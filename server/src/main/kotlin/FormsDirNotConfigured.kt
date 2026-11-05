package app.oreshkov.oracleformsmcp.server

/**
 * What every tool answers while no forms directory is configured.
 *
 * Written for the model, which has to pass it on: the fix is the user's (a setting and a restart),
 * so it names the setting in each spelling a channel uses and says that no call fixes it. It also
 * says what the error is *not* — an empty answer, or one an agent could take for "there are no
 * Forms modules here", would be a wrong conclusion that reads as a right one.
 */
internal val FORMS_DIR_NOT_CONFIGURED: String =
    "No forms directory is configured, so this server has nothing to serve yet. This is not an " +
        "empty directory, and it says nothing about whether Forms modules exist. Ask the user to " +
        "set $ENV_FORMS_DIR to the directory holding their .fmb/.mmb/.pll/.olb modules (or to " +
        "start the server with --forms-dir <path>; in the Claude Code plugin or the desktop bundle " +
        "it is the forms_dir setting), then restart the server. No tool call can fix this."

/** Thrown by [FormsService] for every operation while it has no forms directory. */
internal class FormsDirNotConfiguredException : IllegalStateException(FORMS_DIR_NOT_CONFIGURED)
