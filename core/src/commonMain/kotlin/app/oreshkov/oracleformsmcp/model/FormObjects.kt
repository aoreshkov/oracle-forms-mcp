package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Lean summaries of the remaining named form objects. Only the properties a model typically
 * needs to orient itself are indexed; everything else stays reachable through `get_object_xml`
 * via the ObjectRef recorded for every named element.
 */

/** A list of values. */
@Serializable
@SerialName("LovInfo")
public data class LovInfo(
    val name: String,
    val recordGroupName: String? = null,
    val columnMappings: List<String> = emptyList(),
)

/** A record group; [queryText] is present for query-based groups. */
@Serializable
@SerialName("RecordGroupInfo")
public data class RecordGroupInfo(
    val name: String,
    val queryText: String? = null,
    val columns: List<String> = emptyList(),
)

/** A window. */
@Serializable
@SerialName("WindowInfo")
public data class WindowInfo(
    val name: String,
    val title: String? = null,
)

/** A canvas and the window it displays on. */
@Serializable
@SerialName("CanvasInfo")
public data class CanvasInfo(
    val name: String,
    val canvasType: String? = null,
    val windowName: String? = null,
)

/** An alert dialog. */
@Serializable
@SerialName("AlertInfo")
public data class AlertInfo(
    val name: String,
    val message: String? = null,
)

/** A module parameter. */
@Serializable
@SerialName("ParameterInfo")
public data class ParameterInfo(
    val name: String,
    val dataType: String? = null,
    val defaultValue: String? = null,
)

/** A PL/SQL library attachment (`.pll` referenced by this module). */
@Serializable
@SerialName("AttachedLibraryInfo")
public data class AttachedLibraryInfo(
    val name: String,
    val libraryLocation: String? = null,
)
