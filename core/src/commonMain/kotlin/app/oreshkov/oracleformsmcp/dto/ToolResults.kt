package app.oreshkov.oracleformsmcp.dto

import app.oreshkov.oracleformsmcp.model.AnnotationKind
import app.oreshkov.oracleformsmcp.model.Author
import app.oreshkov.oracleformsmcp.model.BlockInfo
import app.oreshkov.oracleformsmcp.model.ElementId
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleStatus
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
import app.oreshkov.oracleformsmcp.model.TriggerLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Response shapes returned by the MCP tools, shared with the `server` module so tools just
 * serialize a value object instead of hand-rolling JSON. All read-only collections with defaults
 * so the wire format stays forward-compatible as fields are added.
 */

/** One row of `list_modules`. */
@Serializable
@SerialName("ModuleStatusEntry")
public data class ModuleStatusEntry(
    val module: ModuleKey,
    val type: ModuleType,
    val path: String? = null,
    val sizeBytes: Long? = null,
    val lastModified: String? = null,
    val status: ModuleStatus,
    val hasPreConverted: Boolean = false,
)

/** `list_modules`. */
@Serializable
@SerialName("ModuleList")
public data class ModuleList(
    val formsDir: String,
    val oracleHomeConversion: Boolean = false,
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
)

/** `get_module_overview` — counts plus the names of every section. */
@Serializable
@SerialName("ModuleOverview")
public data class ModuleOverview(
    val module: ModuleKey,
    val formsVersion: String? = null,
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
    val annotations: ElementAnnotations = ElementAnnotations(),
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

/** `list_blocks`. */
@Serializable
@SerialName("BlockList")
public data class BlockList(
    val module: ModuleKey,
    val blocks: List<BlockSummary> = emptyList(),
)

/** `get_block` — the full block including its items and trigger names. */
@Serializable
@SerialName("BlockDetail")
public data class BlockDetail(
    val module: ModuleKey,
    val block: BlockInfo,
    val annotations: ElementAnnotations = ElementAnnotations(),
)

/** One row of `list_triggers`. */
@Serializable
@SerialName("TriggerSummary")
public data class TriggerSummary(
    val name: String,
    val level: TriggerLevel,
    val block: String? = null,
    val item: String? = null,
    val firstLine: String = "",
    val lineCount: Int = 0,
)

/** `list_triggers`. */
@Serializable
@SerialName("TriggerList")
public data class TriggerList(
    val module: ModuleKey,
    val triggers: List<TriggerSummary> = emptyList(),
)

/** `get_trigger` — the decoded PL/SQL body. */
@Serializable
@SerialName("TriggerSource")
public data class TriggerSource(
    val module: ModuleKey,
    val name: String,
    val level: TriggerLevel,
    val block: String? = null,
    val item: String? = null,
    val text: String,
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

/** `list_program_units`. */
@Serializable
@SerialName("ProgramUnitList")
public data class ProgramUnitList(
    val module: ModuleKey,
    val units: List<ProgramUnitSummary> = emptyList(),
)

/** `get_program_unit` — the PL/SQL body. */
@Serializable
@SerialName("ProgramUnitSource")
public data class ProgramUnitSource(
    val module: ModuleKey,
    val name: String,
    val unitType: ProgramUnitType,
    val text: String,
    val annotations: ElementAnnotations = ElementAnnotations(),
)

/** One hit from `search_source`. */
@Serializable
@SerialName("SearchHit")
public data class SearchHit(
    val path: String,
    val line: Int,
    val snippet: String,
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
 */
@Serializable
@SerialName("ObjectXml")
public data class ObjectXml(
    val module: ModuleKey,
    val objectType: String,
    val name: String,
    val ownerPath: String? = null,
    val xml: String,
    val startLine: Int = 1,
    val truncated: Boolean = false,
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

/** `search_annotations` — matching notes and relations across one module. */
@Serializable
@SerialName("AnnotationSearchResults")
public data class AnnotationSearchResults(
    val module: ModuleKey,
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
