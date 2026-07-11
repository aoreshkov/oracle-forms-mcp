package app.oreshkov.oracleformsmcp.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parsed, cacheable index of one Forms module that MCP tools read.
 *
 * Built once per [key] from the converted text form and persisted as JSON in the cache, so tool
 * calls answer without re-running conversion or parsing. One unified shape for all four
 * [ModuleType]s with empty-defaulted sections (a menu has no blocks, a library only
 * [programUnits]) — a flat class keeps the derived tool output schemas precise, unlike a sealed
 * hierarchy.
 *
 * PL/SQL bodies are deliberately NOT stored here: they live in sidecar files under `plsql` (or
 * the `.pld` itself) referenced by [SourceRef]s, keeping the index small for even the largest
 * forms.
 *
 * [fingerprint] identifies the exact source file consumed ([sourceFile]); a mismatch against the
 * file now on disk marks this entry stale. [parsedAt] uses the stdlib [Instant].
 */
@Serializable
@SerialName("ModuleIndex")
public data class ModuleIndex(
    val key: ModuleKey,
    val formsVersion: String? = null,
    val sourceFile: String,
    val fingerprint: ModuleFingerprint,
    val convertedFile: String,
    val parsedAt: Instant,
    val blocks: List<BlockInfo> = emptyList(),
    val triggers: List<TriggerInfo> = emptyList(),
    val programUnits: List<ProgramUnitInfo> = emptyList(),
    val attachedLibraries: List<AttachedLibraryInfo> = emptyList(),
    val lovs: List<LovInfo> = emptyList(),
    val recordGroups: List<RecordGroupInfo> = emptyList(),
    val windows: List<WindowInfo> = emptyList(),
    val canvases: List<CanvasInfo> = emptyList(),
    val alerts: List<AlertInfo> = emptyList(),
    val parameters: List<ParameterInfo> = emptyList(),
    val visualAttributes: List<String> = emptyList(),
    val propertyClasses: List<String> = emptyList(),
    val editors: List<String> = emptyList(),
    val menus: List<MenuInfo> = emptyList(),
    val objectLibraryTabs: List<ObjectLibraryTabInfo> = emptyList(),
    val objectRefs: List<ObjectRef> = emptyList(),
)
