package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.core.AnnotationStore
import app.oreshkov.oracleformsmcp.core.FormsDirectoryScanner
import app.oreshkov.oracleformsmcp.core.ModuleCache
import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.core.ModuleNotFetchedException
import app.oreshkov.oracleformsmcp.core.ModuleParser
import app.oreshkov.oracleformsmcp.core.ModuleStaleException
import app.oreshkov.oracleformsmcp.dto.AnnotationCreated
import app.oreshkov.oracleformsmcp.dto.AnnotationRemoved
import app.oreshkov.oracleformsmcp.dto.AnnotationSearchResults
import app.oreshkov.oracleformsmcp.dto.AnnotationView
import app.oreshkov.oracleformsmcp.dto.BlockDetail
import app.oreshkov.oracleformsmcp.dto.BlockList
import app.oreshkov.oracleformsmcp.dto.BlockSummary
import app.oreshkov.oracleformsmcp.dto.ElementAnnotationList
import app.oreshkov.oracleformsmcp.dto.ElementAnnotations
import app.oreshkov.oracleformsmcp.dto.FetchModuleSummary
import app.oreshkov.oracleformsmcp.dto.ModuleAnnotationsView
import app.oreshkov.oracleformsmcp.dto.ModuleList
import app.oreshkov.oracleformsmcp.dto.ModuleOverview
import app.oreshkov.oracleformsmcp.dto.ModuleStatusEntry
import app.oreshkov.oracleformsmcp.dto.ObjectXml
import app.oreshkov.oracleformsmcp.dto.ProgramUnitList
import app.oreshkov.oracleformsmcp.dto.ProgramUnitSource
import app.oreshkov.oracleformsmcp.dto.ProgramUnitSummary
import app.oreshkov.oracleformsmcp.dto.RelationCreated
import app.oreshkov.oracleformsmcp.dto.RelationView
import app.oreshkov.oracleformsmcp.dto.SearchHit
import app.oreshkov.oracleformsmcp.dto.SearchResults
import app.oreshkov.oracleformsmcp.dto.TriggerList
import app.oreshkov.oracleformsmcp.dto.TriggerSource
import app.oreshkov.oracleformsmcp.dto.TriggerSummary
import app.oreshkov.oracleformsmcp.io.Fingerprints
import app.oreshkov.oracleformsmcp.model.Annotation
import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.model.Author
import app.oreshkov.oracleformsmcp.model.ElementId
import app.oreshkov.oracleformsmcp.model.ElementKind
import app.oreshkov.oracleformsmcp.model.ModuleFingerprint
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ProgramUnitInfo
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
import app.oreshkov.oracleformsmcp.model.Relation
import app.oreshkov.oracleformsmcp.model.ScannedModule
import app.oreshkov.oracleformsmcp.model.SourceRef
import app.oreshkov.oracleformsmcp.model.TriggerInfo
import app.oreshkov.oracleformsmcp.model.TriggerLevel
import co.touchlab.kermit.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.streams.asSequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A coarse [FormsService.fetchModule] phase: [step] of [totalSteps], human-readable [message]. */
data class FetchProgress(val step: Int, val totalSteps: Int, val message: String)

/**
 * Orchestrates scan → convert → parse → cache and exposes the read operations the MCP tools call,
 * so the tool files stay declarative adapters. Every read goes through the cached [ModuleIndex]
 * and is staleness-checked against the source file's fingerprint.
 */
