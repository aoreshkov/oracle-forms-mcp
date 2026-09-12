package app.oreshkov.oracleformsmcp.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shape and meaning of what the parser writes into a [ModuleIndex].
 *
 * A cache entry survives a server upgrade — nothing about a new build changes the `.fmb` on disk,
 * so the source fingerprint still matches and the entry stays warm. That is correct only while the
 * parser would write the same facts for it. When it would not, the entry is an *older answer*,
 * indistinguishable from a current one unless it says which build wrote it: v0.8.0 indexes served
 * by v0.9.0 reported subclassed triggers as empty, one-line bodies for whole procedures, and no
 * property classes, months after each of those was fixed.
 *
 * **Bump this whenever the parser changes what it writes or what a written field means** — every
 * one of those three fixes would have required it. An entry stamped with anything else is reported
 * stale and re-parsed (no conversion: see `FormsService.fetchModule`).
 *
 * `0` is the implicit version of every entry written before the stamp existed, which is exactly
 * what the default on [ModuleIndex.indexVersion] gives them.
 */
public const val CURRENT_INDEX_VERSION: Int = 1

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
 * file now on disk marks this entry stale. [indexVersion] identifies the parser that wrote it; a
 * mismatch against [CURRENT_INDEX_VERSION] marks it stale too, for the other reason — the file is
 * unchanged but the answers are older than the build serving them. [parsedAt] uses the stdlib
 * [Instant].
 */
@Serializable
@SerialName("ModuleIndex")
public data class ModuleIndex(
    val key: ModuleKey,
    val formsVersion: String? = null,
    val indexVersion: Int = 0,
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
