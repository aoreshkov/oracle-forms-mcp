package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kinds of Forms element an [ElementId] can address. Mirrors the sections of a [ModuleIndex]
 * so an annotation target can be validated against what the parser actually found. [OBJECT] is the
 * catch-all for any named XML object exposed through `objectRefs` (and `get_object_xml`).
 */
@Serializable
@SerialName("ElementKind")
public enum class ElementKind {
    MODULE,
    BLOCK,
    ITEM,
    TRIGGER,
    PROGRAM_UNIT,
    LOV,
    RECORD_GROUP,
    CANVAS,
    WINDOW,
    ALERT,
    PARAMETER,
    MENU,
    MENU_ITEM,
    OBJECT,
}
