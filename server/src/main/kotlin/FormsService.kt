package app.oreshkov.oracleformsmcp.server

import app.oreshkov.oracleformsmcp.core.AnnotationStore
import app.oreshkov.oracleformsmcp.core.FormsDirectoryScanner
import app.oreshkov.oracleformsmcp.core.ModuleCache
import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.core.ModuleIndexOutdatedException
import app.oreshkov.oracleformsmcp.core.ModuleNotFetchedException
import app.oreshkov.oracleformsmcp.core.ModuleParser
import app.oreshkov.oracleformsmcp.core.ModuleStaleException
import app.oreshkov.oracleformsmcp.dto.AnnotationCreated
import app.oreshkov.oracleformsmcp.dto.AnnotationRemoved
import app.oreshkov.oracleformsmcp.dto.AnnotationSearchResults
import app.oreshkov.oracleformsmcp.dto.AnnotationView
import app.oreshkov.oracleformsmcp.dto.BlockColumns
import app.oreshkov.oracleformsmcp.dto.BlockDetail
import app.oreshkov.oracleformsmcp.dto.BlockList
import app.oreshkov.oracleformsmcp.dto.BlockSummary
import app.oreshkov.oracleformsmcp.dto.BodySource
import app.oreshkov.oracleformsmcp.dto.ElementAnnotationList
import app.oreshkov.oracleformsmcp.dto.ElementAnnotations
import app.oreshkov.oracleformsmcp.dto.FetchModuleSummary
import app.oreshkov.oracleformsmcp.dto.ModuleAnnotationsView
import app.oreshkov.oracleformsmcp.dto.MasterRelation
import app.oreshkov.oracleformsmcp.dto.ModuleDetail
import app.oreshkov.oracleformsmcp.dto.ModuleList
import app.oreshkov.oracleformsmcp.dto.ModuleOverview
import app.oreshkov.oracleformsmcp.dto.ModuleSearchHit
import app.oreshkov.oracleformsmcp.dto.ModuleSearchResults
import app.oreshkov.oracleformsmcp.dto.ModuleStatusEntry
import app.oreshkov.oracleformsmcp.dto.ObjectXml
import app.oreshkov.oracleformsmcp.dto.ProgramUnitList
import app.oreshkov.oracleformsmcp.dto.ProgramUnitSource
import app.oreshkov.oracleformsmcp.dto.ProgramUnitSummary
import app.oreshkov.oracleformsmcp.dto.PropertyClassResolution
import app.oreshkov.oracleformsmcp.dto.RelationCreated
import app.oreshkov.oracleformsmcp.dto.RelationView
import app.oreshkov.oracleformsmcp.dto.SearchFileCount
import app.oreshkov.oracleformsmcp.dto.SearchHit
import app.oreshkov.oracleformsmcp.dto.SearchResults
import app.oreshkov.oracleformsmcp.dto.SourceLocation
import app.oreshkov.oracleformsmcp.dto.SourceText
import app.oreshkov.oracleformsmcp.dto.StaleReason
import app.oreshkov.oracleformsmcp.dto.TriggerList
import app.oreshkov.oracleformsmcp.dto.TriggerSource
import app.oreshkov.oracleformsmcp.dto.TriggerSummary
import app.oreshkov.oracleformsmcp.dto.UnresolvedItem
import app.oreshkov.oracleformsmcp.dto.UnresolvedReason
import app.oreshkov.oracleformsmcp.io.Fingerprints
import app.oreshkov.oracleformsmcp.model.Annotation
import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.model.Author
import app.oreshkov.oracleformsmcp.model.CURRENT_INDEX_VERSION
import app.oreshkov.oracleformsmcp.model.ElementId
import app.oreshkov.oracleformsmcp.model.ElementKind
import app.oreshkov.oracleformsmcp.model.BlockInfo
import app.oreshkov.oracleformsmcp.model.DataSourceColumnInfo
import app.oreshkov.oracleformsmcp.model.InheritanceRef
import app.oreshkov.oracleformsmcp.model.ItemDml
import app.oreshkov.oracleformsmcp.model.ItemGeometry
import app.oreshkov.oracleformsmcp.model.ItemInfo
import app.oreshkov.oracleformsmcp.model.ModuleFingerprint
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ProgramUnitInfo
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
import app.oreshkov.oracleformsmcp.model.Relation
import app.oreshkov.oracleformsmcp.model.RelationInfo
import app.oreshkov.oracleformsmcp.model.ScannedModule
import app.oreshkov.oracleformsmcp.model.SourceRef
import app.oreshkov.oracleformsmcp.model.TriggerInfo
import app.oreshkov.oracleformsmcp.model.TriggerLevel
import app.oreshkov.oracleformsmcp.parse.DataSourceColumnReader
import app.oreshkov.oracleformsmcp.server.resources.moduleConvertedUri
import app.oreshkov.oracleformsmcp.server.resources.sourceMimeType
import app.oreshkov.oracleformsmcp.server.resources.sourceRefPath
import app.oreshkov.oracleformsmcp.server.resources.sourceUri
import co.touchlab.kermit.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.useLines
import kotlin.streams.asSequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
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
 * `search_modules` bounds, shared with `SearchModulesTool` so its description promises exactly what
 * is enforced. Two of them, because a cross-module search has two ways to run away: more hits than
 * a response can carry ([DEFAULT_SEARCH_HITS]/[MAX_SEARCH_HITS]) and more modules than one call
 * should read ([MAX_MODULES_PER_SEARCH] — a query matching nothing would otherwise read every
 * converted file in the cache before answering). Either bound sets `truncated` and mints a cursor.
 *
 * [MAX_SEARCH_HITS] is half of `search_source`'s ceiling: a cross-module hit carries its module and
 * a longer path as well as the snippet, and the *worst-case* page — every snippet at its 200-char
 * cap — still has to fit a client's tool-output budget, not just the typical one.
 */
