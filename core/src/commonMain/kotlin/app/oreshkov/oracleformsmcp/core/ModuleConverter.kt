package app.oreshkov.oracleformsmcp.core

import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType

/**
 * Port that produces the text form of a binary Forms module inside the cache.
 *
 * `OracleToolsModuleConverter` shells out to `frmf2xml`/`frmcmp` when `ORACLE_HOME` is set,
 * `CustomCommandModuleConverter` runs a site-supplied command line instead, and
 * `PreConvertedCopyConverter` copies an already-converted sibling file from the forms directory.
 * `ModuleConverters.forEnvironment` picks among them — per module type, when `.pll` libraries are
 * given a command of their own.
 */
public interface ModuleConverter {

    /**
     * Converts (or copies) [sourcePath] into [targetDir] and returns the absolute path of the
     * produced text file (`*_fmb.xml`, `*_mmb.xml`, `*_olb.xml`, or `*.pld`).
     *
     * @throws ConversionException when the module cannot be converted; messages are written to
     *   tell the model (or user) how to fix the situation.
     */
    public suspend fun convert(key: ModuleKey, sourcePath: String, targetDir: String): String

    /** Human-readable description surfaced in fetch summaries and logs. */
    public val description: String

    /**
     * Whether [convert] turns a *binary* module of [type] into its text form, rather than only
     * copying one that was converted elsewhere.
     *
     * This decides which file the pipeline consumes for a module that has both — and therefore
     * which file its cache entry is fingerprinted against. It is a per-type question because one
     * configuration can convert `.pll` libraries while serving forms from pre-converted XML.
     */
    public fun convertsBinary(type: ModuleType): Boolean = false
}
