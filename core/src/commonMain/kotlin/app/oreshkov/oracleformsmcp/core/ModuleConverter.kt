package app.oreshkov.oracleformsmcp.core

import app.oreshkov.oracleformsmcp.model.ModuleKey

/**
 * Port that produces the text form of a binary Forms module inside the cache.
 *
 * Two implementations: `OracleToolsModuleConverter` shells out to `frmf2xml`/`frmcmp` when
 * `ORACLE_HOME` is set; `PreConvertedCopyConverter` copies an already-converted sibling file from
 * the forms directory when it is not.
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
}