internal const val DEFAULT_SEARCH_HITS: Int = 50
internal const val MAX_SEARCH_HITS: Int = 100
internal const val MAX_MODULES_PER_SEARCH: Int = 200

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
            oracleHomeConversion = ModuleType.entries.any(converter::convertsBinary),
            total = selected.size,
            returned = page.size,
            truncated = truncated,
            nextCursor = if (truncated) encodeModuleCursor(page.last().module) else null,
            countsByStatus = countsByStatus,
            // Asked of the converter, per type, and only about types actually on this page: what a
            // site's command accepts is not derivable from the configuration here.
            hint = page.asSequence()
                .filter { it.status == ModuleStatus.NOT_CACHED }
                .map { it.type }
                .distinct()
                .mapNotNull(converter::conversionCaveat)
                .joinToString(" ")
                .ifEmpty { null },
            modules = page,
        )
    }

    /**
     * Converts (or copies) and indexes [key]. Fingerprint-idempotent: a warm entry whose source
     * is unchanged *and* whose index this build wrote returns immediately with `fromCache = true`.
     * [onProgress] fires at each phase boundary (never on a warm hit).
     *
     * An entry whose source is unchanged but whose [ModuleIndex.indexVersion] is not
     * [CURRENT_INDEX_VERSION] is re-parsed from the converted file already in the cache entry —
     * see [reindexInPlace]. That is the upgrade path: nothing about a new server build changes an
     * `.fmb`, so without it a warm module keeps answering with the facts the previous build knew.
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
        val all = scanner.scan()
        val scanned = all.find { it.key == key }
            ?: throw IllegalArgumentException(
                "Module '$key' was not found in $formsDir. Call list_modules to see what exists.",
            )
        val source = fingerprintSource(scanned)
        cache.get(key)?.let { cached ->
            if (cached.sourceFile == source.toString() &&
                Fingerprints.matches(cached.fingerprint, source)
            ) {
                if (cached.indexVersion == CURRENT_INDEX_VERSION) {
                    return cached.summary(fromCache = true).withLibraryHint(all)
                }
                // The file is unchanged and only the parser moved on: re-parse, do not re-convert.
                // Falls through to a full conversion when the converted file is gone.
                reindexInPlace(key, cached, source, onProgress)?.let { return it.withLibraryHint(all) }
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
        val index = parsed.stamped(source)
        cache.putIndex(index)
        return index.summary(fromCache = false).withLibraryHint(all)
    }

    /**
     * Rebuilds [cached] from the converted file it already names, without running the converter.
     *
     * The expensive half of a fetch is the conversion, and here it is provably unnecessary: the
     * source fingerprint still matches, so the converted text form in the cache entry is the one
     * this very file produces. Only the *parse* is out of date, and re-running it also rewrites the
     * PL/SQL sidecars — which is the point, since a parser fix (decoded bodies, real line counts)
     * lands in them, not only in the index JSON.
     *
     * Returns `null` when the converted file is missing — a cache entry someone pruned by hand, or
     * a relocated `--converted-dir` — leaving the caller to do the full conversion.
     */
    private suspend fun reindexInPlace(
        key: ModuleKey,
        cached: ModuleIndex,
        source: Path,
        onProgress: suspend (FetchProgress) -> Unit,
    ): FetchModuleSummary? {
        val converted = runCatching { resolveRef(key, cached.convertedFile) }.getOrNull()
        if (converted == null || !converted.exists()) return null
        log.i {
            "Re-indexing $key from $converted without converting: cached index " +
                "v${cached.indexVersion}, current v$CURRENT_INDEX_VERSION"
        }
        onProgress(FetchProgress(1, REINDEX_STEPS, "Re-parsing $key (converted file reused)"))
        val moduleDir = cache.moduleDir(key)
        val parsed = withContext(Dispatchers.Default) { parser.parse(key, converted.toString(), moduleDir) }
        onProgress(FetchProgress(2, REINDEX_STEPS, "Caching the index of $key"))
        val index = parsed.stamped(source)
        cache.putIndex(index)
        return index.summary(fromCache = false)
    }

    /**
     * The cache contract stamped onto a freshly parsed index: the file staleness is judged against,
     * and the parser version that wrote it. Both live here rather than in the parser so that every
     * entry this service caches carries them, whichever [ModuleParser] produced it.
     */
    private fun ModuleIndex.stamped(source: Path): ModuleIndex = copy(
        sourceFile = source.toString(),
        fingerprint = Fingerprints.of(source),
        indexVersion = CURRENT_INDEX_VERSION,
    )

    /**
     * The module's sections by name, plus — at [detailed] — the window and canvas objects behind
     * two of them. Modality and the window a canvas sits on are the properties these sections are
     * actually consulted for, and they used to cost one `get_object_xml` per object.
     */
    suspend fun overview(key: ModuleKey, detailed: Boolean = false): ModuleOverview {
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
            detail = if (detailed) {
                ModuleDetail(windows = index.windows, canvases = index.canvases)
            } else {
                null
            },
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

    /**
     * One block. A block of a real form runs to dozens of items, so [detailed] governs how much of
     * each row comes back, and [items] narrows the rows to the ones a question is about.
     *
     * The block's master-detail relations are served at every verbosity — the ones written on it,
     * and those on other blocks that name it as their detail — because they are structure: they
     * decide what the block can be queried through.
     *
     * What `concise` drops is descriptive — data type, column, canvas, size, and the properties Forms
     * only writes when they are overridden. What it keeps is everything a reader would otherwise
     * have to *infer*: the item's name and type, its property class (which is where a shop's item
     * semantics live), its prompt, its trigger names, and its subclassing pointer. Dropping that
     * last one to save bytes would re-create the absence bug `bodySource` exists to prevent.
     *
     * [detailed] also resolves each item's DML properties through its property class
     * ([BlockDetail.effectiveDml]) and names every item it could not ([BlockDetail.unresolvedItems]);
     * [columns] reads the block's data-source columns out of its XML and sets them against *all* of
     * its items, whatever [items] selected, since a column is supplied by any item of the block.
     *
     * Resolution follows a class into another module only when that module is already cached and
     * current — the same read-only rule as `resolve` on bodies — and says which module to fetch
     * when it could not.
     */
    suspend fun getBlock(
        key: ModuleKey,
        blockName: String,
        detailed: Boolean = false,
        columns: Boolean = false,
        items: List<String>? = null,
    ): BlockDetail {
        val index = index(key)
        val full = index.blocks.firstOrNull { it.name.equals(blockName, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "No block '$blockName' in $key. Blocks: ${index.blocks.joinToString(", ") { it.name }}",
            )
        val selected = selectItems(index.key, full, items)
        val filtered = selected !== full.items
        val rows = if (detailed) selected else selected.map(::conciseItem)
        // One budget across the lists this result can carry, spent in the order they answer the
        // question: the relations the block is reached through (a few rows, and what decides what it
        // can see), the items, what is unknown about them, their resolved properties, then the base
        // table behind them.
        val budget = RowBudget(MAX_RESULT_CHARS - RESULT_OVERHEAD_CHARS)
        val (relations, relationsCut) = budget.take(
            full.relations,
            RelationInfo.serializer(),
            share = budget.share(RELATION_BUDGET_SHARE),
        )
        val masters = detailRelationsOf(index, full)
        val (detailOf, detailOfCut) = budget.take(
            masters,
            MasterRelation.serializer(),
            share = budget.share(RELATION_BUDGET_SHARE),
        )
        val (served, itemsCut) = budget.take(
            rows,
            ItemInfo.serializer(),
            share = if (columns) budget.share(ITEM_BUDGET_WITH_COLUMNS) else budget.share(ITEM_BUDGET_SHARE),
        )
        val block = full.copy(items = served, relations = relations)
        val effective = if (detailed) effectiveItemDml(index, block, budget) else null
        val blockColumns = if (columns) blockColumns(index.key, full, budget) else null
        val again: (List<String>?) -> String = { names ->
            "get_block(module=\"$key\", block=\"${full.name}\", verbosity=\"detailed\"" +
                (if (columns) ", columns=true" else "") +
                (names?.let { list -> ", items=[${list.joinToString(", ") { "\"$it\"" }}]" } ?: "") + ")"
        }
        val hints = buildList {
            if (itemsCut) {
                val of = if (filtered) "the ${selected.size} matched" else "${full.items.size}"
                add(
                    "Returned ${served.size} of $of items: the rest would not fit one response. Ask " +
                        "again with verbosity=\"concise\" (smaller rows, every item) or with items=[...] " +
                        "naming the ones the question is about, or answer a single-property question " +
                        "over all of them with search_source(module=\"$key\", scope=\"xml\", query=...).",
                )
            }
            block.inherited?.let { ref ->
                add(
                    inheritedHint(
                        subject = "Block '${block.name}'",
                        ref = ref,
                        parentKey = inheritedModuleKey(ref),
                        nextCall = { "get_block(module=\"$it\", block=\"${ref.name ?: block.name}\")" },
                        resolveAttempted = false,
                        resolvable = false,
                    ),
                )
            }
            effective?.let { dml ->
                addAll(effectiveDmlHints(dml, again, requested = selected.map { it.name }.takeIf { filtered }))
            }
            addAll(
                relationHints(
                    key = index.key,
                    block = full,
                    served = relations,
                    detailOfServed = detailOf,
                    detailOfAll = masters,
                ),
            )
            if (blockColumns != null && blockColumns.total == 0 && block.inherited != null) {
                add("This module records no data-source columns for the subclassed block; they are defined with it.")
            }
            // The column rows give way first, so on a wide base table this is the cut a caller is
            // likeliest to meet — and the one place the result can say that the answer above it
            // (which columns no item supplies) was still computed over every column.
            if (blockColumns != null && blockColumns.truncated) {
                add(
                    "'columns' lists ${blockColumns.columns.size} of ${blockColumns.total} " +
                        "data-source columns; the rest did not fit. 'columnsWithoutItem' and " +
                        "'mandatoryColumnsWithoutItem' are computed over all ${blockColumns.total}, " +
                        "so what they name is complete. For the remaining column rows themselves, ask " +
                        "again naming fewer items — ${again(listOf("<one item>"))} — which leaves more " +
                        "of the response for them.",
                )
            }
            if (blockColumns != null && blockColumns.namesTruncated) {
                add(
                    "The column-name lists are themselves cut: 'columnsWithoutItem' names " +
                        "${blockColumns.columnsWithoutItem.size} of " +
                        "${blockColumns.columnsWithoutItemTotal} and 'mandatoryColumnsWithoutItem' " +
                        "${blockColumns.mandatoryColumnsWithoutItem.size} of " +
                        "${blockColumns.mandatoryColumnsWithoutItemTotal}, so neither is the whole " +
                        "set — read the totals, not the lengths.",
                )
            }
        }
        return BlockDetail(
            module = index.key,
            block = block,
            source = locationOf(index.key, block.sourceRef),
            hint = hints.joinToString(" ").ifEmpty { null },
            itemTotal = full.items.size,
            itemsMatched = selected.size.takeIf { filtered },
            truncated = itemsCut || relationsCut || detailOfCut ||
                effective?.cut == true || effective?.unresolvedCut == true ||
                effective?.geometryCut == true || blockColumns?.truncated == true ||
                blockColumns?.namesTruncated == true,
            effectiveDml = effective?.items.orEmpty(),
            effectiveGeometry = effective?.geometry.orEmpty(),
            unresolvedItems = effective?.unresolved.orEmpty(),
            propertyClasses = effective?.classes.orEmpty(),
            columns = blockColumns,
            detailOf = detailOf,
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
                    // firstLine is the parsed body's first non-blank line, so an empty one means
                    // the body holds no code — the same test getTrigger makes against the text.
                    // Such a body counts zero lines: the stored count never goes below one, and
                    // "1 line" beside an inherited body reads as a one-line trigger.
                    lineCount = if (it.firstLine.isBlank()) 0 else it.lineCount,
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
            textEncoding = trigger.textEncoding,
            // A resolved body came out of the parent module's file, so that is what it points at.
            source = followed?.let { locationOf(it.module, it.ref) } ?: locationOf(index.key, ref),
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
            textEncoding = unit.textEncoding,
            // A resolved body came out of the parent module's file, so that is what it points at.
            source = followed?.let { locationOf(it.module, it.ref) } ?: locationOf(index.key, ref),
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

    /**
     * Searches one module's cached files line by line.
     *
     * [ignoreCase] defaults to `true`, as it does in `search_modules`: PL/SQL is case-insensitive
     * and Forms writes its own names in upper case, so a case-sensitive default made the two
     * search tools disagree about the same query — and the workaround it taught was to drop the
     * first letter of a word and search for the remainder.
     *
     * The scan does not stop when the page is full: it goes on counting, so [SearchResults.total]
     * and the per-file counts describe the whole result on every page. That costs nothing in the
     * worst case — a query matching nothing already reads every file — and it is what lets a
     * caller tell "these are all the hits" from "this is the first page of them" without paging.
     */
    suspend fun searchSource(
        key: ModuleKey,
        query: String,
        regex: Boolean,
        scope: String?,
        maxResults: Int,
        offset: Int = 0,
        ignoreCase: Boolean = true,
    ): SearchResults {
        val index = index(key) // staleness/fetched check before touching files
        val searchScope = searchScopeOf(scope)
        val cap = maxResults.coerceIn(1, MAX_SEARCH_RESULTS)
        val start = offset.coerceAtLeast(0)
        val matches = lineMatcher(query, regex, ignoreCase = ignoreCase, tool = "search_source")
        val pageHits = mutableListOf<SearchHit>()
        val perFile = mutableListOf<SearchFileCount>()
        var seen = 0 // total matches across all files, for stable offset paging and the total

        val files = withContext(Dispatchers.IO) { searchableFiles(index, searchScope) }
        for ((refPath, file) in files) {
            val uri = sourceUri(key, refPath)
            var inFile = 0
            withContext(Dispatchers.IO) {
                file.useLines { lines ->
                    for ((lineIndex, line) in lines.withIndex()) {
                        if (!matches(line)) continue
                        inFile++
                        if (seen++ < start || pageHits.size == cap) continue // another page's hit
                        pageHits += SearchHit(
                            path = refPath,
                            line = lineIndex + 1,
                            snippet = line.trim().take(SNIPPET_CHARS),
                            uri = uri,
                        )
                    }
                }
            }
            if (inFile > 0) perFile += SearchFileCount(path = refPath, hits = inFile, uri = uri)
        }

        // The counts are the summary and the hits the page; both must fit one response. The counts
        // are spent first, under a share, because they are what a page cannot show — a module-wide
        // identifier in a few hundred sidecars must not starve the hits it summarises.
        val budget = RowBudget(MAX_RESULT_CHARS - RESULT_OVERHEAD_CHARS)
        val (cappedFiles, overLimit) = capRows(perFile)
        val (fileRows, filesCut) = budget.take(
            cappedFiles,
            SearchFileCount.serializer(),
            share = budget.share(SEARCH_FILE_BUDGET_SHARE),
        )
        val (hits, _) = budget.take(pageHits, SearchHit.serializer())
        val end = start + hits.size
        val truncated = end < seen
        return SearchResults(
            query = query,
            hits = hits,
            truncated = truncated,
            offset = start,
            nextOffset = if (truncated) end else null,
            total = seen,
            hint = searchSourceHint(key, query, regex, ignoreCase, searchScope, cap, start, end, seen, perFile.size),
            fileTotal = perFile.size,
            filesTruncated = filesCut || overLimit,
            files = fileRows,
        )
    }

    /**
     * What a `search_source` page does not say on its own: that hits remain past it, with the exact
     * call that continues, or that the offset asked for is past the last hit. `null` when the page
     * holds every hit there is.
     */
    private fun searchSourceHint(
        key: ModuleKey,
        query: String,
        regex: Boolean,
        ignoreCase: Boolean,
        scope: SearchScope,
        maxResults: Int,
        start: Int,
        end: Int,
        total: Int,
        fileTotal: Int,
    ): String? {
        val next = buildString {
            append("search_source(module=\"$key\", query=")
            append(resultJson.encodeToString(String.serializer(), query))
            if (regex) append(", regex=true")
            if (!ignoreCase) append(", ignoreCase=false")
            append(", scope=\"${scope.label}\", maxResults=$maxResults, offset=")
        }
        return when {
            end < total -> "Hits ${start + 1}-$end of $total across $fileTotal file(s); call $next$end) " +
                "for the rest — 'files' counts them all, but a claim that something is absent " +
                "needs every page."
            start > 0 && start >= total && total > 0 -> "'offset' $start is past the last of $total " +
                "hit(s); call ${next}0) to start over."
            else -> null
        }
    }

    /**
     * Searches every **cached** module at once — the questions a single module cannot answer:
     * which forms call a given form, where a `:GLOBAL` variable is written, which modules subclass
     * a shared block (that last one lives in the converted XML's `ParentFilename` attributes, so
     * `scope = "xml"` finds it).
     *
     * Deliberately a separate tool rather than `search_source(module = "*")`: `scope = "all"`
     * already means "PL/SQL *and* XML within one module", and making "all" also mean "across
     * modules" is the overlapping-purpose confusion that makes a model pick the wrong call.
     *
     * **Cached modules only.** Reaching an un-fetched one would mean converting it, which a
     * read-only tool must not do, so the un-searchable ones are counted
     * ([ModuleSearchResults.skippedNotCached]) and named in the hint instead of being quietly
     * absent — a cross-module search that covered a tenth of the directory otherwise reads exactly
     * like one that found nothing. A module whose source changed since it was indexed is skipped
     * the same way rather than searched against text that no longer matches its `.fmb`.
     *
     * Two bounds, both reported: [maxResults] hits, and [MAX_MODULES_PER_SEARCH] modules read per
     * call. The second exists because a query that matches nothing would otherwise read every
     * converted file in the cache — megabytes per form — before answering. Whichever bound stops
     * the scan, the result is [ModuleSearchResults.truncated] with a cursor that resumes at the
     * exact position, so the walk still covers everything one page at a time.
     *
     * @param regex treats [query] as a regular expression; [ignoreCase] applies either way and
     *   defaults to `true`, because Forms code names the same module `ORDERS`, `orders` and
     *   `Call_Form('orders')` in the same code base
     * @param modulePattern case-insensitive substring of the module name, narrowing which cached
     *   modules are read at all
     * @param cursor an opaque [ModuleSearchResults.nextCursor]; it carries the position *and* a
     *   fingerprint of the arguments it was minted for, so continuing a different search fails
     *   with a message saying so instead of returning a misaligned page
     */
    suspend fun searchModules(
        query: String,
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        scope: String? = null,
        modulePattern: String? = null,
        maxResults: Int = DEFAULT_SEARCH_HITS,
        cursor: String? = null,
    ): ModuleSearchResults {
        val searchScope = searchScopeOf(scope)
        val matches = lineMatcher(query, regex, ignoreCase, tool = "search_modules")
        val cap = maxResults.coerceIn(1, MAX_SEARCH_HITS)
        val namePattern = modulePattern?.trim()?.takeIf { it.isNotEmpty() }
        fun wanted(key: ModuleKey) = namePattern == null || key.name.contains(namePattern, ignoreCase = true)

        // Sorted by canonical key: the scan order *is* the cursor's coordinate system.
        val cached = cache.list().filter { wanted(it) }.sortedBy { it.toString() }
        val cachedSet = cached.toSet()
        val scannedKeys = scanner.scan().mapTo(HashSet()) { it.key }
        val notCached = scannedKeys.count { wanted(it) && it !in cachedSet }

        val fingerprint = searchFingerprint(query, regex, ignoreCase, searchScope, namePattern)
        val resume = cursor?.let { decodeModuleSearchCursor(it, fingerprint) }
        // Resume strictly after the cursor's module, or at it with that many of its hits already
        // served. A module evicted since the cursor was minted leaves the position at the next one,
        // skipping nothing: a cursor that no longer lines up degrades, it never misaligns.
        var position = 0
        var resumeSkip = 0
        if (resume != null) {
            val landing = cached.indexOfFirst { it.toString() >= resume.module }
            position = if (landing < 0) cached.size else landing
            if (position < cached.size && cached[position].toString() == resume.module) {
                if (resume.hitsServed > 0) resumeSkip = resume.hitsServed else position += 1
            }
        }

        val hits = mutableListOf<ModuleSearchHit>()
        val firstPosition = position
        var visited = 0 // modules whose index was read this call — the per-call budget
        var scanned = 0 // ...of which these were actually searched
        var staleSkipped = 0
        var vanished = 0
        val attached = sortedSetOf<String>()
        var next: ModuleSearchPosition? = null

        scan@ while (position < cached.size && hits.size < cap && visited < MAX_MODULES_PER_SEARCH) {
            val chunk = cached.subList(
                position,
                minOf(position + SEARCH_MODULE_CHUNK, position + (MAX_MODULES_PER_SEARCH - visited), cached.size),
            )
            // Reading a chunk in parallel, assembling it in order: the scan is IO-bound over
            // whole converted forms, while the cursor needs one deterministic sequence.
            val outcomes = coroutineScope {
                chunk.mapIndexed { n, key ->
                    val skip = if (position + n == firstPosition) resumeSkip else 0
                    async { scanCachedModule(key, skip, cap, matches, searchScope) }
                }.awaitAll()
            }
            for ((n, outcome) in outcomes.withIndex()) {
                val key = chunk[n]
                visited++
                if (outcome == null) { vanished++; continue } // evicted between list() and this read
                if (outcome.stale) { staleSkipped++; continue }
                scanned++
                outcome.attachedLibraries.mapTo(attached) { it.trim().uppercase() }
                val skipUsed = if (position + n == firstPosition) resumeSkip else 0
                val taken = minOf(cap - hits.size, outcome.hits.size)
                hits += outcome.hits.take(taken)
                if (hits.size < cap) continue
                next = when {
                    taken < outcome.hits.size || outcome.more -> ModuleSearchPosition(key.toString(), skipUsed + taken)
                    position + n + 1 < cached.size -> ModuleSearchPosition(key.toString(), 0)
                    else -> null
                }
                position += n + 1
                break@scan
            }
            position += chunk.size
        }
        // Stopped on the module budget rather than the result cap: resume after the last one read.
        if (next == null && position in 1..<cached.size) next = ModuleSearchPosition(cached[position - 1].toString(), 0)

        return ModuleSearchResults(
            query = query,
            cachedModules = cached.size,
            scannedModules = scanned,
            skippedNotCached = notCached + vanished,
            skippedStale = staleSkipped,
            truncated = next != null,
            nextCursor = next?.let { encodeModuleSearchCursor(fingerprint, it) },
            hint = moduleSearchHint(
                notCached = notCached + vanished,
                stale = staleSkipped,
                truncated = next != null,
                namePattern = namePattern,
                // Libraries the searched modules attach that are in the forms directory but not
                // fetched: when a called procedure is not found, these are where it most likely is.
                unfetchedLibraries = unfetchedLibraries(attached, scannedKeys, cachedSet),
            ),
            hits = hits,
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
        val kept = jsonEscapedPrefixLength(xml, MAX_OBJECT_XML_CHARS)
        val capped = kept < xml.length
        val served = xml.take(kept)
        val location = locationOf(index.key, ref.ref)
        return ObjectXml(
            module = index.key,
            objectType = ref.objectType,
            name = ref.name,
            ownerPath = ref.ownerPath,
            xml = served,
            startLine = ref.ref.startLine,
            truncated = capped,
            source = location,
            // The fragment itself only shows SubclassSubObject="true"; the parent pointer lives on
            // the enclosing element, so it is served here rather than left one call away.
            inherited = ref.inherited,
            hint = if (capped) objectXmlHint(index.key, ref.ref, served, location) else null,
            annotations = elementAnnotations(
                index,
                ElementId(index.key, ElementKind.OBJECT, ref.name, ref.ownerPath),
            ),
        )
    }

    /**
     * Where a cut `get_object_xml` fragment continues. The cut can fall inside a line, so the
     * continuation starts *at* that line rather than after it — re-reading part of a line is cheap,
     * skipping the rest of one is a silent hole. A cut inside the fragment's first line has no
     * useful continuation (the same line would be cut again), so it points at a search instead.
     */
    private fun objectXmlHint(key: ModuleKey, ref: SourceRef, served: String, location: SourceLocation?): String {
        val cutLine = ref.startLine + served.count { it == '\n' }
        if (cutLine == ref.startLine || location == null || location.uri.isEmpty()) {
            return "The fragment was cut at the response size cap inside its first line. Find the " +
                "attributes you need with search_source(module=\"$key\", scope=\"xml\", query=...)."
        }
        return "The fragment was cut at the response size cap, inside line $cutLine. Continue with " +
            "read_source(module=\"$key\", uri=\"${location.uri}\", startLine=$cutLine, " +
            "endLine=${ref.endLine}), a few dozen lines at a time."
    }

    /**
     * Reads one capped slice of a cached file — the operation that makes every `SourceLocation`
     * this server hands out actually openable, instead of a line range against a path only the
     * server can resolve.
     *
     * [target] is either a source URI (`oracleforms://ORDERS.fmb/converted`) or the cache-relative
     * path a ref carries (`plsql/triggers/ORDERS.KEY-COMMIT.sql`); both are accepted because both
     * appear in the results a caller is reading from. Whichever it is, it is resolved through the
     * same containment check as every other read, so a path cannot escape the module's cache.
     *
     * The response is bounded twice — [maxLines] rows and a character ceiling — because neither
     * bound alone is enough: a converted form runs to hundreds of thousands of lines, and a single
     * line of a doubly-escaped PL/SQL body can be the whole procedure.
     */
    suspend fun readSource(
        key: ModuleKey,
        target: String,
        startLine: Int? = null,
        endLine: Int? = null,
        maxLines: Int? = null,
    ): SourceText {
        val index = index(key) // staleness/fetched check before touching files
        val refPath = refPathOf(key, index, target)
        val file = resolveRef(key, refPath)
        if (!file.exists()) {
            throw IllegalStateException(
                "Cached file $refPath of $key is missing. Call fetch_module to re-index it.",
            )
        }
        val lines = withContext(Dispatchers.IO) { file.readLines() }
        val from = (startLine ?: 1).coerceAtLeast(1)
        require(lines.isEmpty() || from <= lines.size) {
            "startLine $from is past the end of $refPath, which has ${lines.size} lines."
        }
        val requestedTo = (endLine ?: lines.size).coerceAtMost(lines.size)
        val slice = slice(lines, from, requestedTo, maxLines)
        val uri = sourceUri(key, refPath).orEmpty()
        val nextStartLine = (slice.lastLine + 1).takeIf { slice.truncated && it <= requestedTo }
        return SourceText(
            module = key,
            source = SourceLocation(uri = uri, file = refPath, startLine = from, endLine = slice.lastLine),
            totalLines = lines.size,
            truncated = slice.truncated,
            requestedEndLine = requestedTo,
            nextStartLine = nextStartLine,
            lineCut = slice.lineCut,
            hint = if (!slice.truncated) {
                null
            } else {
                // Continue in the form the caller used, so the next call is this one with new lines.
                val byUri = uri.isNotEmpty() && target.contains("://")
                readSourceHint(
                    key = key,
                    target = if (byUri) uri else refPath,
                    byUri = byUri,
                    from = from,
                    slice = slice,
                    requestedTo = requestedTo.takeIf { endLine != null },
                    nextStartLine = nextStartLine,
                )
            },
            text = slice.text,
        )
    }

    /**
     * What a cut `read_source` page says, in the order a caller acts on it: what came back against
     * what was asked, why it stopped, and the exact call that continues. A line cut part-way gets
     * its own sentence, because continuing at the next line silently drops the rest of it.
     */
    private fun readSourceHint(
        key: ModuleKey,
        target: String,
        byUri: Boolean,
        from: Int,
        slice: SourceSlice,
        requestedTo: Int?,
        nextStartLine: Int?,
    ): String = buildString {
        if (slice.lineCut) {
            append(
                "Line ${slice.lastLine} alone is longer than one response, so only its start was " +
                    "returned. A line of converted XML is one whole object: read it with " +
                    "get_object_xml, or find the attribute you need with search_source(scope=\"xml\").",
            )
        } else {
            append("Returned lines $from-${slice.lastLine}")
            if (requestedTo != null) append(" of the requested $from-$requestedTo")
            append(if (slice.stoppedAtLineCap) " (the line cap was reached)." else " (the size cap was reached).")
        }
        if (nextStartLine != null) {
            val arg = if (byUri) "uri" else "file"
            val end = if (requestedTo != null) ", endLine=$requestedTo" else ""
            append(" Continue with read_source(module=\"$key\", $arg=\"$target\", startLine=$nextStartLine$end).")
            if (!slice.stoppedAtLineCap && !slice.lineCut) {
                append(" Lines of converted XML can run to thousands of characters, so ask for fewer at a time.")
            }
        }
    }

    /**
     * A whole cached file as resource content: [readSource] over everything, with the truncation
     * stated *in* the text. A resource read returns bytes and nothing else, so a silently cut file
     * would be indistinguishable from a short one — the marker names the call that continues it.
     */
    suspend fun readSourceResource(key: ModuleKey, target: String): String {
        val slice = readSource(key, target, maxLines = MAX_SOURCE_LINES)
        if (!slice.truncated) return slice.text
        val next = slice.nextStartLine ?: (slice.source.endLine + 1)
        val note = "truncated at line ${slice.source.endLine} of ${slice.totalLines}; " +
            "call read_source(module=\"$key\", uri=\"${slice.source.uri}\", startLine=$next) for the rest"
        return slice.text + if (sourceMimeType(slice.source.file) == "application/xml") {
            "\n<!-- $note -->"
        } else {
            "\n-- $note"
        }
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
     * written by another build of the parser → [ModuleIndexOutdatedException];
     * source deleted → still served (list_modules reports it as SOURCE_MISSING).
     *
     * The two staleness checks are independent and the source one runs first, because it is the
     * one that needs a conversion to heal. An entry can fail only the second: the file on disk is
     * untouched, and the answers in the entry are still the previous build's.
     */
    suspend fun index(key: ModuleKey): ModuleIndex {
        val cached = cache.get(key) ?: throw ModuleNotFetchedException(key)
        val source = Path.of(cached.sourceFile)
        if (source.exists() && !Fingerprints.matches(cached.fingerprint, source)) {
            throw ModuleStaleException(key)
        }
        if (cached.indexVersion != CURRENT_INDEX_VERSION) {
            throw ModuleIndexOutdatedException(key, cached.indexVersion, CURRENT_INDEX_VERSION)
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
        // Both ways an entry goes stale report STALE — the action is `fetch_module` either way —
        // and the reason says which, because only one of them re-runs the converter.
        val (status, staleReason) = when {
            cached == null -> ModuleStatus.NOT_CACHED to null
            !Fingerprints.matches(cached.fingerprint, Path.of(cached.sourceFile)) ->
                ModuleStatus.STALE to StaleReason.SOURCE_CHANGED
            cached.indexVersion != CURRENT_INDEX_VERSION ->
                ModuleStatus.STALE to StaleReason.INDEX_OUTDATED
            else -> ModuleStatus.CACHED to null
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
            staleReason = staleReason,
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

    /**
     * The binary when the converter this module's type reaches can convert one, else the
     * pre-converted text form. Asked of the converter per type, never derived from configuration
     * here: `--compile-command` alone converts `.pll` while forms stay in copy-mode, and a copy-mode
     * form fingerprinted against its binary would never go stale when its XML is re-exported.
     */
    private fun conversionSource(module: ScannedModule): String =
        if (converter.convertsBinary(module.key.type)) {
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

    // --- search: scope, matching, per-module scan, cursors (search_source and search_modules) ---

    /** Which of a module's cached files a search reads. The wire vocabulary is one set for both tools. */
    private data class SearchScope(val plsql: Boolean, val xml: Boolean) {
        /** The `scope` value this selection came from, for the search cursor's fingerprint. */
        val label: String get() = if (plsql && xml) SCOPE_ALL else if (xml) SCOPE_XML else SCOPE_PLSQL
    }

    private fun searchScopeOf(scope: String?): SearchScope = when (scope?.lowercase() ?: SCOPE_PLSQL) {
        SCOPE_PLSQL -> SearchScope(plsql = true, xml = false)
        SCOPE_XML -> SearchScope(plsql = false, xml = true)
        SCOPE_ALL -> SearchScope(plsql = true, xml = true)
        else -> throw IllegalArgumentException("scope must be one of: $SCOPE_PLSQL, $SCOPE_XML, $SCOPE_ALL")
    }

    /**
     * The line predicate both search tools use. A bad regex fails as an argument error naming the
     * way out, the way every other argument error here does — a raw pattern-syntax message tells a
     * model nothing it can act on.
     */
    private fun lineMatcher(
        query: String,
        regex: Boolean,
        ignoreCase: Boolean,
        tool: String,
    ): (String) -> Boolean {
        require(query.isNotEmpty()) { "'query' must not be empty." }
        if (!regex) return { line -> line.contains(query, ignoreCase = ignoreCase) }
        val compiled = try {
            if (ignoreCase) Regex(query, RegexOption.IGNORE_CASE) else Regex(query)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid regex 'query' for $tool: ${e.message}. " +
                    "Drop the 'regex' flag to match the query as a plain substring.",
                e,
            )
        }
        return { line -> compiled.containsMatchIn(line) }
    }

    /** One cached module's contribution to a `search_modules` page. */
    private class ModuleScan(
        val hits: List<ModuleSearchHit>,
        /** At least one further match exists in this module beyond [hits]. */
        val more: Boolean = false,
        /** The module's source changed since it was indexed, so nothing here was searched. */
        val stale: Boolean = false,
        /** The PL/SQL libraries the module attaches, by name — where its called code usually lives. */
        val attachedLibraries: List<String> = emptyList(),
    )

    /**
     * Scans one cached module, skipping its first [skip] matches and collecting at most [want].
     *
     * Reads the cache entry directly instead of going through [index]: a stale or evicted module is
     * a *reported* outcome of a cross-module search, not a failure of it — one module changing on
     * disk must not turn a search over a thousand others into an error. `null` means the entry is
     * gone. Lines are streamed rather than read whole: one converted form runs to hundreds of
     * thousands of them, and this is called for every module in the scan window.
     */
    private suspend fun scanCachedModule(
        key: ModuleKey,
        skip: Int,
        want: Int,
        matches: (String) -> Boolean,
        scope: SearchScope,
    ): ModuleScan? {
        val cached = cache.get(key) ?: return null
        val source = Path.of(cached.sourceFile)
        if (source.exists() && !Fingerprints.matches(cached.fingerprint, source)) {
            return ModuleScan(emptyList(), stale = true)
        }
        // An index another build wrote counts as stale here too, and for a reason particular to
        // this tool: what it searches are the PL/SQL sidecars, which are that build's output. A
        // module indexed before bodies were decoded holds each of them on one line, so it would be
        // searched and would honestly report one useless hit — worse than being counted as skipped.
        if (cached.indexVersion != CURRENT_INDEX_VERSION) {
            return ModuleScan(emptyList(), stale = true)
        }
        return withContext(Dispatchers.IO) {
            val hits = mutableListOf<ModuleSearchHit>()
            var seen = 0
            var more = false
            for ((refPath, file) in searchableFiles(cached, scope)) {
                val uri = sourceUri(key, refPath)
                val stopped = try {
                    file.useLines { lines ->
                        for ((lineIndex, line) in lines.withIndex()) {
                            if (!matches(line)) continue
                            if (seen++ < skip) continue
                            if (hits.size == want) return@useLines true
                            hits += ModuleSearchHit(
                                module = key,
                                moduleSpec = key.toString(),
                                path = refPath,
                                line = lineIndex + 1,
                                snippet = line.trim().take(SNIPPET_CHARS),
                                uri = uri,
                            )
                        }
                        false
                    }
                } catch (e: IOException) {
                    // One unreadable cached file must not fail a search over every other module.
                    log.w(e) { "Skipping unreadable cached file $refPath of $key" }
                    false
                }
                if (stopped) {
                    more = true
                    break
                }
            }
            ModuleScan(hits, more = more, attachedLibraries = cached.attachedLibraries.map { it.name })
        }
    }

    /** The position a `search_modules` cursor names: a module, and how many of its hits were served. */
    private class ModuleSearchPosition(val module: String, val hitsServed: Int)

    /**
     * Mints the opaque `search_modules` continuation token. Opaque for the reason MCP requires it
     * of cursors — nothing may construct one by hand — and fingerprinted so a token cannot be
     * carried over to a *different* query, where its position would name a page of some other
     * result set. The server keeps no state behind it: the position is the whole handle, so there
     * is nothing to expire.
     */
    private fun encodeModuleSearchCursor(fingerprint: String, at: ModuleSearchPosition): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "$SEARCH_CURSOR_PREFIX$fingerprint:${at.hitsServed}:${at.module}".toByteArray(Charsets.UTF_8),
        )

    private fun decodeModuleSearchCursor(cursor: String, fingerprint: String): ModuleSearchPosition {
        val decoded = runCatching { String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8) }.getOrNull()
        val parts = decoded?.removePrefix(SEARCH_CURSOR_PREFIX)?.split(':', limit = 3)
        require(decoded?.startsWith(SEARCH_CURSOR_PREFIX) == true && parts?.size == 3) {
            "Invalid 'cursor' for search_modules. Pass back the 'nextCursor' from a previous call " +
                "verbatim, or omit 'cursor' to start a new search."
        }
        val hitsServed = parts[1].toIntOrNull()
        require(hitsServed != null && hitsServed >= 0) {
            "Invalid 'cursor' for search_modules. Pass back the 'nextCursor' from a previous call " +
                "verbatim, or omit 'cursor' to start a new search."
        }
        require(parts[0] == fingerprint) {
            "This 'cursor' was minted for a different search. Repeat the original 'query', " +
                "'regex', 'ignoreCase', 'scope' and 'modulePattern' with it, or omit 'cursor' to " +
                "start a new search."
        }
        return ModuleSearchPosition(parts[2], hitsServed)
    }

    /** Binds a cursor to the arguments it was minted for; not a secret, just a mismatch detector. */
    private fun searchFingerprint(
        query: String,
        regex: Boolean,
        ignoreCase: Boolean,
        scope: SearchScope,
        modulePattern: String?,
    ): String {
        // Length-prefixed, so no two argument tuples can hash the same material however the
        // separator appears inside a query.
        val material = listOf(query, regex.toString(), ignoreCase.toString(), scope.label, modulePattern ?: "")
            .joinToString("|") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * What a `search_modules` caller should do next, when there is something to do: the coverage
     * gaps first (a module that was not searched is the one thing a hit list cannot show), then the
     * continuation. `null` when the scan was complete and exhaustive.
     */
    private fun moduleSearchHint(
        notCached: Int,
        stale: Int,
        truncated: Boolean,
        namePattern: String?,
        unfetchedLibraries: List<ModuleKey> = emptyList(),
    ): String? {
        val patternArg = namePattern?.let { ", pattern=\"$it\"" } ?: ""
        val sentences = buildList {
            if (notCached > 0) {
                add(
                    "$notCached matching module(s) are not cached and were not searched — " +
                        "list_modules(status=\"not_cached\"$patternArg) names them, and " +
                        "fetch_module adds one to the search.",
                )
            }
            if (unfetchedLibraries.isNotEmpty()) {
                val shown = unfetchedLibraries.take(MAX_NAMED_LIBRARIES).joinToString(", ")
                val more = unfetchedLibraries.size - MAX_NAMED_LIBRARIES
                add(
                    "The searched modules attach libraries that are not fetched: $shown" +
                        (if (more > 0) " and $more more" else "") +
                        ". Code they call that is not found here is most likely there — " +
                        "fetch_module each, then search again.",
                )
            }
            if (stale > 0) {
                add(
                    "$stale cached module(s) are stale — they changed on disk since they were " +
                        "indexed, or an older build of this server indexed them — and were " +
                        "skipped. list_modules(status=\"stale\"$patternArg) names them with a " +
                        "'staleReason'; call fetch_module on them, then search again.",
                )
            }
            if (truncated) {
                add(
                    "More remains: call search_modules again with the same arguments and 'cursor' " +
                        "set to the returned 'nextCursor'.",
                )
            }
        }
        return sentences.joinToString(" ").ifEmpty { null }
    }

    /**
     * The files a search scans, each with the [SourceRef]-style path a hit reports: the PL/SQL
     * sidecars under the module's cache entry, and the module's own converted text form (never a
     * sibling module's — the converted directory can be shared by all of them).
     */
    private fun searchableFiles(index: ModuleIndex, scope: SearchScope): List<Pair<String, Path>> {
        val plsql = scope.plsql
        val xml = scope.xml
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

    /**
     * An item row without the descriptive properties, for `get_block`'s concise default. Only
     * fields a caller can recover by asking for `verbosity: "detailed"` are dropped — never one
     * whose absence would read as a fact about the item.
     */
    private fun conciseItem(item: ItemInfo): ItemInfo = ItemInfo(
        name = item.name,
        itemType = item.itemType,
        prompt = item.prompt,
        propertyClass = item.propertyClass,
        triggerNames = item.triggerNames,
        inherited = item.inherited,
    )

    // --- get_block: relations ---

    /**
     * The relations of [index] that name [block] as their detail, with the block each is written on,
     * in block order. Forms writes a relation only on its master, so this side is found by looking
     * rather than stored twice.
     */
    private fun detailRelationsOf(index: ModuleIndex, block: BlockInfo): List<MasterRelation> =
        index.blocks.flatMap { master ->
            master.relations
                .filter { it.detailBlock.equals(block.name, ignoreCase = true) }
                .map { MasterRelation(master.name, it) }
        }

    /**
     * What a `get_block` says about the relations it served: the ones cut for size, with the call
     * that reads each; and where a subclassed block's or relation's missing definition lives, so
     * that no relations, or a relation with no join, never reads as a fact about the form.
     */
    private fun relationHints(
        key: ModuleKey,
        block: BlockInfo,
        served: List<RelationInfo>,
        detailOfServed: List<MasterRelation>,
        detailOfAll: List<MasterRelation>,
    ): List<String> = buildList {
        val omitted = block.relations.drop(served.size).map { it.name to block.name } +
            detailOfAll.drop(detailOfServed.size).map { it.relation.name to it.masterBlock }
        omitted.firstOrNull()?.let { (name, owner) ->
            add(
                "Returned ${served.size} of the ${block.relations.size} relations '${block.name}' is the " +
                    "master of and ${detailOfServed.size} of the ${detailOfAll.size} naming it as detail: " +
                    "the rest did not fit one response. Read each with get_object_xml(module=\"$key\", " +
                    "objectType=\"Relation\", name=\"$name\", owner=\"$owner\"), naming the next the same way.",
            )
        }
        if (block.inherited != null && block.relations.isEmpty()) {
            add(
                "This module records no relations on the subclassed block; any it is the master of " +
                    "are defined with it in its parent.",
            )
        }
        val joinsElsewhere = (served + detailOfServed.map { it.relation })
            .filter { it.inherited != null && it.joinCondition == null }
            .map { it.name }
            .distinct()
        if (joinsElsewhere.isNotEmpty()) {
            add(
                "Relation(s) ${joinsElsewhere.joinToString(", ")} are subclassed and write no join " +
                    "condition here; it is defined with the parent (see each relation's 'inherited').",
            )
        }
    }

    // --- get_block: effective DML properties and data-source columns ---

    /** A property class followed to the end of its chain, or as far as the cache allowed. */
    private class ClassChain(
        val dml: ItemDml,
        val geometry: ItemGeometry,
        val resolved: Boolean,
        val through: List<ModuleKey>,
        val missingModule: ModuleKey?,
    )

    /**
     * [effectiveItemDml]'s result: the resolved items that fit, the unresolved items that fit, and
     * one account per class used. [resolvedTotal] and [unresolvedTotal] count every item of each
     * kind, so a cut can say how many did not fit. Both lists keep item order and stop at the first
     * row that does not fit, so the items omitted from the map are [firstOmitted] and every resolved
     * item after it.
     */
    private class EffectiveDml(
        val items: Map<String, ItemDml>,
        val geometry: Map<String, ItemGeometry>,
        val unresolved: List<UnresolvedItem>,
        val classes: List<PropertyClassResolution>,
        val subclassedItems: Int,
        val resolvedTotal: Int,
        val cut: Boolean,
        val omitted: List<String>,
        val unresolvedTotal: Int,
        val unresolvedCut: Boolean,
        val geometryTotal: Int,
        val geometryCut: Boolean,
        val geometryOmitted: List<String>,
    ) {
        val firstOmitted: String? get() = omitted.firstOrNull()
    }

    /**
     * Each item's DML properties with its property class applied, field by field: what the item
     * writes, else what its class writes, else what that class is based on.
     *
     * An item is included only when its chain resolved to the end, because only then is a `null`
     * field the Forms default rather than "not known". Every other item becomes an
     * [UnresolvedItem] instead, so the absence is stated per item rather than left to be inferred:
     * one whose chain needs a module that is not fetched or is stale, one whose chain cannot be
     * followed at all, and one subclassed from another module, whose properties live with its
     * parent object, which this does not walk.
     */
    private suspend fun effectiveItemDml(index: ModuleIndex, block: BlockInfo, budget: RowBudget): EffectiveDml {
        val chains = mutableMapOf<String, ClassChain>()
        val usage = mutableMapOf<String, Int>()
        val items = linkedMapOf<String, ItemDml>()
        val geometry = linkedMapOf<String, ItemGeometry>()
        val unresolved = mutableListOf<UnresolvedItem>()
        var subclassed = 0
        for (item in block.items) {
            if (item.inherited != null) {
                subclassed++
                unresolved += UnresolvedItem(item.name, item.propertyClass, UnresolvedReason.SUBCLASSED)
                continue
            }
            val own = item.dml ?: ItemDml()
            val ownSize = ItemGeometry(item.width, item.height)
            val className = item.propertyClass
            if (className == null) {
                items[item.name] = own
                geometry.putSize(item.name, ownSize)
                continue
            }
            val canonical = className.uppercase()
            usage.merge(canonical, 1, Int::plus)
            val chain = chains.getOrPut(canonical) {
                resolveClassChain(index, className, visited = mutableSetOf(), hops = 0)
            }
            when {
                chain.resolved -> {
                    items[item.name] = own.over(chain.dml)
                    geometry.putSize(item.name, ownSize.over(chain.geometry))
                }
                chain.missingModule != null -> unresolved += UnresolvedItem(
                    item.name,
                    className,
                    UnresolvedReason.CLASS_MODULE_NOT_FETCHED,
                    chain.missingModule,
                )
                else -> unresolved += UnresolvedItem(item.name, className, UnresolvedReason.CLASS_NOT_FOLLOWABLE)
            }
        }
        // The unknowns are spent first: they are small, and they are what keeps an item missing
        // from the map from reading as an item with nothing to report.
        val (unresolvedFitted, unresolvedCut) = budget.take(
            unresolved,
            UnresolvedItem.serializer(),
            share = budget.share(UNRESOLVED_BUDGET_SHARE),
        )
        // Resolved rows are the largest section after the items themselves; a screen too wide for
        // both keeps the items it served and cuts the map. The cut is returned, never dropped: an
        // item absent from the map otherwise reads exactly like an item whose class did not resolve.
        val (fitted, cut) = budget.take(
            items.entries.map { it.key to it.value },
            PairSerializer(String.serializer(), ItemDml.serializer()),
            share = budget.share(EFFECTIVE_DML_BUDGET_SHARE),
        )
        // Sizes are two small ints per row and are asked for far less often than the DML, so they
        // go last of the resolved sections — but they are still counted and cut like the rest,
        // because an item silently dropped from here reads as an item with no size of its own.
        val (fittedSizes, geometryCut) = budget.take(
            geometry.entries.map { it.key to it.value },
            PairSerializer(String.serializer(), ItemGeometry.serializer()),
            share = budget.share(EFFECTIVE_GEOMETRY_BUDGET_SHARE),
        )
        val classes = chains.map { (canonical, chain) ->
            PropertyClassResolution(
                name = index.propertyClassDetails.firstOrNull { it.name.uppercase() == canonical }?.name ?: canonical,
                resolved = chain.resolved,
                resolvedThrough = chain.through,
                missingModule = chain.missingModule,
                itemCount = usage[canonical] ?: 0,
            )
        }
        return EffectiveDml(
            items = fitted.toMap(),
            geometry = fittedSizes.toMap(),
            unresolved = unresolvedFitted,
            classes = classes,
            subclassedItems = subclassed,
            resolvedTotal = items.size,
            cut = cut,
            omitted = items.keys.drop(fitted.size),
            unresolvedTotal = unresolved.size,
            unresolvedCut = unresolvedCut,
            geometryTotal = geometry.size,
            geometryCut = geometryCut,
            geometryOmitted = geometry.keys.drop(fittedSizes.size),
        )
    }

    /**
     * Records [size] for [name] unless it is empty. A row carrying neither dimension says only
     * "resolved, and nothing written anywhere in the chain" — which the item's presence in
     * `effectiveDml` already says, so it would spend budget to repeat it.
     */
    private fun MutableMap<String, ItemGeometry>.putSize(name: String, size: ItemGeometry) {
        if (size.width != null || size.height != null) put(name, size)
    }

    /**
     * Follows one property class: its own values, then the class it is based on in the same module,
     * or — for a stub — the class its pointer names in another module, read only if that module is
     * cached and current. Bounded by [MAX_INHERITANCE_HOPS] and guarded against loops, since the
     * chain is data read from converted files.
     */
    private suspend fun resolveClassChain(
        index: ModuleIndex,
        className: String,
        visited: MutableSet<String>,
        hops: Int,
    ): ClassChain {
        val here = listOf(index.key)
        val info = index.propertyClassDetails.firstOrNull { it.name.equals(className, ignoreCase = true) }
        if (info == null || hops >= MAX_INHERITANCE_HOPS || !visited.add("${index.key}:${className.uppercase()}")) {
            return ClassChain(ItemDml(), ItemGeometry(), resolved = false, through = here, missingModule = null)
        }
        val own = info.item ?: ItemDml()
        val ownSize = info.geometry ?: ItemGeometry()
        val pointer = info.inherited
        val parent: ClassChain = when {
            pointer != null -> {
                val parentKey = inheritedModuleKey(pointer)
                    ?: return ClassChain(own, ownSize, resolved = false, through = here, missingModule = null)
                val parentIndex = runCatching { index(parentKey) }.getOrNull()
                    ?: return ClassChain(own, ownSize, resolved = false, through = here, missingModule = parentKey)
                resolveClassChain(parentIndex, pointer.name ?: className, visited, hops + 1)
            }
            info.propertyClass != null -> resolveClassChain(index, info.propertyClass!!, visited, hops + 1)
            else -> return ClassChain(own, ownSize, resolved = true, through = here, missingModule = null)
        }
        return ClassChain(
            dml = own.over(parent.dml),
            geometry = ownSize.over(parent.geometry),
            resolved = parent.resolved,
            through = (here + parent.through).distinct(),
            missingModule = parent.missingModule,
        )
    }

    /** This value where it is written, [fallback]'s where it is not. */
    private fun ItemDml.over(fallback: ItemDml): ItemDml = ItemDml(
        databaseItem = databaseItem ?: fallback.databaseItem,
        insertAllowed = insertAllowed ?: fallback.insertAllowed,
        updateAllowed = updateAllowed ?: fallback.updateAllowed,
        updateIfNull = updateIfNull ?: fallback.updateIfNull,
        queryAllowed = queryAllowed ?: fallback.queryAllowed,
        enabled = enabled ?: fallback.enabled,
        keyboardNavigable = keyboardNavigable ?: fallback.keyboardNavigable,
        primaryKey = primaryKey ?: fallback.primaryKey,
        required = required ?: fallback.required,
        maximumLength = maximumLength ?: fallback.maximumLength,
        initialValue = initialValue ?: fallback.initialValue,
        copyValueFromItem = copyValueFromItem ?: fallback.copyValueFromItem,
    )

    /** This size where it is written, [fallback]'s where it is not — per dimension, as for DML. */
    private fun ItemGeometry.over(fallback: ItemGeometry): ItemGeometry = ItemGeometry(
        width = width ?: fallback.width,
        height = height ?: fallback.height,
    )

    /**
     * What a detailed `get_block` says about the items missing from `effectiveDml`: the ones cut
     * for size, with the call that reaches them; the module to fetch for an unresolved class,
     * named with the exact call; and the subclassed items it does not try to resolve. [again]
     * renders this call narrowed to the given item names (or as [requested], when `null`).
     */
    private fun effectiveDmlHints(
        effective: EffectiveDml,
        again: (List<String>?) -> String,
        requested: List<String>?,
    ): List<String> =
        buildList {
            if (effective.cut) {
                // First, because it changes how every other absence in the map reads.
                val named = effective.omitted.take(MAX_NAMED_OMITTED_ITEMS)
                val more = if (effective.omitted.size > named.size) {
                    " (the first ${named.size} of ${effective.omitted.size}; name the rest the same way)"
                } else {
                    ""
                }
                add(
                    "'effectiveDml' covers ${effective.items.size} of the ${effective.resolvedTotal} " +
                        "items whose properties resolved: the rest, from '${effective.firstOmitted}' on, " +
                        "did not fit one response, so an item missing from it is not thereby unresolved. " +
                        "Ask for them by name with ${again(named)}$more.",
                )
            }
            if (effective.unresolvedCut) {
                add(
                    "'unresolvedItems' lists ${effective.unresolved.size} of the ${effective.unresolvedTotal} " +
                        "items whose properties are unknown; the counts below cover all of them.",
                )
            }
            if (effective.geometryCut) {
                val named = effective.geometryOmitted.take(MAX_NAMED_OMITTED_ITEMS)
                val more = if (effective.geometryOmitted.size > named.size) {
                    " (the first ${named.size} of ${effective.geometryOmitted.size}; name the rest the same way)"
                } else {
                    ""
                }
                add(
                    "'effectiveGeometry' covers ${effective.geometry.size} of the " +
                        "${effective.geometryTotal} items whose size resolved: the rest did not fit, so " +
                        "an item missing from it has a size this response does not state. Ask for them " +
                        "by name with ${again(named)}$more.",
                )
            }
            effective.classes.filter { !it.resolved }.groupBy { it.missingModule }.forEach { (missing, classes) ->
                val itemCount = classes.sumOf { it.itemCount }
                val names = classes.joinToString(", ") { it.name }
                if (missing != null) {
                    add(
                        "$itemCount item(s) take their properties from $names, defined in '$missing', " +
                            "which is not fetched or is stale, so they are in 'unresolvedItems', not " +
                            "'effectiveDml'. Call fetch_module(module=\"$missing\"), then ${again(requested)}.",
                    )
                } else {
                    add(
                        "$itemCount item(s) use $names, whose definition could not be followed; " +
                            "they are in 'unresolvedItems', not 'effectiveDml' — " +
                            "get_object_xml(objectType=\"PropertyClass\") shows the raw attributes.",
                    )
                }
            }
            if (effective.subclassedItems > 0) {
                add(
                    "${effective.subclassedItems} item(s) are subclassed from another module and are " +
                        "not in 'effectiveDml'; their properties are defined with the parent object " +
                        "(see each item's 'inherited').",
                )
            }
        }

    /**
     * The items of [block] a `get_block` call asked for, in block order — or every item, as the
     * same list instance, when [names] selects nothing. A name may carry its block as a prefix
     * (`ORDERS.CUSTOMER_ID`), the way items are written in PL/SQL. A name the block does not have
     * fails the call with the block's item names, rather than quietly returning fewer rows.
     */
    private fun selectItems(key: ModuleKey, block: BlockInfo, names: List<String>?): List<ItemInfo> {
        val wanted = names.orEmpty()
            .map { name -> name.trim().removePrefix(":").let { stripBlockPrefix(it, block.name) }.uppercase() }
            .filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return block.items
        val present = block.items.mapTo(HashSet()) { it.name.uppercase() }
        val unknown = wanted.filter { it !in present }.distinct()
        require(unknown.isEmpty()) {
            val shown = block.items.take(MAX_OVERVIEW_NAMES).joinToString(", ") { it.name }
            val more = (block.items.size - MAX_OVERVIEW_NAMES).takeIf { it > 0 }?.let { " … and $it more" }.orEmpty()
            "No item ${unknown.joinToString(", ") { "'$it'" }} in block '${block.name}' of $key. Items: $shown$more"
        }
        val set = wanted.toSet()
        return block.items.filter { it.name.uppercase() in set }
    }

    private fun stripBlockPrefix(name: String, block: String): String {
        val dot = name.indexOf('.')
        return if (dot > 0 && name.substring(0, dot).equals(block, ignoreCase = true)) name.substring(dot + 1) else name
    }

    /**
     * The block's data-source columns, read from its own slice of the converted XML, and the columns
     * no item names. Items name a column by `ColumnName` — whose table alias, if any, is dropped,
     * since a block over an inline subquery writes `S.OWNER` against a column recorded as `OWNER` —
     * or by their own name when they have none.
     */
    private suspend fun blockColumns(key: ModuleKey, block: BlockInfo, budget: RowBudget): BlockColumns {
        val ref = block.sourceRef
        val all = if (ref == null || block.dataSourceColumnCount == 0) {
            emptyList()
        } else {
            DataSourceColumnReader.read(readRef(key, ref))
        }
        val named = block.items.mapTo(HashSet()) { (it.columnName?.substringAfterLast('.') ?: it.name).uppercase() }
        val withoutItem = all.filter { it.name.uppercase() !in named }
        val mandatory = withoutItem.filter { it.mandatory }
        // The two name lists are the answer and the column rows are the evidence, so the rows give
        // way first: the names are spent from the budget before them, mandatory ones first, since
        // those are what an insert fails on. They are spent rather than merely capped because they
        // are built after the rows and a thousand names is kilobytes — enough, once, to carry the
        // whole result past MAX_RESULT_CHARS.
        val (shownMandatory, mandatoryCut) = budget.take(
            mandatory.take(MAX_COLUMN_NAMES).map { it.name },
            String.serializer(),
        )
        val (shownWithoutItem, withoutItemCut) = budget.take(
            withoutItem.take(MAX_COLUMN_NAMES).map { it.name },
            String.serializer(),
        )
        val (capped, overLimit) = capRows(all)
        val (rows, cut) = budget.take(capped, DataSourceColumnInfo.serializer())
        return BlockColumns(
            total = all.size,
            truncated = cut || overLimit,
            columns = rows,
            columnsWithoutItem = shownWithoutItem,
            columnsWithoutItemTotal = withoutItem.size,
            mandatoryColumnsWithoutItem = shownMandatory,
            mandatoryColumnsWithoutItemTotal = mandatory.size,
            namesTruncated = withoutItemCut || mandatoryCut ||
                shownWithoutItem.size < withoutItem.size || shownMandatory.size < mandatory.size,
        )
    }

    // --- addressable source (SourceLocation, read_source) ---

    /** The addressable location of [ref] in [key]'s cache — a ref plus the URI that opens it. */
    private fun locationOf(key: ModuleKey, ref: SourceRef?): SourceLocation? = ref?.let {
        SourceLocation(
            uri = sourceUri(key, it.file).orEmpty(),
            file = it.file,
            startLine = it.startLine,
            endLine = it.endLine,
        )
    }

    /**
     * The cache-relative path [target] names, whether it arrived as a source URI or as the ref
     * path itself. A URI for a *different* module is rejected rather than silently read against
     * this one, and the message says which two disagree.
     */
    private fun refPathOf(key: ModuleKey, index: ModuleIndex, target: String): String {
        val trimmed = target.trim()
        require(trimmed.isNotEmpty()) { "Pass 'uri' or 'file' naming the source to read." }
        if (!trimmed.contains("://")) return trimmed
        return sourceRefPath(key, trimmed, index.convertedFile) ?: throw IllegalArgumentException(
            "'$trimmed' is not a source URI of $key. Source URIs look like " +
                "'oracleforms://$key/converted' or 'oracleforms://$key/plsql/triggers/NAME.sql', " +
                "and every result that points at a file carries one as 'source.uri'.",
        )
    }

    /** One [slice]: the text, the last line it reaches, and why it stopped short of the request. */
    private class SourceSlice(
        val text: String,
        val lastLine: Int,
        val truncated: Boolean,
        val lineCut: Boolean,
        val stoppedAtLineCap: Boolean,
    )

    /**
     * Lines [from]..[to] of [lines], stopping at whichever ceiling comes first, with the last line
     * actually taken and whether anything was left behind.
     *
     * The character budget is measured in **JSON-escaped** characters ([jsonEscapedLength]), because
     * that is what a client receives: converted XML is dense with quotes, each of which travels as
     * two characters, so a budget counted on the raw text overshoots on exactly the files most
     * likely to reach it. It is checked per line, so a slice never overshoots — except for a first
     * line that alone exceeds the budget, which is taken and cut ([SourceSlice.lineCut]), because
     * returning nothing would be worse than returning a prefix that says it is one.
     */
    private fun slice(
        lines: List<String>,
        from: Int,
        to: Int,
        maxLines: Int?,
    ): SourceSlice {
        val cap = (maxLines ?: DEFAULT_SOURCE_LINES).coerceIn(1, MAX_SOURCE_LINES)
        val taken = mutableListOf<String>()
        var chars = 0
        var last = from - 1
        var stoppedAtLineCap = false
        for (i in from..to) {
            if (taken.size == cap) {
                stoppedAtLineCap = true
                break
            }
            val line = lines[i - 1]
            val cost = jsonEscapedLength(line) + 2 // plus the escaped newline that joins it
            if (chars + cost > MAX_SOURCE_CHARS) {
                if (taken.isEmpty()) {
                    val prefix = line.take(jsonEscapedPrefixLength(line, MAX_SOURCE_CHARS))
                    return SourceSlice(prefix, i, truncated = true, lineCut = true, stoppedAtLineCap = false)
                }
                break
            }
            taken += line
            chars += cost
            last = i
        }
        return SourceSlice(
            text = taken.joinToString("\n"),
            lastLine = maxOf(last, from),
            truncated = last < to,
            lineCut = false,
            stoppedAtLineCap = stoppedAtLineCap,
        )
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

    /**
     * A body found by following a subclassing pointer: the module it was found in, the text, and
     * the [ref] it came from — which addresses a file in *that* module's cache, not this one's.
     */
    private class InheritedBody(val module: ModuleKey, val text: String, val ref: SourceRef)

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
            val textRef = found.textRef
            if (textRef != null) {
                val text = readRef(parentKey, textRef)
                if (text.isNotBlank()) return InheritedBody(parentKey, text, textRef)
            }
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

    /**
     * The libraries named in [attached] that are in the forms directory ([scannedKeys]) but not
     * cached ([cachedKeys]): where code a module calls but does not define most likely lives, and
     * what no read tool can see until it is fetched. A library absent from the directory is left
     * out — naming a fetch that cannot succeed is not a hint.
     */
    private fun unfetchedLibraries(
        attached: Collection<String>,
        scannedKeys: Set<ModuleKey>,
        cachedKeys: Set<ModuleKey>,
    ): List<ModuleKey> = attached
        .map { ModuleKey.of(it.trim(), ModuleType.LIBRARY) }
        .distinct()
        .filter { it in scannedKeys && it !in cachedKeys }

    /**
     * This summary with the `fetch_module` hint: the attached libraries not fetched yet, each as
     * the call that fetches it. The converter's caveat about libraries rides along when it has one
     * — a Forms2XML-based command cannot convert them, and the calls named here would fail on
     * exactly that.
     */
    private suspend fun FetchModuleSummary.withLibraryHint(scanned: List<ScannedModule>): FetchModuleSummary {
        if (attachedLibraries.isEmpty()) return this
        val missing = unfetchedLibraries(
            attached = attachedLibraries,
            scannedKeys = scanned.mapTo(HashSet()) { it.key },
            cachedKeys = cache.list().toSet(),
        )
        if (missing.isEmpty()) return this
        val shown = missing.take(MAX_NAMED_LIBRARIES)
        val more = missing.size - shown.size
        val hint = "${module.name} attaches ${shown.joinToString(", ")}" +
            (if (more > 0) " and $more more" else "") +
            ", not fetched — program units its triggers call may live there: " +
            shown.joinToString(", ") { "fetch_module(module=\"$it\")" } + "." +
            (this@FormsService.converter.conversionCaveat(ModuleType.LIBRARY)?.let { " $it" } ?: "")
        return copy(hint = hint)
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
        convertedUri = moduleConvertedUri(key),
    )

    private companion object {
        const val MAX_SEARCH_RESULTS = 200
        const val FETCH_STEPS = 3

        /** Phases of a re-index: the conversion phase of [FETCH_STEPS] is the one it skips. */
        const val REINDEX_STEPS = 2

        /** How much of a matching line a hit quotes, in both search tools. */
        const val SNIPPET_CHARS = 200

        /** Scope vocabulary shared by `search_source` and `search_modules`. */
        const val SCOPE_PLSQL = "plsql"
        const val SCOPE_XML = "xml"
        const val SCOPE_ALL = "all"

        /**
         * How many cached modules `search_modules` reads in parallel. The scan is IO-bound over
         * whole converted files; the chunk is assembled in key order afterwards, so this changes
         * the speed of a page and never its contents.
         */
        const val SEARCH_MODULE_CHUNK = 8

        /**
         * How `get_block` divides one response between its lists. Relations go first — a block is
         * the master or detail of a handful at most — each side capped so a generated form cannot
         * crowd out its items with them. The items are the block, so they
         * take most of it — less when the caller also asked for columns, since a base table's
         * columns are then part of the question. Each served item is then a row of either the
         * unresolved list (spent first: small, and what makes an absence from the map legible) or
         * the resolved map, then the resolved sizes; what is left goes to the column rows, whose two
         * summary name lists are never cut.
         */
        const val RELATION_BUDGET_SHARE = 10
        const val ITEM_BUDGET_SHARE = 80
        const val ITEM_BUDGET_WITH_COLUMNS = 55
        const val UNRESOLVED_BUDGET_SHARE = 40
        const val EFFECTIVE_DML_BUDGET_SHARE = 60
        const val EFFECTIVE_GEOMETRY_BUDGET_SHARE = 40

        /** How many of the items cut from `effectiveDml` a `get_block` hint names in its follow-up call. */
        const val MAX_NAMED_OMITTED_ITEMS = 20

        /**
         * The most of a `search_source` response its per-file counts may take. A row is ~150
         * characters, so this holds about a hundred files; the hits get the rest, and a page of
         * them that does not fit is cut and continued like any other.
         */
        const val SEARCH_FILE_BUDGET_SHARE = 30

        /** How many un-fetched attached libraries a `search_modules` or `fetch_module` hint names before counting. */
        const val MAX_NAMED_LIBRARIES = 5

        /** Marks a `search_modules` cursor as ours, so a token from elsewhere fails cleanly. */
        const val SEARCH_CURSOR_PREFIX = "module-search:v1:"

        /**
         * Output ceilings. Claude Code caps MCP tool output at 25,000 tokens (~100 KB) and warns
         * at 10,000, so every list-shaped result is bounded and says so rather than being rejected
         * whole: [DEFAULT_MODULE_PAGE]/[MAX_MODULE_PAGE] page `list_modules`, [MAX_LIST_ROWS] caps
         * the per-module lists, and [MAX_OVERVIEW_NAMES] caps each section of the overview.
         */
        const val MAX_LIST_ROWS = 1_000
        const val MAX_OVERVIEW_NAMES = 500

        /**
         * How many column names each of `get_block(columns=true)`'s two summary lists carries.
         * They are built after the column rows were budgeted, so this is what bounds them; a
         * generated table of a thousand columns would otherwise push the result past
         * [MAX_RESULT_CHARS] on names alone, which is what it once did.
         */
        const val MAX_COLUMN_NAMES = 500


        /**
         * `read_source` ceilings. Two of them, because neither bounds this data alone: a converted
         * form is hundreds of thousands of lines, while a doubly-escaped PL/SQL body can be one
         * line holding a whole procedure. [DEFAULT_SOURCE_LINES] is what a caller gets without
         * asking — enough for a trigger body or an XML fragment, small enough to read twice.
         *
         * [MAX_SOURCE_CHARS] (and `get_object_xml`'s [MAX_OBJECT_XML_CHARS]) count JSON-escaped
         * characters and sit well inside [MAX_RESULT_CHARS]. They were 100,000 and 500,000 raw
         * characters: a slice of attribute-dense XML that obeyed them was still over Claude Code's
         * 25,000-token default, so a call that respected every ceiling here was spilled to a file.
         */
        const val DEFAULT_SOURCE_LINES = 200
        const val MAX_SOURCE_LINES = 2_000
        const val MAX_SOURCE_CHARS = 40_000
        const val MAX_OBJECT_XML_CHARS = 40_000

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
