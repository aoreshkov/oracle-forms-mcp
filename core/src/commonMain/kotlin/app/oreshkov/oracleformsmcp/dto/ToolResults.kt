package app.oreshkov.oracleformsmcp.dto

import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.model.Author
import app.oreshkov.oracleformsmcp.model.BlockInfo
import app.oreshkov.oracleformsmcp.model.CanvasInfo
import app.oreshkov.oracleformsmcp.model.ElementId
import app.oreshkov.oracleformsmcp.model.InheritanceRef
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
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
 */
@Serializable
@SerialName("SourceText")
public data class SourceText(
    val module: ModuleKey,
    val source: SourceLocation = SourceLocation(),
    val totalLines: Int = 0,
    val truncated: Boolean = false,
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
 * One row of `list_modules`. [name] is the flat identifier to match and reason about; [module]
 * carries the same name with its type as the canonical key every other tool takes.
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
    val modules: List<ModuleStatusEntry> = emptyList(),
)

/** `fetch_module` — summary of a converted-and-indexed module. */
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
 */
@Serializable
@SerialName("BlockDetail")
public data class BlockDetail(
    val module: ModuleKey,
    val block: BlockInfo,
    val source: SourceLocation? = null,
    val hint: String? = null,
    val annotations: ElementAnnotations = ElementAnnotations(),
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
 * `search_source`. [truncated] is `true` when more hits existed than the page cap; in that case
 * [nextOffset] is the `offset` to pass to fetch the next page. [offset] echoes the page start.
 */
@Serializable
@SerialName("SearchResults")
public data class SearchResults(
    val query: String,
    val hits: List<SearchHit> = emptyList(),
    val truncated: Boolean = false,
    val offset: Int = 0,
    val nextOffset: Int? = null,
)

/**
 * `get_object_xml` — the raw XML fragment of one named object, sliced from the converted file by
 * its recorded line range. [truncated] flags a fragment cut at the response size cap.
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
