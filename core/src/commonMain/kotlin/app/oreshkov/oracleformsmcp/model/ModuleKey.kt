package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identity of one Forms module: uppercase module name plus its [ModuleType], e.g. `ORDERS.fmb`.
 *
 * Forms module names are case-insensitive, so [name] is normalized to uppercase (construct via
 * [of]/[parse]). The type is part of the key because the same name can exist as several kinds
 * (an `ORDERS.fmb` and an `ORDERS.pll` are different modules). Primary cache key throughout.
 */
@Serializable
@SerialName("ModuleKey")
public data class ModuleKey(
    val name: String,
    val type: ModuleType,
) {
    init {
        require(name.isNotBlank()) { "module name must not be blank" }
        // The canonical form becomes a cache directory name, so separators/traversal are invalid.
        require(name.none { it == '/' || it == '\\' || it == ':' } && !name.contains("..")) {
            "module name '$name' must not contain path separators or '..'"
        }
    }

    /** Canonical `NAME.ext` form, e.g. `ORDERS.fmb`. */
    override fun toString(): String = "$name.${type.extension}"

    public companion object {
        /** Builds a key with the name normalized to uppercase. */
        public fun of(name: String, type: ModuleType): ModuleKey =
            ModuleKey(name.trim().uppercase(), type)

        /**
         * Parses a `name.ext` string such as `orders.fmb`.
         *
         * @throws IllegalArgumentException when there is no `.ext` part or the extension is not
         *   one of fmb/mmb/pll/olb.
         */
        public fun parse(spec: String): ModuleKey {
            val dot = spec.lastIndexOf('.')
            require(dot > 0 && dot < spec.length - 1) {
                "Invalid module spec '$spec': expected 'name.ext' with ext one of " +
                    ModuleType.entries.joinToString("/") { it.extension }
            }
            val type = ModuleType.fromExtension(spec.substring(dot + 1))
                ?: throw IllegalArgumentException(
                    "Invalid module spec '$spec': unknown extension '${spec.substring(dot + 1)}'",
                )
            return of(spec.substring(0, dot), type)
        }

        /** Like [parse], but returns `null` instead of throwing on an invalid form. */
        public fun parseOrNull(spec: String): ModuleKey? =
            runCatching { parse(spec) }.getOrNull()
    }
}
