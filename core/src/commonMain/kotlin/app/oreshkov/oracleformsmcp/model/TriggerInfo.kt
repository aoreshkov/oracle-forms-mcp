package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a trigger is attached, derived from its position in the XML element stack. */
@Serializable
@SerialName("TriggerLevel")
public enum class TriggerLevel {
    FORM,
    BLOCK,
    ITEM,
    MENU,
}

/**
 * One trigger of a module. [textRef] points at the decoded PL/SQL (a `.sql` sidecar under
 * `plsql/triggers` written during parsing); [xmlRef] at the trigger's element in the converted
 * XML.
 * [firstLine] is a trimmed one-line preview so listings are readable without fetching the body.
 *
 * [inherited] is set when the trigger is subclassed: the body is empty *here* while the code that
 * actually runs lives in the parent module. This is the one case where an empty body must never
 * be served without the pointer beside it — on its own it reads as "this trigger does nothing".
 */
@Serializable
@SerialName("TriggerInfo")
public data class TriggerInfo(
    val name: String,
    val level: TriggerLevel,
    val blockName: String? = null,
    val itemName: String? = null,
    val firstLine: String = "",
    val lineCount: Int = 0,
    val inherited: InheritanceRef? = null,
    val textRef: SourceRef? = null,
    val xmlRef: SourceRef? = null,
)
