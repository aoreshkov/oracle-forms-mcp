package app.oreshkov.oracleformsmcp.dto

import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.model.Author
import app.oreshkov.oracleformsmcp.model.BlockInfo
import app.oreshkov.oracleformsmcp.model.CanvasInfo
import app.oreshkov.oracleformsmcp.model.DataSourceColumnInfo
import app.oreshkov.oracleformsmcp.model.ElementId
import app.oreshkov.oracleformsmcp.model.InheritanceRef
import app.oreshkov.oracleformsmcp.model.ItemDml
import app.oreshkov.oracleformsmcp.model.ItemGeometry
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
import app.oreshkov.oracleformsmcp.model.RelationInfo
import app.oreshkov.oracleformsmcp.model.TextEncoding
import app.oreshkov.oracleformsmcp.model.TriggerLevel
import app.oreshkov.oracleformsmcp.model.WindowInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Response shapes returned by the MCP tools, shared with the `server` module so tools just
 * serialize a value object instead of hand-rolling JSON. All read-only collections with defaults
 * so the wire format stays forward-compatible as fields are added.
 */

/**
 * A readable location inside a module's cached files: the cache-relative [file] and 1-based
 * inclusive line range of a `SourceRef`, plus the [uri] that addresses it.
 *
 * The line range is only useful if something can act on it. A cache-relative path is deliberately
 * layout-independent — the cache root is not a client's business, and under the container image or
 * the HTTP transport a host path means nothing at all — so the range on its own resolved to
 * nothing a caller could open. [uri] closes that: it addresses the same file as an MCP resource,
 * and `read_source` takes either form.
 *
 * **What the lines count from is [file], never the module.** A trigger's or program unit's body is
 * extracted to a sidecar of its own holding that body alone, so [startLine] 1 is the first line of
 * *that body* — `POST-INSERT:75` is line 75 of the POST-INSERT trigger, and citing it as
 * `ORDERS.fmb:75` points at nothing anyone can find. The exceptions are the two files that are
 * whole modules: the converted XML, and a `.pll`'s `.pld` dump, whose program units carry their
 * line range within the one dump.
 */
@Serializable
@SerialName("SourceLocation")
public data class SourceLocation(
    val uri: String = "",
    val file: String = "",
    val startLine: Int = 1,
    val endLine: Int = 1,
)

/**
 * `read_source` — one capped slice of a cached file.
 *
 * [source] echoes the range actually returned, which is not necessarily the range asked for:
 * [truncated] says the request was cut at the line or character ceiling, and [totalLines] gives
 * the size of the whole file so the next call can pick up where this one stopped.
 *
 * A cut is also stated the way a reader acts on it, because the flag alone gets read past: a long
 * range of converted XML comes back as a fraction of it, and the next request starts where the
 * caller *thought* the first had ended, leaving a hole. [nextStartLine] is where to continue (`null` when nothing
 * of the requested range is left), [requestedEndLine] is the end the request was clamped to, and
 * [hint] spells out the call. [lineCut] means the last line returned is itself only a prefix — one
 * line of converted XML is one whole object and can outgrow a response on its own.
 */
@Serializable
@SerialName("SourceText")
public data class SourceText(
    val module: ModuleKey,
    val source: SourceLocation = SourceLocation(),
    val totalLines: Int = 0,
    val truncated: Boolean = false,
    val requestedEndLine: Int? = null,
    val nextStartLine: Int? = null,
    val lineCut: Boolean = false,
    val hint: String? = null,
    val text: String = "",
)

/**
 * Where the PL/SQL body served beside this value came from.
 *
 * The distinction exists because [INHERITED] and [EMPTY] look identical on the wire — both carry
 * `text: ""` — while meaning opposite things. Serving an inherited body as a bare empty string
 * asserts that the object does nothing, which is the one lie a reader has no way to detect.
 *
 * [OWN] the body is defined in this module (the ordinary case, and the default so the field can
 * be omitted); [INHERITED] the object is subclassed and its code lives in the module the
 * accompanying `inherited` ref names; [RESOLVED] the body shown *is* the parent's, followed for
 * this call (`resolvedFrom` says from where); [EMPTY] the object genuinely has no body.
 */
