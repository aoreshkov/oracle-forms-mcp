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
import app.oreshkov.oracleformsmcp.dto.BodySource
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
import app.oreshkov.oracleformsmcp.model.InheritanceRef
import app.oreshkov.oracleformsmcp.model.ModuleFingerprint
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ModuleType
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
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.streams.asSequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A coarse [FormsService.fetchModule] phase: [step] of [totalSteps], human-readable [message]. */
data class FetchProgress(val step: Int, val totalSteps: Int, val message: String)

/**
 * `list_modules` page size when the caller names none, and the ceiling it is clamped to. Shared
 * with `ListModulesTool` so the numbers the tool description promises are the ones enforced here.
 */
internal const val DEFAULT_MODULE_PAGE: Int = 100
internal const val MAX_MODULE_PAGE: Int = 500

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
    /**
     * The configured [converter] can turn a binary module into its text form (ORACLE_HOME tools or
     * a `--convert-command` command line), as opposed to copy-mode which can only read files that were
     * converted elsewhere.
     */
    private val binaryConversion: Boolean,
    /**
     * Where converted XML (and `.pld`) files are kept, from `--converted-dir`. One flat directory
     * shared by every module, each file named canonically after its [ModuleKey]
     * (`orders_fmb.xml`, `utils.pld`), so the text forms are browsable and reusable outside the
     * cache. `null` keeps them inside the module's own cache directory.
     *
     * Conversion runs directly in this directory: the converter is driven with its working
     * directory as its output directory, so a site converter that writes where it is told needs no
     * arguments to find its way here. Output is attributed by canonical name first (see
     * [ModuleKey.convertedFileName]), with the "newest matching file written after the run started"
     * heuristic as a fallback for converters that name their output freely — and [sharedOutputLock]
     * keeps that fallback unambiguous.
     */
    private val convertedDir: Path? = null,
) {
    private val log = Logger.withTag("FormsService")

    /**
     * Serialises conversions when [convertedDir] makes every module share one output directory: the
     * fallback attribution heuristic ("newest matching file") cannot tell two concurrent runs
     * apart. Only the converter call is held, not parsing or caching — and the lock is skipped
     * entirely when each module converts in its own cache directory.
     */
    private val sharedOutputLock = Mutex()

    /**
     * Serialises the whole fetch pipeline per module. Tool handlers are dispatched concurrently
     * (MCP SDK 0.15+), so two overlapping `fetch_module` calls for one key would otherwise both
     * miss the cache and both run convert → parse → putIndex into the same cache directory.
     *
     * Distinct from [sharedOutputLock], which serialises *across* modules and only when they share
     * one `--converted-dir`. Always taken before [sharedOutputLock], never the reverse, so the two
     * cannot deadlock. Bounded by the number of modules in the forms directory.
     */
    private val fetchLocks = ConcurrentHashMap<ModuleKey, Mutex>()

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

    /**
     * Scans the forms directory and reports one filtered, capped page of module cache statuses.
     *
     * A real forms directory holds thousands of modules, so an unfiltered, unbounded answer is
     * larger than any client's tool-output budget. The cheap filters run first: [pattern] and
     * [type] are pure string work, so a narrowed call never stats — let alone hashes — a module it
     * will not report. Only the survivors get a status, which costs a cache read plus a stat of
     * the source file.
     *
     * @param pattern case-insensitive substring of the module name, or a regex when [regex] is set
     * @param status keeps only rows in that state; [ModuleList.countsByStatus] still describes the
     *   whole name/type-filtered set, so a status filter narrows the rows without blinding the
     *   caller to what else is there
     * @param cursor an opaque [ModuleList.nextCursor] from a previous call; paging is keyset-based
     *   on the canonical module key, so a page boundary survives modules appearing or disappearing
     *   between calls
     */
    suspend fun listModules(
        pattern: String? = null,
        regex: Boolean = false,
        type: ModuleType? = null,
        status: ModuleStatus? = null,
        limit: Int? = null,
        cursor: String? = null,
    ): ModuleList {
        val scanned = scanner.scan()
        val scannedKeys = scanned.mapTo(mutableSetOf()) { it.key }
        val cachedKeys = cache.list().toSet()
        // One ordered universe: scanned modules plus cache entries whose source vanished from the
        // forms directory (still readable). Sorted by canonical key so cursors are stable.
        val universe = (scanned.map { it.key to it } + cachedKeys.filterNot { it in scannedKeys }.map { it to null })
            .sortedBy { (key, _) -> key.toString() }

        val nameMatcher = pattern?.let { moduleNameMatcher(it, regex) }
        val matching = universe.filter { (key, _) ->
            (nameMatcher == null || nameMatcher(key.name)) && (type == null || key.type == type)
        }

        val rows = withContext(Dispatchers.IO) {
            matching.map { (key, module) -> statusEntry(key, module, key in cachedKeys) }
        }
        val countsByStatus = rows.groupingBy { it.status }.eachCount()
        val selected = if (status == null) rows else rows.filter { it.status == status }

        val after = cursor?.let { decodeModuleCursor(it) }
        val remaining = if (after == null) selected else selected.filter { it.module.toString() > after }
        val page = remaining.take((limit ?: DEFAULT_MODULE_PAGE).coerceIn(1, MAX_MODULE_PAGE))
        val truncated = page.size < remaining.size
        return ModuleList(
            formsDir = formsDir.toAbsolutePath().toString(),
            oracleHomeConversion = binaryConversion,
            total = selected.size,
            returned = page.size,
            truncated = truncated,
            nextCursor = if (truncated) encodeModuleCursor(page.last().module) else null,
            countsByStatus = countsByStatus,
            modules = page,
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
    ): FetchModuleSummary =
        // The warm path is inside the lock on purpose: hoisting the cache check out would restore
        // the very race this closes. A caller that arrives during a cold fetch of the same module
        // waits, then gets that fetch's result with `fromCache = true` instead of redoing the work.
        fetchLocks.computeIfAbsent(key) { Mutex() }.withLock { fetchModuleLocked(key, onProgress) }

    private suspend fun fetchModuleLocked(
        key: ModuleKey,
        onProgress: suspend (FetchProgress) -> Unit,
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
        val converted =
            if (convertedDir == null) convert(key, scanned)
            else sharedOutputLock.withLock { convert(key, scanned) }
        val textForm = canonicalizeConverted(key, converted)
        onProgress(FetchProgress(2, FETCH_STEPS, "Parsing $key"))
        // Parsing a large form is CPU-bound; keep it off the caller's dispatcher.
        val parsed = withContext(Dispatchers.Default) { parser.parse(key, textForm.toString(), moduleDir) }
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
        // Fourteen name-only sections, any of which a generated module can blow up; cap each and
        // report one flag rather than fourteen.
        val cap = NameSectionCap()
        val blocks = cap(index.blocks.map { it.name })
        val programUnits = cap(index.programUnits.map { it.name })
        val attachedLibraries = cap(index.attachedLibraries.map { it.name })
        val lovs = cap(index.lovs.map { it.name })
        val recordGroups = cap(index.recordGroups.map { it.name })
        val windows = cap(index.windows.map { it.name })
        val canvases = cap(index.canvases.map { it.name })
        val alerts = cap(index.alerts.map { it.name })
        val parameters = cap(index.parameters.map { it.name })
        val visualAttributes = cap(index.visualAttributes)
        val propertyClasses = cap(index.propertyClasses)
        val editors = cap(index.editors)
        val menus = cap(index.menus.map { it.name })
        val objectLibraryTabs = cap(index.objectLibraryTabs.map { it.name })
        return ModuleOverview(
            module = index.key,
            formsVersion = index.formsVersion,
            truncated = cap.truncated,
            blocks = blocks,
            triggerCount = index.triggers.size,
            programUnits = programUnits,
            attachedLibraries = attachedLibraries,
            lovs = lovs,
            recordGroups = recordGroups,
            windows = windows,
            canvases = canvases,
            alerts = alerts,
            parameters = parameters,
            visualAttributes = visualAttributes,
            propertyClasses = propertyClasses,
            editors = editors,
            menus = menus,
            objectLibraryTabs = objectLibraryTabs,
            annotations = elementAnnotations(index, ElementId(index.key, ElementKind.MODULE, index.key.name)),
        )
    }

    suspend fun listBlocks(key: ModuleKey): BlockList {
        val index = index(key)
        val (blocks, truncated) = capRows(
            index.blocks.map { block ->
                BlockSummary(
                    name = block.name,
                    queryDataSourceName = block.queryDataSourceName,
                    itemCount = block.items.size,
                    triggerCount = block.triggerNames.size +
                        block.items.sumOf { it.triggerNames.size },
                )
            },
        )
        return BlockList(
            module = index.key,
            total = index.blocks.size,
            truncated = truncated,
            blocks = blocks,
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
            hint = block.inherited?.let { ref ->
                inheritedHint(
                    subject = "Block '${block.name}'",
                    ref = ref,
                    parentKey = inheritedModuleKey(ref),
                    nextCall = { "get_block(module=\"$it\", block=\"${ref.name ?: block.name}\")" },
                    resolveAttempted = false,
                    resolvable = false,
                )
            },
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
        val matching = index(key).triggers.asSequence()
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
                    // firstLine is the parsed body's first non-blank line, so an empty one means
                    // the body holds no code — the same test getTrigger makes against the text.
                    bodySource = bodySourceOf(it.firstLine, it.inherited),
                )
            }
            .toList()
        val (triggers, truncated) = capRows(matching)
        return TriggerList(
            module = key,
            total = matching.size,
            truncated = truncated,
            triggers = triggers,
        )
    }

    /**
     * One trigger's PL/SQL. When the trigger is subclassed its body is empty *in this module*, so
     * the result says so ([BodySource.INHERITED]) and carries the pointer plus the call that
     * reaches the code — never a bare `""`, which reads as "this trigger does nothing".
     *
     * [resolve] follows that pointer, but only into modules that are **already cached**: fetching
     * one would run a conversion, and this read is annotated `readOnlyHint`. When the walk cannot
     * finish, the pointer and the hint are returned unchanged rather than an error.
     */
    suspend fun getTrigger(
        key: ModuleKey,
        name: String,
        block: String?,
        item: String?,
        ownerPath: String? = null,
        resolve: Boolean = false,
    ): TriggerSource {
        val index = index(key)
        val trigger = resolveTrigger(index, name, ownerPath, block, item)
        val ref = trigger.textRef
            ?: throw IllegalStateException("Trigger '$name' has no recorded PL/SQL body")
        val own = readRef(key, ref)
        val inherited = trigger.inherited.takeIf { bodySourceOf(own, it) == BodySource.INHERITED }
        val followed = if (resolve && inherited != null) {
            followInherited(inherited) { parentIndex, pointer ->
                val found = resolveTrigger(parentIndex, trigger.name, suggestedOwnerPath(pointer))
                InheritedStep(found.inherited, found.textRef)
            }
        } else {
            null
        }
        return TriggerSource(
            module = index.key,
            name = trigger.name,
            level = trigger.level,
            block = trigger.blockName,
            item = trigger.itemName,
            text = followed?.text ?: own,
            bodySource = if (followed != null) BodySource.RESOLVED else bodySourceOf(own, inherited),
            inherited = inherited,
            resolvedFrom = followed?.module,
            hint = if (inherited == null || followed != null) null else inheritedHint(
                subject = "Trigger '${trigger.name}'" +
                    (triggerOwner(trigger)?.let { " on '$it'" } ?: " at form level"),
                ref = inherited,
                parentKey = inheritedModuleKey(inherited),
                nextCall = { parent ->
                    "get_trigger(module=\"$parent\", name=\"${trigger.name}\"" +
                        (suggestedOwnerPath(inherited)?.let { ", ownerPath=\"$it\")" } ?: ")")
                },
                resolveAttempted = resolve,
                resolvable = true,
            ),
            annotations = elementAnnotations(
                index,
                ElementId(index.key, ElementKind.TRIGGER, trigger.name, triggerOwner(trigger)),
            ),
        )
    }

    suspend fun listProgramUnits(key: ModuleKey): ProgramUnitList {
        val index = index(key)
        val (units, truncated) = capRows(
            index.programUnits.map {
                ProgramUnitSummary(name = it.name, unitType = it.unitType, lineCount = it.lineCount)
            },
        )
        return ProgramUnitList(
            module = index.key,
            total = index.programUnits.size,
            truncated = truncated,
            units = units,
        )
    }

    /** One program unit's PL/SQL; subclassing and [resolve] behave exactly as in [getTrigger]. */
    suspend fun getProgramUnit(
        key: ModuleKey,
        name: String,
        unitType: String?,
        resolve: Boolean = false,
    ): ProgramUnitSource {
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
        val own = readRef(key, ref)
        val inherited = unit.inherited.takeIf { bodySourceOf(own, it) == BodySource.INHERITED }
        val followed = if (resolve && inherited != null) {
            followInherited(inherited) { parentIndex, _ ->
                val found = parentIndex.programUnits.first {
                    it.name.equals(unit.name, ignoreCase = true) && it.unitType == unit.unitType
                }
                InheritedStep(found.inherited, found.textRef)
            }
        } else {
            null
        }
        return ProgramUnitSource(
            module = index.key,
            name = unit.name,
            unitType = unit.unitType,
            text = followed?.text ?: own,
            bodySource = if (followed != null) BodySource.RESOLVED else bodySourceOf(own, inherited),
            inherited = inherited,
            resolvedFrom = followed?.module,
            hint = if (inherited == null || followed != null) null else inheritedHint(
                subject = "Program unit '${unit.name}'",
                ref = inherited,
                parentKey = inheritedModuleKey(inherited),
                nextCall = { parent ->
                    "get_program_unit(module=\"$parent\", name=\"${unit.name}\", " +
                        "unitType=\"${unit.unitType.name}\")"
                },
                resolveAttempted = resolve,
                resolvable = true,
            ),
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
        val index = index(key) // staleness/fetched check before touching files
        val includePlsql: Boolean
        val includeXml: Boolean
        when (scope?.lowercase() ?: "plsql") {
            "plsql" -> { includePlsql = true; includeXml = false }
            "xml" -> { includePlsql = false; includeXml = true }
            "all" -> { includePlsql = true; includeXml = true }
            else -> throw IllegalArgumentException("scope must be one of: plsql, xml, all")
        }
        val cap = maxResults.coerceIn(1, MAX_SEARCH_RESULTS)
        val start = offset.coerceAtLeast(0)
        val pattern = if (regex) Regex(query) else null
        val hits = mutableListOf<SearchHit>()
        var seen = 0 // total matches scanned across all files, for stable offset paging
        var truncated = false

        val files = withContext(Dispatchers.IO) { searchableFiles(index, includePlsql, includeXml) }
        outer@ for ((refPath, file) in files) {
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
                    path = refPath,
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
            // The fragment itself only shows SubclassSubObject="true"; the parent pointer lives on
            // the enclosing element, so it is served here rather than left one call away.
            inherited = ref.inherited,
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
        val (pagedNotes, notesCut) = capRows(noteHits)
        val (pagedRelations, relationsCut) = capRows(relationHits)
        return AnnotationSearchResults(
            module = key,
            totalNotes = noteHits.size,
            totalRelations = relationHits.size,
            truncated = notesCut || relationsCut,
            notes = pagedNotes,
            relations = pagedRelations,
        )
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

    // --- list_modules: matching, status, cursors ---

    /** Name predicate for `list_modules`; a bad regex fails as an argument error, not a crash. */
    private fun moduleNameMatcher(pattern: String, regex: Boolean): (String) -> Boolean {
        if (!regex) return { name -> name.contains(pattern, ignoreCase = true) }
        val compiled = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid regex 'pattern' for list_modules: ${e.message}. " +
                    "Drop the 'regex' flag to match the pattern as a plain substring.",
                e,
            )
        }
        return { name -> compiled.containsMatchIn(name) }
    }

    /**
     * Resolves one row's cache status and file metadata. [isCached] comes from the cache's
     * directory listing, so a never-fetched module answers without opening its index at all —
     * the common case on a cold cache and on every `NOT_CACHED` row of a warm one.
     */
    private suspend fun statusEntry(
        key: ModuleKey,
        module: ScannedModule?,
        isCached: Boolean,
    ): ModuleStatusEntry {
        if (module == null) {
            return ModuleStatusEntry(
                name = key.name,
                module = key,
                type = key.type,
                status = ModuleStatus.SOURCE_MISSING,
            )
        }
        val path = fingerprintSource(module)
        val cached = if (isCached) cache.get(key) else null
        val status = when {
            cached == null -> ModuleStatus.NOT_CACHED
            Fingerprints.matches(cached.fingerprint, Path.of(cached.sourceFile)) -> ModuleStatus.CACHED
            else -> ModuleStatus.STALE
        }
        // One stat, not two: size and mtime come from the same attribute read.
        val attributes = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull()
        return ModuleStatusEntry(
            name = key.name,
            module = key,
            type = key.type,
            path = path.toString(),
            sizeBytes = attributes?.size(),
            lastModified = attributes?.let { Instant.ofEpochMilli(it.lastModifiedTime().toMillis()).toString() },
            status = status,
            hasPreConverted = module.preConvertedPath != null,
        )
    }

    /**
     * Mints the opaque continuation token clients pass back as `cursor`. MCP requires cursors to be
     * treated as opaque, so the encoding exists to stop callers from constructing one by hand — not
     * as a secret. It is a keyset cursor: it names the last key of the page, never an offset.
     */
    private fun encodeModuleCursor(key: ModuleKey): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$MODULE_CURSOR_PREFIX$key".toByteArray(Charsets.UTF_8))

    /** The canonical key a [encodeModuleCursor] token points just past. */
    private fun decodeModuleCursor(cursor: String): String {
        val decoded = runCatching { String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8) }.getOrNull()
        require(decoded != null && decoded.startsWith(MODULE_CURSOR_PREFIX)) {
            "Invalid 'cursor' for list_modules. Pass back the 'nextCursor' from a previous call " +
                "verbatim, or omit 'cursor' to start from the first page."
        }
        return decoded.removePrefix(MODULE_CURSOR_PREFIX)
    }

    /**
     * Caps a list-shaped result: the rows to return plus whether any were dropped. Every list tool
     * goes through this, so a pathological module returns a page that says it was cut instead of a
     * response no client can accept.
     */
    private fun <T> capRows(rows: List<T>): Pair<List<T>, Boolean> =
        if (rows.size <= MAX_LIST_ROWS) rows to false else rows.take(MAX_LIST_ROWS) to true

    /**
     * [capRows] for the name-only sections of `get_module_overview`: caps each section and
     * remembers whether any of them was cut, so the overview carries one honest [truncated] flag
     * instead of one per section.
     */
    private class NameSectionCap {
        var truncated: Boolean = false
            private set

        operator fun invoke(names: List<String>): List<String> {
            if (names.size <= MAX_OVERVIEW_NAMES) return names
            truncated = true
            return names.take(MAX_OVERVIEW_NAMES)
        }
    }

    private fun conversionSource(module: ScannedModule): String =
        if (binaryConversion) {
            module.binaryPath ?: module.preConvertedPath!!
        } else {
            module.preConvertedPath ?: module.binaryPath!!
        }

    /**
     * Runs the configured converter for [key] with [convertedDirOf] as both its working and its
     * output directory, and returns the file it produced.
     */
    private suspend fun convert(key: ModuleKey, scanned: ScannedModule): Path =
        Path.of(
            converter.convert(
                key = key,
                sourcePath = conversionSource(scanned).toString(),
                targetDir = convertedDirOf(key).toString(),
            ),
        )

    /** Reads the line range of [ref], guarded against paths escaping the module's cache dir. */
    private suspend fun readRef(key: ModuleKey, ref: SourceRef): String {
        val file = resolveRef(key, ref.file)
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

    /**
     * The file a [SourceRef.file] path addresses. `converted/…` resolves against [convertedDirOf]
     * (which is the module's own cache subdirectory unless [convertedDir] relocated it), everything
     * else against the module's cache directory. Either way the result must stay inside the root it
     * was resolved against.
     */
    private fun resolveRef(key: ModuleKey, refFile: String): Path {
        val prefix = "$CONVERTED_DIR/"
        val (root, relative) =
            if (refFile.startsWith(prefix)) convertedDirOf(key) to refFile.removePrefix(prefix)
            else Path.of(cache.moduleDir(key)) to refFile
        val base = root.normalize()
        val file = base.resolve(relative).normalize()
        require(file.startsWith(base)) { "Path escapes the module cache dir: $refFile" }
        return file
    }

    /** Where [key]'s converted text form lives: the configured [convertedDir], else in its cache entry. */
    private fun convertedDirOf(key: ModuleKey): Path =
        convertedDir ?: Path.of(cache.moduleDir(key)).resolve(CONVERTED_DIR)

    /**
     * Gives a freshly converted file its canonical name ([ModuleKey.convertedFileName]) and returns
     * where it ended up. The file is already in the right directory — conversion runs there — so
     * this is a rename in place, and a no-op both when the converter already used that name and
     * when no [convertedDir] is configured (inside a module's own cache entry the file is addressed
     * by its actual name, so renaming it would buy nothing).
     */
    private suspend fun canonicalizeConverted(key: ModuleKey, produced: Path): Path {
        val root = convertedDir ?: return produced
        return withContext(Dispatchers.IO) {
            val target = root.createDirectories().resolve(key.convertedFileName)
            if (target.normalize() == produced.normalize()) return@withContext target
            Files.move(produced, target, StandardCopyOption.REPLACE_EXISTING)
            log.i { "Converted $key kept at $target" }
            target
        }
    }

    /**
     * The files `search_source` scans, each with the [SourceRef]-style path a hit reports: the
     * PL/SQL sidecars under the module's cache entry, and the module's own converted text form
     * (never a sibling module's — the converted directory can be shared by all of them).
     */
    private fun searchableFiles(
        index: ModuleIndex,
        plsql: Boolean,
        xml: Boolean,
    ): List<Pair<String, Path>> {
        val moduleDir = Path.of(cache.moduleDir(index.key)).normalize()
        val files = mutableListOf<Pair<String, Path>>()
        val plsqlDir = moduleDir.resolve(PLSQL_DIR)
        if (plsql && plsqlDir.exists()) {
            Files.walk(plsqlDir).use { stream ->
                files += stream.asSequence()
                    .filter { it.isRegularFile() }
                    .map { moduleDir.relativize(it).joinToString("/") to it }
                    .toList()
            }
        }
        // A .pld is the PL/SQL itself; XML is the raw document.
        val wanted = if (index.convertedFile.endsWith(".pld", ignoreCase = true)) plsql else xml
        if (wanted) {
            val converted = runCatching { resolveRef(index.key, index.convertedFile) }.getOrNull()
            if (converted != null && converted.exists()) files += index.convertedFile to converted
        }
        return files.sortedBy { it.first }
    }

    // --- subclassing (inherited objects) ---

    /**
     * What a served body actually is. An empty [text] means opposite things depending on whether
     * the object is subclassed, and the two are indistinguishable on the wire without this — the
     * correctness bug this vocabulary exists to close.
     */
    private fun bodySourceOf(text: String, inherited: InheritanceRef?): BodySource = when {
        text.isNotBlank() -> BodySource.OWN
        inherited != null -> BodySource.INHERITED
        else -> BodySource.EMPTY
    }

    /** One hop of a subclassing walk: where the parent object points next, and its body ref. */
    private class InheritedStep(val next: InheritanceRef?, val textRef: SourceRef?)

    /** A body found by following a subclassing pointer, and the module it was found in. */
    private class InheritedBody(val module: ModuleKey, val text: String)

    /**
     * The `ownerPath` to look under in the parent module, or `null` for "wherever it is there".
     *
     * An object carried in by an **object group** keeps its own name but not its place: where the
     * group puts it inside an object library is that library's business, not something this
     * module's XML records. Asserting `:FORM` for it would be a guess, so the scope is left open
     * and a name that turns out to be ambiguous over there ends the walk instead.
     */
    private fun suggestedOwnerPath(ref: InheritanceRef): String? =
        ref.ownerPath ?: FORM_LEVEL_OWNER.takeIf { ref.objectGroup == null }

    /**
     * Follows a subclassing pointer to the module that actually defines the body.
     *
     * **Cached modules only.** Fetching a parent would convert it, and every read tool here
     * declares `readOnlyHint = true`; a walk that cannot proceed returns `null` and the caller
     * serves the pointer plus a hint naming the `fetch_module` call instead. The walk is bounded
     * ([MAX_INHERITANCE_HOPS]) and cycle-guarded, since a chain is data from converted files and
     * nothing guarantees it terminates.
     *
     * [step] locates the counterpart object in a parent index; anything it throws (no such
     * trigger, ambiguous scope) ends the walk rather than failing the call.
     */
    private suspend fun followInherited(
        start: InheritanceRef,
        step: (ModuleIndex, InheritanceRef) -> InheritedStep,
    ): InheritedBody? {
        var pointer = start
        val visited = mutableSetOf<ModuleKey>()
        repeat(MAX_INHERITANCE_HOPS) {
            val parentKey = inheritedModuleKey(pointer) ?: return null
            if (!visited.add(parentKey)) return null // a chain that loops back on itself
            val parentIndex = runCatching { index(parentKey) }.getOrNull() ?: return null
            val found = runCatching { step(parentIndex, pointer) }.getOrNull() ?: return null
            val text = found.textRef?.let { readRef(parentKey, it) }.orEmpty()
            if (text.isNotBlank()) return InheritedBody(parentKey, text)
            pointer = found.next ?: return null // empty and not inherited further: nothing to find
        }
        return null
    }

    /**
     * The module a subclassing pointer names, or `null` when it cannot be pinned down.
     *
     * `ParentFilename` is preferred because it carries the module *type* as its extension (and is
     * taken by its file name only — Forms may record a full path from the machine that saved the
     * form). A pointer with only `ParentModule` is a bare name, so it resolves only when exactly
     * one cached module wears it.
     */
    private suspend fun inheritedModuleKey(ref: InheritanceRef): ModuleKey? {
        ref.file
            ?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.let { ModuleKey.parseOrNull(it) }
            ?.let { return it }
        val name = ref.module?.trim()?.uppercase() ?: return null
        return cache.list().singleOrNull { it.name == name }
    }

    /**
     * The message that goes beside an inherited body or block: which module holds the definition
     * and the exact call that reaches it — the same contract the staleness exceptions keep.
     *
     * [resolvable] is `false` for tools that have no `resolve` argument, so the hint never
     * suggests one that does not exist; [resolveAttempted] switches the wording to say why
     * `resolve` came back empty-handed.
     */
    private suspend fun inheritedHint(
        subject: String,
        ref: InheritanceRef,
        parentKey: ModuleKey?,
        nextCall: (ModuleKey) -> String,
        resolveAttempted: Boolean,
        resolvable: Boolean,
    ): String {
        if (parentKey == null) {
            val named = ref.module ?: ref.file
            return "$subject is subclassed, so its definition lives in the module it inherits " +
                "from — which cannot be pinned down from this module's XML" +
                (named?.let { " (it names only '$it')" } ?: "") +
                ". Call list_modules(pattern=\"${named ?: ""}\") to find it, or get_object_xml " +
                "for the raw subclass attributes."
        }
        val retry = if (resolvable) " Or retry this call with resolve=true." else ""
        // An object group is how the definition got here; naming it saves a search of the library.
        val where = "'$parentKey'" + (ref.objectGroup?.let { " (object group '$it')" } ?: "")
        if (cache.get(parentKey) == null) {
            return "$subject is subclassed, so its definition lives in $where, which is not " +
                "cached" +
                (
                    if (resolveAttempted) {
                        " — resolve could not follow the pointer, because reaching an un-cached " +
                            "module means converting it and this read never does"
                    } else {
                        ""
                    }
                    ) +
                ". Call fetch_module(module=\"$parentKey\"), then ${nextCall(parentKey)}.$retry"
        }
        if (!resolveAttempted) {
            return "$subject is subclassed, so its definition lives in $where, which is " +
                "already cached. Call ${nextCall(parentKey)}.$retry"
        }
        return "$subject is subclassed from $where, but no body was found there" +
            (suggestedOwnerPath(ref)?.let { " under '$it'" } ?: "") +
            ". Call ${nextCall(parentKey)} to look directly, or get_object_xml for the raw " +
            "subclass attributes."
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
         * Output ceilings. Claude Code caps MCP tool output at 25,000 tokens (~100 KB) and warns
         * at 10,000, so every list-shaped result is bounded and says so rather than being rejected
         * whole: [DEFAULT_MODULE_PAGE]/[MAX_MODULE_PAGE] page `list_modules`, [MAX_LIST_ROWS] caps
         * the per-module lists, and [MAX_OVERVIEW_NAMES] caps each section of the overview.
         */
        const val MAX_LIST_ROWS = 1_000
        const val MAX_OVERVIEW_NAMES = 500

        /**
         * How far `resolve` walks a subclassing chain. Forms allows a parent to be subclassed in
         * turn, so the chain is data read from converted files — bounded here (on top of the
         * cycle guard) so a malformed one costs a handful of cache reads, not a hang.
         */
        const val MAX_INHERITANCE_HOPS = 5

        /** Marks a `list_modules` cursor as ours, so a token from elsewhere fails cleanly. */
        const val MODULE_CURSOR_PREFIX = "modules:v1:"

        /** Cache subdirectory of a module's converted text form, and the prefix its refs carry. */
        const val CONVERTED_DIR = "converted"
        const val PLSQL_DIR = "plsql"

        /**
         * The ownerPath token that selects the owner-less form-level trigger among same-named
         * ones at other levels. `:` is illegal in Forms object names, so the token can never
         * collide with a block that is literally named FORM.
         */
        const val FORM_LEVEL_OWNER = ":FORM"
    }
}
