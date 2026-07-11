package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The four Oracle Forms module kinds this server understands.
 *
 * [extension] is the binary file extension in the forms directory; [convertedSuffix] is the
 * filename suffix of the text form produced by the Oracle tools (`frmf2xml` for XML kinds,
 * `frmcmp Script=YES` for [LIBRARY]'s `.pld` dump) and expected when running without
 * `ORACLE_HOME`.
 */
@Serializable
@SerialName("ModuleType")
public enum class ModuleType(
    public val extension: String,
    public val convertedSuffix: String,
) {
    FORM("fmb", "_fmb.xml"),
    MENU("mmb", "_mmb.xml"),
    LIBRARY("pll", ".pld"),
    OBJECT_LIBRARY("olb", "_olb.xml"),
    ;

    public companion object {
        /** The type whose binary [extension] matches (case-insensitive), or `null`. */
        public fun fromExtension(extension: String): ModuleType? =
            entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) }

        /**
         * Matches a pre-converted file name like `orders_fmb.xml` or `utils.pld` and returns the
         * module name (uppercased) plus its type, or `null` when [fileName] is no converted form.
         */
        public fun matchConverted(fileName: String): Pair<String, ModuleType>? {
            for (type in entries) {
                if (fileName.endsWith(type.convertedSuffix, ignoreCase = true)) {
                    val base = fileName.dropLast(type.convertedSuffix.length)
                    if (base.isNotBlank()) return base.uppercase() to type
                }
            }
            return null
        }
    }
}