@Serializable
@SerialName("BodySource")
public enum class BodySource {
    OWN,
    INHERITED,
    RESOLVED,
    EMPTY,
}

/**
 * Why a `STALE` row is stale. The action is the same either way — `fetch_module` — but the cost
 * is not, and neither is the reason a warm-looking module is being re-fetched.
 */
@Serializable
@SerialName("StaleReason")
public enum class StaleReason {
    /** The source file changed since it was indexed; re-fetching re-converts it. */
    SOURCE_CHANGED,

    /** An older build of this server wrote the index; re-fetching only re-parses it. */
    INDEX_OUTDATED,
}

/**
 * One row of `list_modules`. [name] is the flat identifier to match and reason about; [module]
 * carries the same name with its type as the canonical key every other tool takes.
 *
 * [staleReason] is set only on a `STALE` row: the status says what to do, this says why.
 */
@Serializable
@SerialName("ModuleStatusEntry")
public data class ModuleStatusEntry(
    val name: String,
    val module: ModuleKey,
    val type: ModuleType,
    val path: String? = null,
    val sizeBytes: Long? = null,
    val lastModified: String? = null,
    val status: ModuleStatus,
    val staleReason: StaleReason? = null,
    val hasPreConverted: Boolean = false,
)

/**
 * `list_modules` — one filtered, capped page of the forms directory.
 *
 * The paging fields precede [modules] so a reader meets them before the rows: [total] is the size
 * of the filtered result set, [returned] the rows on this page, and [truncated] says more rows
 * follow — in which case [nextCursor] is the opaque token to pass back as `cursor`.
 * [countsByStatus] summarises the name/type-filtered set *before* any `status` filter, so a first
 * call stays small and still orients ("of 40 matches, 3 are CACHED").
 * [oracleHomeConversion] says the server converts binaries itself for at least one module type
 * (Oracle tools or a site command) rather than only copying pre-converted text forms.
 *
 * [hint] carries what this page's rows do not say on their own — a module type this server's
 * conversion cannot produce, which a `NOT_CACHED` row would otherwise invite a caller to discover
 * one failed `fetch_module` at a time.
 */
@Serializable
@SerialName("ModuleList")
public data class ModuleList(
    val formsDir: String,
    val oracleHomeConversion: Boolean = false,
    val total: Int = 0,
    val returned: Int = 0,
    val truncated: Boolean = false,
    val nextCursor: String? = null,
    val countsByStatus: Map<ModuleStatus, Int> = emptyMap(),
    val hint: String? = null,
    val modules: List<ModuleStatusEntry> = emptyList(),
)

/**
 * `fetch_module` — summary of a converted-and-indexed module.
 *
 * [hint] names the [attachedLibraries] that are in the forms directory but not fetched. A form's
 * triggers usually call into them, and a library that is not fetched is invisible to every other
 * tool — so the procedures it defines would otherwise read as missing rather than elsewhere.
 */
@Serializable
@SerialName("FetchModuleSummary")
public data class FetchModuleSummary(
    val module: ModuleKey,
    val formsVersion: String? = null,
    val converter: String,
    val blockCount: Int = 0,
    val itemCount: Int = 0,
    val triggerCount: Int = 0,
    val programUnitCount: Int = 0,
    val attachedLibraries: List<String> = emptyList(),
    val fromCache: Boolean = false,
    /**
     * Where the module's converted text form can be read, as a resource URI. The first thing a
     * caller wants after a fetch is the file everything else points into, and the cache-relative
     * paths in the index name it without saying where it is.
     */
    val convertedUri: String? = null,
    val hint: String? = null,
)

/**
 * `get_module_overview` — counts plus the names of every section. [truncated] is `true` when at
 * least one section held more names than the per-section cap and was cut; drill into that section
 * with its own list tool (`list_blocks`, `list_program_units`) or `search_source`.
 */