class FormsService(
    private val scanner: FormsDirectoryScanner,
    private val converter: ModuleConverter,
    private val parser: ModuleParser,
    private val cache: ModuleCache,
    private val annotationStore: AnnotationStore,
    private val formsDir: Path,
    private val oracleConversion: Boolean,
) {
    private val log = Logger.withTag("FormsService")

    /**
     * Resolves a tool's `module` argument: `NAME.ext` is parsed directly; a bare name matches
     * when exactly one scanned or cached module carries it. Ambiguity and misses raise errors
     * that tell the model what to pass instead.
     */
    suspend fun resolveModule(spec: String): ModuleKey {
        ModuleKey.parseOrNull(spec)?.let { return it }
        val known = (scanner.scan().map { it.key } + cache.list()).distinct()
        val matches = known.filter { it.name.equals(spec.trim(), ignoreCase = true) }
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException(
                "No module named '$spec' in $formsDir. Known modules: " +
                    known.sortedBy { it.toString() }.joinToString(", ").ifEmpty { "(none)" } +
                    ". Call list_modules to see them with their status.",
            )
            else -> throw IllegalArgumentException(
                "'$spec' is ambiguous — it exists as ${matches.joinToString(" and ")}. " +
                    "Pass the name with its extension.",
            )
        }
    }

    /** Scans the forms directory and reports each module's cache status. */
    suspend fun listModules(): ModuleList {
        val scanned = scanner.scan()
        val entries = scanned.map { module ->
            val path = fingerprintSource(module)
            val cached = cache.get(module.key)
            val status = when {
                cached == null -> ModuleStatus.NOT_CACHED
                Fingerprints.matches(cached.fingerprint, Path.of(cached.sourceFile)) -> ModuleStatus.CACHED
                else -> ModuleStatus.STALE
            }
            ModuleStatusEntry(
                module = module.key,
                type = module.key.type,
                path = path.toString(),
                sizeBytes = runCatching { Files.size(path) }.getOrNull(),
                lastModified = runCatching {
                    Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()).toString()
                }.getOrNull(),
                status = status,
                hasPreConverted = module.preConvertedPath != null,
            )
        }
        // Cache entries whose source vanished from the forms directory are still readable.
        val scannedKeys = scanned.map { it.key }.toSet()
        val orphans = cache.list().filterNot { it in scannedKeys }.map { key ->
            ModuleStatusEntry(module = key, type = key.type, status = ModuleStatus.SOURCE_MISSING)
        }
        return ModuleList(
            formsDir = formsDir.toAbsolutePath().toString(),
            oracleHomeConversion = oracleConversion,
            modules = entries + orphans,
        )
    }

    /**
     * Converts (or copies) and indexes [key]. Fingerprint-idempotent: a warm entry whose source
     * is unchanged returns immediately with `fromCache = true`. [onProgress] fires at each phase
     * boundary (never on a warm hit).
     */
    suspend fun fetchModule(
        key: ModuleKey,
        onProgress: suspend (FetchProgress) -> Unit = {},
    ): FetchModuleSummary {
        val scanned = scanner.scan().find { it.key == key }
            ?: throw IllegalArgumentException(
                "Module '$key' was not found in $formsDir. Call list_modules to see what exists.",
            )
        val source = fingerprintSource(scanned)
        cache.get(key)?.let { cached ->
            if (cached.sourceFile == source.toString() &&
                Fingerprints.matches(cached.fingerprint, source)
            ) {
                return cached.summary(fromCache = true)
            }
        }

        log.i { "Converting and indexing $key from $source (${converter.description})" }
        val moduleDir = cache.moduleDir(key)
        onProgress(FetchProgress(1, FETCH_STEPS, "Converting $key"))
        val converted = converter.convert(
            key = key,
            sourcePath = conversionSource(scanned).toString(),
            targetDir = Path.of(moduleDir).resolve("converted").toString(),
        )
        onProgress(FetchProgress(2, FETCH_STEPS, "Parsing $key"))
        // Parsing a large form is CPU-bound; keep it off the caller's dispatcher.
        val parsed = withContext(Dispatchers.Default) { parser.parse(key, converted, moduleDir) }
        onProgress(FetchProgress(3, FETCH_STEPS, "Caching the index of $key"))
        val index = parsed.copy(
            sourceFile = source.toString(),
            fingerprint = Fingerprints.of(source),
        )
        cache.putIndex(index)
        return index.summary(fromCache = false)
    }

    suspend fun overview(key: ModuleKey): ModuleOverview {
        val index = index(key)
        return ModuleOverview(
            module = index.key,
            formsVersion = index.formsVersion,
            blocks = index.blocks.map { it.name },
            triggerCount = index.triggers.size,
            programUnits = index.programUnits.map { it.name },
            attachedLibraries = index.attachedLibraries.map { it.name },
            lovs = index.lovs.map { it.name },
            recordGroups = index.recordGroups.map { it.name },
            windows = index.windows.map { it.name },
            canvases = index.canvases.map { it.name },
            alerts = index.alerts.map { it.name },
            parameters = index.parameters.map { it.name },
            visualAttributes = index.visualAttributes,
            propertyClasses = index.propertyClasses,
            editors = index.editors,
            menus = index.menus.map { it.name },
            objectLibraryTabs = index.objectLibraryTabs.map { it.name },
            annotations = elementAnnotations(index, ElementId(index.key, ElementKind.MODULE, index.key.name)),
        )
    }

    suspend fun listBlocks(key: ModuleKey): BlockList {
        val index = index(key)
        return BlockList(
            module = index.key,
            blocks = index.blocks.map { block ->
                BlockSummary(
                    name = block.name,
                    queryDataSourceName = block.queryDataSourceName,
                    itemCount = block.items.size,
                    triggerCount = block.triggerNames.size +
                        block.items.sumOf { it.triggerNames.size },
                )
            },
        )
    }

    suspend fun getBlock(key: ModuleKey, blockName: String): BlockDetail {
        val index = index(key)
        val block = index.blocks.firstOrNull { it.name.equals(blockName, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "No block '$blockName' in $key. Blocks: ${index.blocks.joinToString(", ") { it.name }}",
            )
        return BlockDetail(
            module = index.key,
            block = block,
            annotations = elementAnnotations(index, ElementId(index.key, ElementKind.BLOCK, block.name)),
        )
    }

    suspend fun listTriggers(
        key: ModuleKey,
        block: String?,
        item: String?,
        level: String?,
        detailed: Boolean = false,
    ): TriggerList {
        val wanted: Set<TriggerLevel>? = when (level?.lowercase()) {
            null, "all" -> null
            "form" -> setOf(TriggerLevel.FORM)
            "block" -> setOf(TriggerLevel.BLOCK)
            "item" -> setOf(TriggerLevel.ITEM)
            "menu" -> setOf(TriggerLevel.MENU)
            else -> throw IllegalArgumentException("level must be one of: form, block, item, menu, all")
        }
        val triggers = index(key).triggers.asSequence()
            .filter { wanted == null || it.level in wanted }
            .filter { block == null || it.blockName.equals(block, ignoreCase = true) }
            .filter { item == null || it.itemName.equals(item, ignoreCase = true) }
            .map {
                TriggerSummary(
                    name = it.name,
                    level = it.level,
                    block = it.blockName,
                    item = it.itemName,
                    // The PL/SQL preview is the bulky per-row field; omit it unless asked.
                    firstLine = if (detailed) it.firstLine else "",
                    lineCount = it.lineCount,
                )
            }
            .toList()
        return TriggerList(module = key, triggers = triggers)
    }

    suspend fun getTrigger(
        key: ModuleKey,
        name: String,
        block: String?,
        item: String?,
        ownerPath: String? = null,
    ): TriggerSource {
        val index = index(key)
        val trigger = resolveTrigger(index, name, ownerPath, block, item)
        val ref = trigger.textRef
            ?: throw IllegalStateException("Trigger '$name' has no recorded PL/SQL body")
        return TriggerSource(
            module = index.key,
            name = trigger.name,
            level = trigger.level,
            block = trigger.blockName,
            item = trigger.itemName,
            text = readRef(key, ref),
            annotations = elementAnnotations(
                index,
                ElementId(index.key, ElementKind.TRIGGER, trigger.name, triggerOwner(trigger)),
            ),
        )
    }

    suspend fun listProgramUnits(key: ModuleKey): ProgramUnitList {
        val index = index(key)
        return ProgramUnitList(
            module = index.key,
            units = index.programUnits.map {
                ProgramUnitSummary(name = it.name, unitType = it.unitType, lineCount = it.lineCount)
            },
        )
    }

    suspend fun getProgramUnit(key: ModuleKey, name: String, unitType: String?): ProgramUnitSource {
        val index = index(key)
        val wantedType = unitType?.let { ProgramUnitType.fromForms(it.replace('_', ' ')) }
        val matches = index.programUnits.filter {
            it.name.equals(name, ignoreCase = true) && (wantedType == null || it.unitType == wantedType)
        }
        val unit = when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException(
                "No program unit '$name' in $key. Call list_program_units to see what exists.",
            )
            else -> throw IllegalArgumentException(
                "Program unit '$name' exists as ${matches.joinToString(" and ") { it.unitType.name }} " +
                    "in $key. Disambiguate with the 'unitType' argument.",
            )
        }
        val ref = unit.textRef
            ?: throw IllegalStateException("Program unit '$name' has no recorded PL/SQL body")
        return ProgramUnitSource(
            module = index.key,
            name = unit.name,
            unitType = unit.unitType,
            text = readRef(key, ref),
            annotations = elementAnnotations(index, programUnitId(index.key, unit)),
        )
    }

    suspend fun searchSource(
        key: ModuleKey,
        query: String,
        regex: Boolean,
        scope: String?,
        maxResults: Int,
        offset: Int = 0,
    ): SearchResults {
        index(key) // staleness/fetched check before touching files
        val includePlsql: Boolean
        val includeXml: Boolean
        when (scope?.lowercase() ?: "plsql") {
            "plsql" -> { includePlsql = true; includeXml = false }
            "xml" -> { includePlsql = false; includeXml = true }
            "all" -> { includePlsql = true; includeXml = true }
            else -> throw IllegalArgumentException("scope must be one of: plsql, xml, all")
        }
        val moduleDir = Path.of(cache.moduleDir(key))
        val cap = maxResults.coerceIn(1, MAX_SEARCH_RESULTS)
        val start = offset.coerceAtLeast(0)
        val pattern = if (regex) Regex(query) else null
        val hits = mutableListOf<SearchHit>()
        var seen = 0 // total matches scanned across all files, for stable offset paging
        var truncated = false

        val files = withContext(Dispatchers.IO) { searchableFiles(moduleDir, includePlsql, includeXml) }
        outer@ for (file in files) {
            val lines = withContext(Dispatchers.IO) { file.readLines() }
            for ((lineIndex, line) in lines.withIndex()) {
                val matches = pattern?.containsMatchIn(line) ?: line.contains(query)
                if (!matches) continue
                if (seen++ < start) continue // skip earlier pages
                if (hits.size == cap) {
                    truncated = true // a further match exists beyond this page
                    break@outer
                }
                hits += SearchHit(
                    path = moduleDir.relativize(file).joinToString("/"),
                    line = lineIndex + 1,
                    snippet = line.trim().take(200),
                )
            }
        }
        return SearchResults(
            query = query,
            hits = hits,
            truncated = truncated,
            offset = start,
            nextOffset = if (truncated) start + hits.size else null,
        )
    }

    suspend fun getObjectXml(key: ModuleKey, objectType: String, name: String, owner: String?): ObjectXml {
        val index = index(key)
        val matches = index.objectRefs.filter {
            it.objectType.equals(objectType, ignoreCase = true) &&
                it.name.equals(name, ignoreCase = true) &&
                (owner == null || it.ownerPath.equals(owner, ignoreCase = true))
        }
        val ref = when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException(
                "No $objectType named '$name' in $key. Object types present: " +
                    index.objectRefs.map { it.objectType }.distinct().sorted().joinToString(", "),
            )
            else -> throw IllegalArgumentException(
                "$objectType '$name' exists at several scopes in $key: " +
                    matches.joinToString(", ") { it.ownerPath ?: "(top level)" } +
                    ". Disambiguate with the 'owner' argument.",
            )
        }
        val xml = readRef(key, ref.ref)
        val capped = xml.length > MAX_OBJECT_XML_CHARS
        return ObjectXml(
            module = index.key,
            objectType = ref.objectType,
            name = ref.name,
            ownerPath = ref.ownerPath,
            xml = if (capped) xml.take(MAX_OBJECT_XML_CHARS) else xml,
            startLine = ref.ref.startLine,
            truncated = capped,
            annotations = elementAnnotations(
                index,
                ElementId(index.key, ElementKind.OBJECT, ref.name, ref.ownerPath),
            ),
        )
    }

    /** Every module with a cached index (feeds the MCP resources). */
    suspend fun listCached(): List<ModuleKey> = cache.list()

    // --- annotation layer (AI/user-supplied meta-information persisted about elements) ---

    /**
     * Persists one [kind] annotation ([body]) about the [elementKind] element named [name]
     * (optionally scoped by [ownerPath]). Requires the module to be freshly fetched: the element
     * is validated against the current index, and the source fingerprint is snapshotted so a later
     * re-index can flag the note as predating the source.
     */
    suspend fun annotate(
        key: ModuleKey,
        elementKind: ElementKind,
        name: String,
        ownerPath: String?,
        kind: AnnotationKind,
        body: String,
        author: Author = Author.AI,
    ): AnnotationCreated {
        require(body.isNotBlank()) { "annotation body must not be blank" }
        val index = index(key)
        val target = resolveElement(index, elementKind, name, ownerPath)
        val annotation = Annotation(
            id = newId(),
            target = target,
            kind = kind,
            body = body.trim(),
            author = author,
            createdAt = now(),
            sourceFingerprint = index.fingerprint,
        )
        annotationStore.addAnnotation(annotation)
        return AnnotationCreated(module = key, annotation = annotation.toView(stale = false))
    }

    /** Records a directed [relType] relation between two elements of the same module (from → to). */
    suspend fun relate(
        key: ModuleKey,
        fromKind: ElementKind,
        fromName: String,
        fromOwner: String?,
        toKind: ElementKind,
        toName: String,
        toOwner: String?,
        relType: String,
        note: String?,
        author: Author = Author.AI,
    ): RelationCreated {
        require(relType.isNotBlank()) { "relType must not be blank" }
        val index = index(key)
        val from = resolveElement(index, fromKind, fromName, fromOwner)
        val to = resolveElement(index, toKind, toName, toOwner)
        val relation = Relation(
            id = newId(),
            from = from,
            to = to,
            relType = relType.trim(),
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            author = author,
            createdAt = now(),
            sourceFingerprint = index.fingerprint,
        )
        annotationStore.addRelation(relation)
        return RelationCreated(module = key, relation = relation.toView(stale = false))
    }

    /**
     * The annotations and relations attached to one element. Served even when the module is stale
     * (each view carries its own drift flag) so knowledge is never hidden by a source change.
     */
    suspend fun getElementAnnotations(
        key: ModuleKey,
        elementKind: ElementKind,
        name: String,
        ownerPath: String?,
    ): ElementAnnotationList {
        val cached = cache.get(key) ?: throw ModuleNotFetchedException(key)
        val target = resolveElement(cached, elementKind, name, ownerPath)
        return ElementAnnotationList(
            module = key,
            element = target,
            annotations = elementAnnotations(cached, target),
        )
    }

    /** Filters a module's stored notes ([text]/[kind]/[tag]) and relations ([text]) — case-insensitive. */
    suspend fun searchAnnotations(
        key: ModuleKey,
        text: String?,
        kind: AnnotationKind?,
        tag: String?,
    ): AnnotationSearchResults {
        val cached = cache.get(key)
        val (notes, relations) = storeViews(key, cached?.let { Path.of(it.sourceFile) })
        val query = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        var noteHits = notes
        if (kind != null) noteHits = noteHits.filter { it.kind == kind }
        if (tag != null) {
            noteHits = noteHits.filter { it.kind == AnnotationKind.TAG && it.body.equals(tag.trim(), ignoreCase = true) }
        }
        if (query != null) {
            noteHits = noteHits.filter {
                it.body.lowercase().contains(query) || it.target?.name?.lowercase()?.contains(query) == true
            }
        }
        // A note-specific filter (kind/tag) excludes relations, which have neither.
        var relationHits = if (kind != null || tag != null) emptyList() else relations
        if (query != null) {
            relationHits = relationHits.filter {
                it.relType.lowercase().contains(query) ||
                    it.note?.lowercase()?.contains(query) == true ||
                    it.from?.name?.lowercase()?.contains(query) == true ||
                    it.to?.name?.lowercase()?.contains(query) == true
            }
        }
        return AnnotationSearchResults(module = key, notes = noteHits, relations = relationHits)
    }

    /** Removes the annotation or relation with [id] from [key]'s store. */
    suspend fun removeAnnotation(key: ModuleKey, id: String): AnnotationRemoved =
        AnnotationRemoved(module = key, id = id, removed = annotationStore.remove(key, id))

    /** The whole annotation set for a module, for the `oracleforms://{module}/annotations` resource. */
    suspend fun moduleAnnotations(key: ModuleKey): ModuleAnnotationsView {
        val cached = cache.get(key)
        val (notes, relations) = storeViews(key, cached?.let { Path.of(it.sourceFile) })
        return ModuleAnnotationsView(module = key, notes = notes, relations = relations)
    }

    /**
     * The cached index for [key], with the staleness contract every read tool relies on:
     * no entry → [ModuleNotFetchedException]; source changed on disk → [ModuleStaleException];
     * source deleted → still served (list_modules reports it as SOURCE_MISSING).
     */
    suspend fun index(key: ModuleKey): ModuleIndex {
        val cached = cache.get(key) ?: throw ModuleNotFetchedException(key)
        val source = Path.of(cached.sourceFile)
        if (source.exists() && !Fingerprints.matches(cached.fingerprint, source)) {
            throw ModuleStaleException(key)
        }
        return cached
    }

    // --- internals ---

    /** The file the pipeline actually consumes, and therefore fingerprints. */
    private fun fingerprintSource(module: ScannedModule): Path = Path.of(conversionSource(module))

    private fun conversionSource(module: ScannedModule): String =
        if (oracleConversion) {
            module.binaryPath ?: module.preConvertedPath!!
        } else {
            module.preConvertedPath ?: module.binaryPath!!
        }

    /** Reads the line range of [ref], guarded against paths escaping the module's cache dir. */
    private suspend fun readRef(key: ModuleKey, ref: SourceRef): String {
        val moduleDir = Path.of(cache.moduleDir(key)).normalize()
        val file = moduleDir.resolve(ref.file).normalize()
        require(file.startsWith(moduleDir)) { "Path escapes the module cache dir: ${ref.file}" }
        if (!file.exists()) {
            throw IllegalStateException(
                "Cached file ${ref.file} of $key is missing. Call fetch_module to re-index it.",
            )
        }
        val lines = withContext(Dispatchers.IO) { file.readLines() }
        val end = ref.endLine.coerceAtMost(lines.size)
        if (ref.startLine > end) return ""
        return lines.subList(ref.startLine - 1, end).joinToString("\n")
    }

    private fun searchableFiles(moduleDir: Path, plsql: Boolean, xml: Boolean): List<Path> {
        val files = mutableListOf<Path>()
        val plsqlDir = moduleDir.resolve("plsql")
        if (plsql && plsqlDir.exists()) {
            Files.walk(plsqlDir).use { stream ->
                files += stream.asSequence().filter { it.isRegularFile() }.toList()
            }
        }
        val convertedDir = moduleDir.resolve("converted")
        if (convertedDir.exists()) {
            Files.walk(convertedDir).use { stream ->
                files += stream.asSequence().filter { file ->
                    file.isRegularFile() && when {
                        // A .pld is the PL/SQL itself; XML is the raw document.
                        file.name.endsWith(".pld", ignoreCase = true) -> plsql
                        else -> xml
                    }
                }.toList()
            }
        }
        return files.sortedBy { it.toString() }
    }

    // --- annotation internals ---

    /** Notes/relations for [element], drawn from [index]'s stored annotations, with drift resolved. */
    private suspend fun elementAnnotations(index: ModuleIndex, element: ElementId): ElementAnnotations {
        val (notes, relations) = storeViews(index.key, Path.of(index.sourceFile))
        val id = element.canonical()
        return ElementAnnotations(
            notes = notes.filter { it.target?.canonical() == id },
            relations = relations.filter { it.from?.canonical() == id || it.to?.canonical() == id },
        )
    }

    /** All of a module's stored annotations/relations as views, each drift-flagged against [sourcePath]. */
    private suspend fun storeViews(
        module: ModuleKey,
        sourcePath: Path?,
    ): Pair<List<AnnotationView>, List<RelationView>> {
        val stored = annotationStore.forModule(module)
        val notes = stored.annotations.map { it.toView(isStale(sourcePath, it.sourceFingerprint)) }
        val relations = stored.relations.map { it.toView(isStale(sourcePath, it.sourceFingerprint)) }
        return notes to relations
    }

    /** True when an annotation's snapshot no longer matches the live source — the note predates it. */
    private fun isStale(sourcePath: Path?, fingerprint: ModuleFingerprint?): Boolean =
        fingerprint != null && sourcePath != null && sourcePath.exists() &&
            !Fingerprints.matches(fingerprint, sourcePath)

    /**
     * Canonical owner path of a trigger: `block.item`, `block`, or `null` at form level.
     *
     * Known limitation: menu-level triggers are owner-less too ([TriggerInfo] carries no menu
     * owner), so same-named triggers in two menus of one module cannot be told apart.
     */
    private fun triggerOwner(trigger: TriggerInfo): String? = when {
        trigger.itemName != null -> "${trigger.blockName}.${trigger.itemName}"
        else -> trigger.blockName
    }

    /**
     * Resolves one trigger of [index] by [name], shared by get_trigger and the annotation tools
     * so both address triggers with the same vocabulary. [ownerPath] narrows the scope:
     * [FORM_LEVEL_OWNER] selects the owner-less form-level trigger (`:` cannot occur in a Forms
     * name, so the token never collides with a block named FORM), a block name matches the block's
     * own and its items' triggers with an exact block-level match taking precedence, and
     * `block.item` matches exactly. The legacy [block]/[item] filters keep get_trigger's original
     * arguments working. Misses and residual ambiguity say what to pass instead.
     */
    private fun resolveTrigger(
        index: ModuleIndex,
        name: String,
        ownerPath: String?,
        block: String? = null,
        item: String? = null,
    ): TriggerInfo {
        val wantsFormLevel = ownerPath?.equals(FORM_LEVEL_OWNER, ignoreCase = true) == true
        val matches = index.triggers.filter {
            it.name.equals(name, ignoreCase = true) &&
                (block == null || it.blockName.equals(block, ignoreCase = true)) &&
                (item == null || it.itemName.equals(item, ignoreCase = true)) &&
                when {
                    ownerPath == null -> true
                    wantsFormLevel -> triggerOwner(it) == null
                    else -> ownerPath.equals(it.blockName, ignoreCase = true) ||
                        ownerPath.equals(triggerOwner(it), ignoreCase = true)
                }
        }
        return when {
            matches.size == 1 -> matches.single()
            matches.isEmpty() -> throw IllegalArgumentException(
                "No trigger '$name' in ${index.key}" +
                    (ownerPath?.let { " under '$it'" } ?: block?.let { " for block '$it'" } ?: "") +
                    ". Call list_triggers to see what exists.",
            )
            else -> matches.singleOrNull { ownerPath != null && ownerPath.equals(triggerOwner(it), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Trigger '$name' exists at several scopes in ${index.key}: " +
                        matches.joinToString(", ") { ownerToken(it) } +
                        ". Pass ownerPath with one of these to disambiguate.",
                )
        }
    }

    /** The ownerPath token that selects [trigger] exactly, as quoted in ambiguity errors. */
    private fun ownerToken(trigger: TriggerInfo): String =
        triggerOwner(trigger)?.let { "'$it'" } ?: "'$FORM_LEVEL_OWNER' (form level)"

    /**
     * Stable annotation identity of a program unit. Package spec and body share a name, so they
     * carry their [ProgramUnitType] as the owner path; all other unit types are unique by name
     * and stay owner-less.
     */
    private fun programUnitId(module: ModuleKey, unit: ProgramUnitInfo): ElementId =
        ElementId(
            module,
            ElementKind.PROGRAM_UNIT,
            unit.name,
            ownerPath = unit.unitType
                .takeIf { it == ProgramUnitType.PACKAGE_SPEC || it == ProgramUnitType.PACKAGE_BODY }
                ?.name,
        )

    /**
     * Resolves an annotation target against the parsed [index], validating that the element exists
     * and returning its canonical name and owner path so writes and reads address it identically.
     */
    private fun resolveElement(
        index: ModuleIndex,
        kind: ElementKind,
        name: String,
        ownerPath: String?,
    ): ElementId {
        fun eid(canonicalName: String, owner: String?) = ElementId(index.key, kind, canonicalName, owner)
        fun fail(known: List<String>): Nothing = throw IllegalArgumentException(
            "No ${kind.name.lowercase()} named '$name' in ${index.key}" +
                (ownerPath?.let { " under '$it'" } ?: "") + ". " +
                (if (known.isEmpty()) "This module has none of that kind."
                else "Known: ${known.distinct().sorted().take(50).joinToString(", ")}.") +
                " Fetch the module and use the list_/get_ tools to see valid element names.",
        )
        // Only for kinds whose identity is name-only (owner = null): duplicates would all map to
        // the same ElementId, so first-match cannot mistarget. A kind with a scoped identity
        // (item, menu item, trigger, package unit) must get its own branch instead.
        fun simple(names: List<String>): ElementId =
            names.firstOrNull { it.equals(name, ignoreCase = true) }?.let { eid(it, null) } ?: fail(names)

        return when (kind) {
            ElementKind.MODULE -> eid(index.key.name, null)
            ElementKind.BLOCK -> simple(index.blocks.map { it.name })
            ElementKind.PROGRAM_UNIT -> {
                val wantedType = ownerPath?.let { ProgramUnitType.fromForms(it.replace('_', ' ')) }
                val matches = index.programUnits.filter {
                    it.name.equals(name, ignoreCase = true) &&
                        (wantedType == null || it.unitType == wantedType)
                }
                when {
                    matches.size == 1 -> programUnitId(index.key, matches.single())
                    matches.isEmpty() -> fail(index.programUnits.map { it.name })
                    else -> throw IllegalArgumentException(
                        "Program unit '$name' exists as " +
                            matches.joinToString(" and ") { it.unitType.name } +
                            " in ${index.key}. Pass ownerPath='PACKAGE_SPEC' or " +
                            "ownerPath='PACKAGE_BODY' to pick one.",
                    )
                }
            }
            ElementKind.LOV -> simple(index.lovs.map { it.name })
            ElementKind.RECORD_GROUP -> simple(index.recordGroups.map { it.name })
            ElementKind.CANVAS -> simple(index.canvases.map { it.name })
            ElementKind.WINDOW -> simple(index.windows.map { it.name })
            ElementKind.ALERT -> simple(index.alerts.map { it.name })
            ElementKind.PARAMETER -> simple(index.parameters.map { it.name })
            ElementKind.MENU -> simple(index.menus.map { it.name })
            ElementKind.ITEM -> {
                val matches = index.blocks
                    .filter { ownerPath == null || it.name.equals(ownerPath, ignoreCase = true) }
                    .flatMap { block ->
                        block.items.filter { it.name.equals(name, ignoreCase = true) }.map { block to it }
                    }
                when {
                    matches.size == 1 -> matches.single().let { (block, item) -> eid(item.name, block.name) }
                    matches.isEmpty() -> fail(index.blocks.flatMap { b -> b.items.map { "${b.name}.${it.name}" } })
                    else -> throw IllegalArgumentException(
                        "Item '$name' exists in several blocks in ${index.key}: " +
                            matches.joinToString(", ") { (block, _) -> block.name } +
                            ". Pass ownerPath (the owning block) to disambiguate.",
                    )
                }
            }
            ElementKind.MENU_ITEM -> {
                val matches = index.menus
                    .filter { ownerPath == null || it.name.equals(ownerPath, ignoreCase = true) }
                    .flatMap { menu ->
                        menu.items.filter { it.name.equals(name, ignoreCase = true) }.map { menu to it }
                    }
                when {
                    matches.size == 1 -> matches.single().let { (menu, item) -> eid(item.name, menu.name) }
                    matches.isEmpty() -> fail(index.menus.flatMap { m -> m.items.map { "${m.name}.${it.name}" } })
                    else -> throw IllegalArgumentException(
                        "Menu item '$name' exists in several menus in ${index.key}: " +
                            matches.joinToString(", ") { (menu, _) -> menu.name } +
                            ". Pass ownerPath (the owning menu) to disambiguate.",
                    )
                }
            }
            ElementKind.TRIGGER ->
                resolveTrigger(index, name, ownerPath).let { eid(it.name, triggerOwner(it)) }
            ElementKind.OBJECT -> {
                val matches = index.objectRefs.filter {
                    it.name.equals(name, ignoreCase = true) &&
                        (ownerPath == null || ownerPath.equals(it.ownerPath, ignoreCase = true))
                }
                when {
                    matches.size == 1 -> matches.single().let { eid(it.name, it.ownerPath) }
                    matches.isEmpty() -> fail(index.objectRefs.map { it.name })
                    else -> throw IllegalArgumentException(
                        "Object '$name' exists at several scopes in ${index.key}: " +
                            matches.joinToString(", ") { it.ownerPath ?: "(top level)" } +
                            ". Pass ownerPath to disambiguate.",
                    )
                }
            }
        }
    }

    private fun Annotation.toView(stale: Boolean) = AnnotationView(
        id = id,
        target = target,
        kind = kind,
        body = body,
        author = author,
        createdAt = createdAt.toString(),
        staleAgainstSource = stale,
    )

    private fun Relation.toView(stale: Boolean) = RelationView(
        id = id,
        from = from,
        to = to,
        relType = relType,
        note = note,
        author = author,
        createdAt = createdAt.toString(),
        staleAgainstSource = stale,
    )

    private fun newId(): String = UUID.randomUUID().toString()

    private fun now(): kotlin.time.Instant =
        kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis())

    private fun ModuleIndex.summary(fromCache: Boolean): FetchModuleSummary = FetchModuleSummary(
        module = key,
        formsVersion = formsVersion,
        converter = converter.description,
        blockCount = blocks.size,
        itemCount = blocks.sumOf { it.items.size },
        triggerCount = triggers.size,
        programUnitCount = programUnits.size,
        attachedLibraries = attachedLibraries.map { it.name },
        fromCache = fromCache,
    )

    private companion object {
        const val MAX_SEARCH_RESULTS = 200
        const val MAX_OBJECT_XML_CHARS = 500_000
        const val FETCH_STEPS = 3

        /**
         * The ownerPath token that selects the owner-less form-level trigger among same-named
         * ones at other levels. `:` is illegal in Forms object names, so the token can never
         * collide with a block that is literally named FORM.
         */
        const val FORM_LEVEL_OWNER = ":FORM"
    }
}
