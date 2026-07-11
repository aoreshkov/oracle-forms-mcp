package app.oreshkov.oracleformsmcp.dto

import app.oreshkov.oracleformsmcp.model.BlockInfo
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
)

/** One hit from `search_source`. */
@Serializable
@SerialName("SearchHit")
public data class SearchHit(
    val path: String,
    val line: Int,
    val snippet: String,
)

/** `search_source`. [truncated] is `true` when more hits existed than the bounded result cap. */
@Serializable
@SerialName("SearchResults")
public data class SearchResults(
    val query: String,
    val hits: List<SearchHit> = emptyList(),
    val truncated: Boolean = false,
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
)