@Serializable
@SerialName("ModuleOverview")
public data class ModuleOverview(
    val module: ModuleKey,
    val formsVersion: String? = null,
    val truncated: Boolean = false,
    val blocks: List<String> = emptyList(),
    val triggerCount: Int = 0,
    val programUnits: List<String> = emptyList(),
    val attachedLibraries: List<String> = emptyList(),
    val lovs: List<String> = emptyList(),
    val recordGroups: List<String> = emptyList(),
    val windows: List<String> = emptyList(),
    val canvases: List<String> = emptyList(),
    val alerts: List<String> = emptyList(),
    val parameters: List<String> = emptyList(),
    val visualAttributes: List<String> = emptyList(),
    val propertyClasses: List<String> = emptyList(),
    val editors: List<String> = emptyList(),
    val menus: List<String> = emptyList(),
    val objectLibraryTabs: List<String> = emptyList(),
    val detail: ModuleDetail? = null,
    val annotations: ElementAnnotations = ElementAnnotations(),
)

/**
 * The objects behind two of `get_module_overview`'s name lists, returned only at
 * `verbosity: "detailed"`.
 *
 * Names alone cannot answer the questions these sections are consulted for. Whether a window is
 * modal decides how a form is read; which window a canvas sits on is the thread from a screen to
 * the block that fills it. Both were reachable only through `get_object_xml`, one call per object.
 *
 * A sub-object rather than richer `windows`/`canvases` fields: those stay name lists, so nothing
 * a caller already reads changes shape.
 */
@Serializable
@SerialName("ModuleDetail")
public data class ModuleDetail(
    val windows: List<WindowInfo> = emptyList(),
    val canvases: List<CanvasInfo> = emptyList(),
)

/** One row of `list_blocks`. */
@Serializable
@SerialName("BlockSummary")
public data class BlockSummary(
    val name: String,
    val queryDataSourceName: String? = null,
    val itemCount: Int = 0,
    val triggerCount: Int = 0,
)

/** `list_blocks`. [total] counts every block; [truncated] says the rows were cut at the cap. */
@Serializable
@SerialName("BlockList")
public data class BlockList(
    val module: ModuleKey,
    val total: Int = 0,
    val truncated: Boolean = false,
    val blocks: List<BlockSummary> = emptyList(),
)

/**
 * `get_block` — the full block including its items and trigger names.
 *
 * A subclassed block carries `block.inherited` (and so may its items); [hint] then names the call
 * that reaches the full definition, because what this module stores is only its overrides.
 *
 * At `verbosity: "detailed"`, [effectiveDml] maps item name → the item's DML properties with its
 * property class applied: what the item writes wins, then its class, then whatever that class is
 * based on — followed into other modules only when they are already fetched. An item is present
 * only when that chain resolved to the end, so an entry's `null` field really is the Forms
 * default. Every served item missing from the map for a reason other than a cut is a row of
 * [unresolvedItems], with the module to fetch when fetching would resolve it — so the unknowns are
 * named item by item beside the map, never folded into it as `null`s that would read as defaults.
 * `block.items[].dml` stays what the item wrote.
 *
 * [effectiveGeometry] is the same resolution for the item's size, and exists for the same reason:
 * in a real form a classed item writes a `Width` and no `Height` at all, so serving only what the
 * item wrote would make a class-supplied height indistinguishable from an unset one — the very
 * absence [unresolvedItems] exists to prevent. An item is present only when its chain resolved and
 * the chain wrote at least one dimension; `block.items[].width`/`height` stay what the item wrote.
 *
 * [propertyClasses] says, once per class the served items use, whether it resolved and from which
 * modules. [columns] is present only when asked for.
 *
 * Master-detail structure is served at every verbosity, because it decides what the block can be
 * queried through: `block.relations` are the relations this block is the master of, as Forms wrote
 * them on it, and [detailOf] the relations in other blocks of the module that name this block as
 * their detail — with `preventMasterlessOperations`, the block cannot be queried except through
 * that master.
 *
 * [itemsMatched] is set when the call named `items`: how many of the block's items it matched,
 * and every other list here then describes only those. [itemTotal] still counts the whole block.
 *
 * [truncated] says `block.items`, a relation list, [effectiveDml], [effectiveGeometry],
 * [unresolvedItems] or [BlockColumns.columns] was cut to fit one response —
 * a data-entry screen of a hundred-odd detailed items with its base table behind it is larger than
 * a client accepts. The [hint] then says which, and names what to ask instead: an item missing from
 * a cut [effectiveDml] is not thereby unresolved.
 */
