package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.core.FormsDirectoryScanner
import app.oreshkov.oracleformsmcp.core.ModuleCache
import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.core.ModuleNotFetchedException
import app.oreshkov.oracleformsmcp.core.ModuleParser
import app.oreshkov.oracleformsmcp.core.ModuleStaleException
import app.oreshkov.oracleformsmcp.dto.BlockDetail
import app.oreshkov.oracleformsmcp.dto.BlockList
import app.oreshkov.oracleformsmcp.dto.BlockSummary
import app.oreshkov.oracleformsmcp.dto.FetchModuleSummary
import app.oreshkov.oracleformsmcp.dto.ModuleList
import app.oreshkov.oracleformsmcp.dto.ModuleOverview
import app.oreshkov.oracleformsmcp.dto.ModuleStatusEntry
import app.oreshkov.oracleformsmcp.dto.ObjectXml
import app.oreshkov.oracleformsmcp.dto.ProgramUnitList
import app.oreshkov.oracleformsmcp.dto.ProgramUnitSource
import app.oreshkov.oracleformsmcp.dto.ProgramUnitSummary
import app.oreshkov.oracleformsmcp.dto.SearchHit
import app.oreshkov.oracleformsmcp.dto.SearchResults
import app.oreshkov.oracleformsmcp.dto.TriggerList
import app.oreshkov.oracleformsmcp.dto.TriggerSource
import app.oreshkov.oracleformsmcp.dto.TriggerSummary
import app.oreshkov.oracleformsmcp.io.Fingerprints
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import app.oreshkov.oracleformsmcp.model.SourceRef
import app.oreshkov.oracleformsmcp.model.TriggerLevel
import co.touchlab.kermit.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
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
        return BlockDetail(module = index.key, block = block)
    }

    suspend fun listTriggers(
        key: ModuleKey,
        block: String?,
        item: String?,
        level: String?,
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
                    firstLine = it.firstLine,
                    lineCount = it.lineCount,
                )
            }
            .toList()
        return TriggerList(module = key, triggers = triggers)
    }

    suspend fun getTrigger(key: ModuleKey, name: String, block: String?, item: String?): TriggerSource {
        val index = index(key)
        val matches = index.triggers.filter {
            it.name.equals(name, ignoreCase = true) &&
                (block == null || it.blockName.equals(block, ignoreCase = true)) &&
                (item == null || it.itemName.equals(item, ignoreCase = true))
        }
        val trigger = when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException(
                "No trigger '$name' in $key" +
                    (block?.let { " for block '$it'" } ?: "") +
                    ". Call list_triggers to see what exists.",
            )
            else -> throw IllegalArgumentException(
                "Trigger '$name' exists at several scopes in $key: " +
                    matches.joinToString(", ") { scopeOf(it.blockName, it.itemName) } +
                    ". Narrow it down with the 'block' (and 'item') arguments.",
            )
        }
        val ref = trigger.textRef
            ?: throw IllegalStateException("Trigger '$name' has no recorded PL/SQL body")
        return TriggerSource(
            module = index.key,
            name = trigger.name,
            level = trigger.level,
            block = trigger.blockName,
            item = trigger.itemName,
            text = readRef(key, ref),
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
        )
    }

    suspend fun searchSource(
        key: ModuleKey,
        query: String,
        regex: Boolean,
        scope: String?,
        maxResults: Int,
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
        val pattern = if (regex) Regex(query) else null
        val hits = mutableListOf<SearchHit>()
        var truncated = false

        val files = withContext(Dispatchers.IO) { searchableFiles(moduleDir, includePlsql, includeXml) }
        outer@ for (file in files) {
            val lines = withContext(Dispatchers.IO) { file.readLines() }
            for ((lineIndex, line) in lines.withIndex()) {
                val matches = pattern?.containsMatchIn(line) ?: line.contains(query)
                if (!matches) continue
                if (hits.size == cap) {
                    truncated = true
                    break@outer
                }
                hits += SearchHit(
                    path = moduleDir.relativize(file).joinToString("/"),
                    line = lineIndex + 1,
                    snippet = line.trim().take(200),
                )
            }
        }
        return SearchResults(query = query, hits = hits, truncated = truncated)
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
        )
    }

    /** Every module with a cached index (feeds the MCP resources). */
    suspend fun listCached(): List<ModuleKey> = cache.list()

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

    private fun scopeOf(block: String?, item: String?): String = when {
        item != null -> "$block.$item"
        block != null -> block!!
        else -> "form level"
    }

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
    }
}
