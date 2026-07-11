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
    val textRef: SourceRef? = null,
    val xmlRef: SourceRef? = null,
)