@Serializable
@SerialName("BlockDetail")
public data class BlockDetail(
    val module: ModuleKey,
    val block: BlockInfo,
    val source: SourceLocation? = null,
    val hint: String? = null,
    val itemTotal: Int = 0,
    val itemsMatched: Int? = null,
    val truncated: Boolean = false,
    val effectiveDml: Map<String, ItemDml> = emptyMap(),
    val effectiveGeometry: Map<String, ItemGeometry> = emptyMap(),
    val unresolvedItems: List<UnresolvedItem> = emptyList(),
    val propertyClasses: List<PropertyClassResolution> = emptyList(),
    val columns: BlockColumns? = null,
    val detailOf: List<MasterRelation> = emptyList(),
    val annotations: ElementAnnotations = ElementAnnotations(),
)

/**
 * A relation that names a `get_block` block as its detail, and the [masterBlock] it is written on.
 * Forms stores a relation only with its master, so this is that block's own [RelationInfo], found
 * by looking rather than stored a second time.
 */
@Serializable
@SerialName("MasterRelation")
public data class MasterRelation(
    val masterBlock: String,
    val relation: RelationInfo,
)

/**
 * One item `get_block` could not put in `effectiveDml`, and why. Its properties are *unknown* here,
 * not defaulted: [missingModule] names the module whose `fetch_module` resolves it, and is `null`
 * when fetching would not help.
 */
@Serializable
@SerialName("UnresolvedItem")
public data class UnresolvedItem(
    val name: String,
    val propertyClass: String? = null,
    val reason: UnresolvedReason = UnresolvedReason.CLASS_NOT_FOLLOWABLE,
    val missingModule: ModuleKey? = null,
)

/** Why an item's effective properties could not be resolved. */
@Serializable
@SerialName("UnresolvedReason")
public enum class UnresolvedReason {
    /** Its class, or a class that one is based on, lives in a module that is not fetched or is stale. */
    CLASS_MODULE_NOT_FETCHED,

    /** Its class chain breaks where fetching cannot fix it: a class not declared where named, a loop. */
    CLASS_NOT_FOLLOWABLE,

    /** The item is subclassed from another module; its properties are defined with the parent object. */
    SUBCLASSED,
}

/**
 * How one property class used by a block's items was resolved for `get_block`.
 *
 * [resolvedThrough] lists the modules the chain was read from, starting with this one. When
 * [resolved] is `false`, [missingModule] names the module the chain needed and could not read —
 * not fetched, or stale — which `fetch_module` fixes; it is `null` when the chain broke for a
 * reason fetching cannot fix (a class that is not declared where the pointer says, a loop).
 */
@Serializable
@SerialName("PropertyClassResolution")
public data class PropertyClassResolution(
    val name: String,
    val resolved: Boolean = false,
    val resolvedThrough: List<ModuleKey> = emptyList(),
    val missingModule: ModuleKey? = null,
    val itemCount: Int = 0,
)

/**
 * `get_block(columns: true)` — the block's data-source columns and how they meet its items.
 *
 * [columnsWithoutItem] are columns no item of the block names — by `ColumnName` with any table
 * alias stripped (`C.OWNER` supplies `OWNER`), or by the item's own name when it has no
 * `ColumnName`. It is structural and does not ask whether each item is a database item.
 * [mandatoryColumnsWithoutItem] is the part of it that is NOT NULL in the database: an insert fails
 * unless a trigger assigns those. [total] counts every column; [truncated] says [columns] was cut.
 *
 * The two name lists are what answers the question, so they are cut only when nothing else is left
 * to give: they are computed over every column, whatever [columns] shows, and are capped only past
 * the point where a list of names is itself kilobytes. [columnsWithoutItemTotal] and
 * [mandatoryColumnsWithoutItemTotal] count them whole, and [namesTruncated] says a list is shorter
 * than its count — because a name list read as exhaustive is how a mandatory column gets missed.
 */
