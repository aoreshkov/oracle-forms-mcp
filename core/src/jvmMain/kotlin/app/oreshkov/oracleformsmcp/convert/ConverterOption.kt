package app.oreshkov.oracleformsmcp.convert

/**
 * The operator option a command line came from, so a message about a broken command names the
 * knob that fixes it — and says what happens when that knob is dropped, which differs: without
 * `--compile-command` a `.pll` still has three places to go.
 */
internal enum class ConverterOption(
    val flag: String,
    val envVar: String,
    /** What dropping the option falls back to, phrased to follow "drop the flag to …". */
    val fallback: String,
) {
    CONVERT(
        flag = "--convert-command",
        envVar = "OFMCP_CONVERT_COMMAND",
        fallback = "fall back to ORACLE_HOME (or to pre-converted files next to the modules)",
    ),
    COMPILE(
        flag = "--compile-command",
        envVar = "OFMCP_COMPILE_COMMAND",
        fallback = "convert .pll libraries with --convert-command, else ORACLE_HOME, else " +
            "pre-converted .pld files next to the modules",
    ),
}
