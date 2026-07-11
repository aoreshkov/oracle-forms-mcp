package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The PL/SQL unit kinds Forms distinguishes (`ProgramUnitType` attribute / `.pld` headers). */
@Serializable
@SerialName("ProgramUnitType")
public enum class ProgramUnitType {
    PROCEDURE,
    FUNCTION,
    PACKAGE_SPEC,
    PACKAGE_BODY,
    UNKNOWN,
    ;

    public companion object {
        /** Maps Forms2XML attribute values (`Procedure`, `Package Spec`, …) or `.pld` headers. */
        public fun fromForms(value: String): ProgramUnitType = when (value.trim().uppercase()) {
            "PROCEDURE" -> PROCEDURE
            "FUNCTION" -> FUNCTION
            "PACKAGE SPEC", "PACKAGE SPECIFICATION", "PACKAGE" -> PACKAGE_SPEC
            "PACKAGE BODY" -> PACKAGE_BODY
            else -> UNKNOWN
        }
    }
}

/**
 * One program unit of a module. For XML modules [textRef] points at the extracted `.sql`
 * sidecar under `plsql/program-units`; for `.pll` libraries it is a line range into the `.pld`
 * dump itself (which is already plain PL/SQL).
 */
@Serializable
@SerialName("ProgramUnitInfo")
public data class ProgramUnitInfo(
    val name: String,
    val unitType: ProgramUnitType = ProgramUnitType.UNKNOWN,
    val lineCount: Int = 0,
    val textRef: SourceRef? = null,
    val xmlRef: SourceRef? = null,
)