@Serializable
@SerialName("BlockColumns")
public data class BlockColumns(
    val total: Int = 0,
    val truncated: Boolean = false,
    val columns: List<DataSourceColumnInfo> = emptyList(),
    val columnsWithoutItem: List<String> = emptyList(),
    val columnsWithoutItemTotal: Int = 0,
    val mandatoryColumnsWithoutItem: List<String> = emptyList(),
    val mandatoryColumnsWithoutItemTotal: Int = 0,
    val namesTruncated: Boolean = false,
)

/**
 * One row of `list_triggers`. [bodySource] tells an empty-looking row that is subclassed from one
 * that is genuinely empty — the same distinction `get_trigger` makes, carried into the listing so
 * a scan of the rows does not have to call every one of them to find out.
 */
@Serializable
@SerialName("TriggerSummary")
public data class TriggerSummary(
    val name: String,
    val level: TriggerLevel,
    val block: String? = null,
    val item: String? = null,
    val firstLine: String = "",
    val lineCount: Int = 0,
    val bodySource: BodySource = BodySource.OWN,
)

/**
 * `list_triggers`. [total] counts the triggers matching the block/item/level filter; [truncated]
 * says the rows were cut at the cap — narrow the filter to see the rest.
 */
@Serializable
@SerialName("TriggerList")
public data class TriggerList(
    val module: ModuleKey,
    val total: Int = 0,
    val truncated: Boolean = false,
    val triggers: List<TriggerSummary> = emptyList(),
)

/**
 * `get_trigger` — the decoded PL/SQL body.
 *
 * [bodySource] qualifies [text]: an empty [text] beside `INHERITED` means the code lives in the
 * module [inherited] names, never that the trigger does nothing. [resolvedFrom] is set only when
 * the call followed the pointer (`resolve`), and [hint] names the exact next call whenever one is
 * needed.
 */
@Serializable
@SerialName("TriggerSource")
public data class TriggerSource(
    val module: ModuleKey,
    val name: String,
    val level: TriggerLevel,
    val block: String? = null,
    val item: String? = null,
    val text: String,
    val bodySource: BodySource = BodySource.OWN,
    val textEncoding: TextEncoding = TextEncoding.ORIGINAL,
    val source: SourceLocation? = null,
    val inherited: InheritanceRef? = null,
    val resolvedFrom: ModuleKey? = null,
    val hint: String? = null,
    val annotations: ElementAnnotations = ElementAnnotations(),
)

/** One row of `list_program_units`. */
@Serializable
@SerialName("ProgramUnitSummary")
public data class ProgramUnitSummary(
    val name: String,
    val unitType: ProgramUnitType,
    val lineCount: Int = 0,
)

/** `list_program_units`. [total] counts every unit; [truncated] says the rows were cut at the cap. */
@Serializable
@SerialName("ProgramUnitList")
public data class ProgramUnitList(
    val module: ModuleKey,
    val total: Int = 0,
    val truncated: Boolean = false,
    val units: List<ProgramUnitSummary> = emptyList(),
)

/** `get_program_unit` — the PL/SQL body. [bodySource] and friends as in [TriggerSource]. */
@Serializable
@SerialName("ProgramUnitSource")
public data class ProgramUnitSource(
    val module: ModuleKey,
    val name: String,
    val unitType: ProgramUnitType,
    val text: String,
    val bodySource: BodySource = BodySource.OWN,
    val textEncoding: TextEncoding = TextEncoding.ORIGINAL,
    val source: SourceLocation? = null,
    val inherited: InheritanceRef? = null,
    val resolvedFrom: ModuleKey? = null,
    val hint: String? = null,
    val annotations: ElementAnnotations = ElementAnnotations(),
)

