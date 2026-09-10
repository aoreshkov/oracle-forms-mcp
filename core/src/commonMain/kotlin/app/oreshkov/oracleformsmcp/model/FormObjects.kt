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

/**
 * A window. [modal] is the property that decides how a form is read — a modal window is a dialog
 * the user cannot leave, so the code that fills it and the code that reads the result sit on
 * opposite sides of one interaction — and it lived only in the raw XML until now.
 *
 * The toolbar canvas names matter for the same reason: a window's toolbar is usually subclassed
 * from elsewhere, so they are the thread from a window to the block that carries its buttons.
 * Every property is `null` when the converted file did not write it, meaning the Forms default.
 */
@Serializable
@SerialName("WindowInfo")
public data class WindowInfo(
    val name: String,
    val title: String? = null,
    val modal: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val horizontalToolbarCanvasName: String? = null,
    val verticalToolbarCanvasName: String? = null,
)

/**
 * A canvas and the window it displays on. [raiseOnEnter] says the canvas comes to the front when
 * navigation enters it, which is how stacked canvases are switched without any code saying so.
 * The viewport is the part actually visible in the window, as opposed to the canvas's own size.
 * `null` means the converted file did not write the property, i.e. the Forms default.
 */
@Serializable
@SerialName("CanvasInfo")
public data class CanvasInfo(
    val name: String,
    val canvasType: String? = null,
    val windowName: String? = null,
    val propertyClass: String? = null,
    val raiseOnEnter: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
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
