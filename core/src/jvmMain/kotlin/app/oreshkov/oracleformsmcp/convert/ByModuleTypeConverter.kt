package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.core.ModuleConverter
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType

/**
 * Routes each module to the converter configured for its [ModuleType], and every other type to
 * [fallback] — how `--compile-command` gives `.pll` libraries a command of their own without the
 * forms, menus, and object libraries losing whichever converter they would have had anyway.
 *
 * Every question is answered by the delegate, including [convertsBinary]: whether a module's binary
 * or its pre-converted text form is consumed (and fingerprinted) depends on which converter it
 * reaches, not on the configuration as a whole.
 */
internal class ByModuleTypeConverter(
    private val overrides: Map<ModuleType, ModuleConverter>,
    private val fallback: ModuleConverter,
) : ModuleConverter {

    init {
        require(overrides.isNotEmpty()) { "no per-type converter given; use the fallback directly" }
    }

    /** The converter a module of [type] reaches. */
    fun delegateFor(type: ModuleType): ModuleConverter = overrides[type] ?: fallback

    override val description: String =
        fallback.description + overrides.entries.joinToString("") { (type, converter) ->
            "; .${type.extension}: ${converter.description}"
        }

    override fun convertsBinary(type: ModuleType): Boolean = delegateFor(type).convertsBinary(type)

    override fun conversionCaveat(type: ModuleType): String? = delegateFor(type).conversionCaveat(type)

    override suspend fun convert(key: ModuleKey, sourcePath: String, targetDir: String): String =
        delegateFor(key.type).convert(key, sourcePath, targetDir)
}