/** One hit from `search_source`. */
@Serializable
@SerialName("SearchHit")
public data class SearchHit(
    val path: String,
    val line: Int,
    val snippet: String,
    /** Resource URI of [path], so a hit can be opened rather than only reported. */
    val uri: String? = null,
)

/**
 * How many of a `search_source` result's hits one file holds — across the whole result, not the
 * page.
 */
@Serializable
@SerialName("SearchFileCount")
public data class SearchFileCount(
    val path: String,
    val hits: Int = 0,
    /** Resource URI of [path], as on [SearchHit.uri]. */
    val uri: String? = null,
)

/**
 * `search_source`. [truncated] is `true` when more hits existed than this page carries; in that
 * case [nextOffset] is the `offset` to pass to fetch the next page, and [hint] says so in words.
 * [offset] echoes the page start.
 *
 * [total] counts every hit in the module, whichever page this is, and [files] says where they
 * are: one row per file with at least one hit, in path order. The counts are what a hit list
 * cannot show — whether an identifier appears anywhere past the page, and roughly where — so they
 * are computed over the whole result. [fileTotal] counts those files; [filesTruncated] says
 * [files] was cut to fit.
 */
@Serializable
@SerialName("SearchResults")
public data class SearchResults(
    val query: String,
    val hits: List<SearchHit> = emptyList(),
    val truncated: Boolean = false,
    val offset: Int = 0,
    val nextOffset: Int? = null,
    val total: Int = 0,
    val hint: String? = null,
    val fileTotal: Int = 0,
    val filesTruncated: Boolean = false,
    val files: List<SearchFileCount> = emptyList(),
)

/**
 * One hit from `search_modules`: a [SearchHit] plus the module the file belongs to.
 *
 * A cross-module hit is only actionable if it says which module it came from — every other tool
 * takes a module, and the same cache-relative [path] shape exists under every one of them. Hence
 * [moduleSpec] beside the canonical [module]: it is the flat `NAME.ext` string those tools accept,
 * so following a hit is a copy rather than a reassembly of the key's two fields.
 */
@Serializable
@SerialName("ModuleSearchHit")
public data class ModuleSearchHit(
    val module: ModuleKey,
    val moduleSpec: String = "",
    val path: String,
    val line: Int,
    val snippet: String,
    /** Resource URI of [path] in [module], so a hit can be opened rather than only reported. */
    val uri: String? = null,
)

/**
 * `search_modules` — hits across every **cached** module, plus what the scan could and could not
 * reach.
 *
 * The counts are part of the answer rather than decoration. A search that silently covered a tenth
 * of the forms directory reads exactly like one that found nothing, so: [cachedModules] is the
 * searchable universe after `modulePattern`, [scannedModules] what this call actually read, and
 * [skippedNotCached]/[skippedStale] the matching modules that could not be searched because they
 * were never fetched or have changed on disk since they were. [hint] names the call that fixes
 * each of those.
 *
 * [truncated] says work remains — either more hits than the result cap or more modules than one
 * call scans — and [nextCursor] is then the opaque token that resumes exactly where this call
 * stopped. It is bound to the query, scope and module pattern it was minted for, so it cannot
 * silently continue a different search.
 */
@Serializable
@SerialName("ModuleSearchResults")
public data class ModuleSearchResults(
    val query: String,
    val cachedModules: Int = 0,
    val scannedModules: Int = 0,
    val skippedNotCached: Int = 0,
    val skippedStale: Int = 0,
    val truncated: Boolean = false,
    val nextCursor: String? = null,
    val hint: String? = null,
    val hits: List<ModuleSearchHit> = emptyList(),
)

/**
 * `get_object_xml` — the raw XML fragment of one named object, sliced from the converted file by
 * its recorded line range. [truncated] flags a fragment cut at the response size cap, and [hint]
 * then names the `read_source` call that continues it.
 *
 * [inherited] answers the subclassing question *at this object's level*: Forms writes the parent
 * pointer on the enclosing owner, so the fragment of a subclassed item shows only
 * `SubclassSubObject="true"` and would otherwise leave the escape hatch unable to answer the
 * question it was called for.
 */
