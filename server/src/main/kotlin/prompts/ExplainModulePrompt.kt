package app.oreshkov.oracleformsmcp.server.prompts

import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/** Cap on the trigger lines embedded in the prompt so huge forms stay within context limits. */
private const val MAX_TRIGGERS = 150

/**
 * `explain_module` prompt: asks the model for a functional summary of a fetched module, with the
 * cached structure (blocks, base tables, triggers, program units) embedded as concrete context.
 */
fun Server.registerExplainModulePrompt(service: FormsService) {
    addPrompt(
        name = "explain_module",
        description = "Explain what a fetched Oracle Forms module does, grounded in its indexed " +
            "blocks, base tables, triggers, and program units.",
        arguments = listOf(
            PromptArgument(
                name = "module",
                description = "Module name, optionally with extension (must be fetched already)",
                required = true,
            ),
        ),
    ) { request ->
        val spec = request.arguments?.get("module")
            ?: throw IllegalArgumentException("Missing required prompt argument 'module'")
        val key = service.resolveModule(spec)
        val index = service.index(key)

        val context = buildString {
            index.formsVersion?.let { appendLine("Forms version: $it") }
            if (index.blocks.isNotEmpty()) {
                appendLine("Blocks (name — base table — items):")
                index.blocks.forEach { block ->
                    appendLine(
                        "- ${block.name} — ${block.queryDataSourceName ?: "(control block)"} — " +
                            block.items.joinToString(", ") { it.name },
                    )
                }
            }
            if (index.triggers.isNotEmpty()) {
                appendLine("Triggers (level scope name — first line):")
                index.triggers.take(MAX_TRIGGERS).forEach { trigger ->
                    val scope = listOfNotNull(trigger.blockName, trigger.itemName).joinToString(".")
                        .ifEmpty { "form" }
                    appendLine("- ${trigger.level} $scope ${trigger.name} — ${trigger.firstLine}")
                }
                if (index.triggers.size > MAX_TRIGGERS) {
                    appendLine("(${index.triggers.size - MAX_TRIGGERS} more triggers omitted — use list_triggers.)")
                }
            }
            if (index.programUnits.isNotEmpty()) {
                appendLine("Program units:")
                index.programUnits.forEach { appendLine("- ${it.unitType} ${it.name} (${it.lineCount} lines)") }
            }
            if (index.attachedLibraries.isNotEmpty()) {
                appendLine("Attached libraries: " + index.attachedLibraries.joinToString(", ") { it.name })
            }
            if (index.menus.isNotEmpty()) {
                appendLine("Menus: " + index.menus.joinToString(", ") { menu ->
                    "${menu.name} [${menu.items.joinToString(", ") { it.name }}]"
                })
            }
        }

        GetPromptResult(
            description = "Explain the Forms module $key",
            messages = listOf(
                PromptMessage(
                    role = Role.User,
                    content = TextContent(
                        """
                        Explain what the Oracle Forms module $key does, for a developer seeing it
                        for the first time. Describe the screens/blocks and the data they work on,
                        the validation and business logic wired into triggers and program units,
                        and how the pieces fit together. Base the explanation strictly on the
                        indexed structure below; use get_trigger/get_program_unit for bodies you
                        need to inspect:
                        """.trimIndent() + "\n\n" + context,
                    ),
                )
            ),
        )
    }
}