@Serializable
@SerialName("ObjectXml")
public data class ObjectXml(
    val module: ModuleKey,
    val objectType: String,
    val name: String,
    val ownerPath: String? = null,
    val xml: String,
    /** Where the fragment starts in the converted file. Superseded by [source], kept for callers. */
    val startLine: Int = 1,
    val truncated: Boolean = false,
    val source: SourceLocation? = null,
    val inherited: InheritanceRef? = null,
    val hint: String? = null,
    val annotations: ElementAnnotations = ElementAnnotations(),
)

/*
 * Annotation layer: AI/user-supplied meta-information persisted about elements. These are *claims*,
 * not parsed facts — every view carries [AnnotationView.author]/[AnnotationView.createdAt] and a
 * [AnnotationView.staleAgainstSource] flag set when the note predates the module's current source.
 */

/** One annotation as served back, with drift ([staleAgainstSource]) resolved against the source. */
@Serializable
@SerialName("AnnotationView")
public data class AnnotationView(
    val id: String = "",
    val target: ElementId? = null,
    val kind: AnnotationKind = AnnotationKind.NOTE,
    val body: String = "",
    val author: Author = Author.AI,
    val createdAt: String = "",
    val staleAgainstSource: Boolean = false,
)

/** One relation as served back; [staleAgainstSource] as in [AnnotationView]. */
@Serializable
@SerialName("RelationView")
public data class RelationView(
    val id: String = "",
    val from: ElementId? = null,
    val to: ElementId? = null,
    val relType: String = "",
    val note: String? = null,
    val author: Author = Author.AI,
    val createdAt: String = "",
    val staleAgainstSource: Boolean = false,
)

/** Notes and relations attached to one element; embedded (defaulted-empty) in the read DTOs. */
@Serializable
@SerialName("ElementAnnotations")
public data class ElementAnnotations(
    val notes: List<AnnotationView> = emptyList(),
    val relations: List<RelationView> = emptyList(),
)

/** `get_element_annotations` — the resolved [element] plus its [annotations]. */
@Serializable
@SerialName("ElementAnnotationList")
public data class ElementAnnotationList(
    val module: ModuleKey,
    val element: ElementId,
    val annotations: ElementAnnotations = ElementAnnotations(),
)

/** `annotate_element` — the annotation that was stored. */
@Serializable
@SerialName("AnnotationCreated")
public data class AnnotationCreated(
    val module: ModuleKey,
    val annotation: AnnotationView,
)

/** `relate_elements` — the relation that was stored. */
@Serializable
@SerialName("RelationCreated")
public data class RelationCreated(
    val module: ModuleKey,
    val relation: RelationView,
)

/** `remove_annotation` — [removed] is `false` when no annotation/relation had that [id]. */
@Serializable
@SerialName("AnnotationRemoved")
public data class AnnotationRemoved(
    val module: ModuleKey,
    val id: String,
    val removed: Boolean = false,
)

/**
 * `search_annotations` — matching notes and relations across one module. [truncated] says either
 * list was cut at the cap; narrow with `text`, `kind` or `tag` to see the rest.
 */
@Serializable
@SerialName("AnnotationSearchResults")
public data class AnnotationSearchResults(
    val module: ModuleKey,
    val totalNotes: Int = 0,
    val totalRelations: Int = 0,
    val truncated: Boolean = false,
    val notes: List<AnnotationView> = emptyList(),
    val relations: List<RelationView> = emptyList(),
)

/** The full annotation set for a module, exposed as the `oracleforms://{module}/annotations` resource. */
@Serializable
@SerialName("ModuleAnnotationsView")
public data class ModuleAnnotationsView(
    val module: ModuleKey,
    val notes: List<AnnotationView> = emptyList(),
    val relations: List<RelationView> = emptyList(),
)
