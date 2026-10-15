package app.oreshkov.oracleformsmcp.server.prompts

import app.oreshkov.oracleformsmcp.dto.BodySource
import app.oreshkov.oracleformsmcp.model.InheritanceRef
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.server.FormsService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/*
 * Caps on what the prompt embeds, so a generated form stays within context limits. Every capped
 * list says how much it left out and which tool lists the rest.
 */
private const val MAX_BLOCKS = 60
private const val MAX_ITEMS_PER_BLOCK = 25
private const val MAX_TRIGGERS = 150
private const val MAX_PROGRAM_UNITS = 150
private const val MAX_LIBRARIES = 40
private const val MAX_MENUS = 40
private const val MAX_MENU_ITEMS = 25

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
        GetPromptResult(
            description = "Explain the Forms module $key",
            messages = listOf(PromptMessage(role = Role.User, content = TextContent(explainModuleText(service, key)))),
        )
    }
}

/**
 * The prompt's text: the instructions, then the indexed structure they tell the model to rely on
 * "strictly". So the structure is held to what the tools are held to — a subclassed body is shown
 * as the pointer to where its code lives, never as an empty one, and every list is capped and says
 * what it left out.
 */
internal suspend fun explainModuleText(service: FormsService, key: ModuleKey): String {
    val index = service.index(key)

    val context = buildString {
        index.formsVersion?.let { appendLine("Forms version: $it") }
        if (index.blocks.isNotEmpty()) {
            appendLine("Blocks (name — base table — items):")
            index.blocks.take(MAX_BLOCKS).forEach { block ->
                val items = block.items.take(MAX_ITEMS_PER_BLOCK).joinToString(", ") { it.name } +
                    moreInline(block.items.size, MAX_ITEMS_PER_BLOCK, "get_block(module=\"$key\", block=\"${block.name}\")")
                appendLine("- ${block.name} — ${block.queryDataSourceName ?: "(control block)"} — $items")
            }
            appendOmitted(index.blocks.size, MAX_BLOCKS, "blocks", "list_blocks")
        }
        if (index.triggers.isNotEmpty()) {
            appendLine("Triggers (level scope name — first line):")
            index.triggers.take(MAX_TRIGGERS).forEach { trigger ->
                val scope = listOfNotNull(trigger.blockName, trigger.itemName).joinToString(".")
                    .ifEmpty { "form" }
                val body = when (service.bodySourceOf(trigger)) {
                    BodySource.INHERITED -> inheritedFrom(trigger.inherited!!, "get_trigger")
                    BodySource.EMPTY -> "(empty)"
                    else -> trigger.firstLine
                }
                appendLine("- ${trigger.level} $scope ${trigger.name} — $body")
            }
            appendOmitted(index.triggers.size, MAX_TRIGGERS, "triggers", "list_triggers")
        }
        if (index.programUnits.isNotEmpty()) {
            appendLine("Program units:")
            index.programUnits.take(MAX_PROGRAM_UNITS).forEach { unit ->
                val body = when (service.bodySourceOf(key, unit)) {
                    BodySource.INHERITED -> "— " + inheritedFrom(unit.inherited!!, "get_program_unit")
                    BodySource.EMPTY -> "(empty)"
                    else -> "(${unit.lineCount} lines)"
                }
                appendLine("- ${unit.unitType} ${unit.name} $body")
            }
            appendOmitted(index.programUnits.size, MAX_PROGRAM_UNITS, "program units", "list_program_units")
        }
        if (index.attachedLibraries.isNotEmpty()) {
            appendLine(
                "Attached libraries: " +
                    index.attachedLibraries.take(MAX_LIBRARIES).joinToString(", ") { it.name },
            )
            appendOmitted(index.attachedLibraries.size, MAX_LIBRARIES, "attached libraries", "get_module_overview")
        }
        if (index.menus.isNotEmpty()) {
            appendLine("Menus (name [items]):")
            index.menus.take(MAX_MENUS).forEach { menu ->
                val items = menu.items.take(MAX_MENU_ITEMS).joinToString(", ") { it.name } +
                    moreInline(menu.items.size, MAX_MENU_ITEMS, "get_object_xml(module=\"$key\", objectType=\"Menu\", name=\"${menu.name}\")")
                appendLine("- ${menu.name} [$items]")
            }
            appendOmitted(index.menus.size, MAX_MENUS, "menus", "get_module_overview")
        }
    }

    return """
        Explain what the Oracle Forms module $key does, for a developer seeing it
        for the first time. Describe the screens/blocks and the data they work on,
        the validation and business logic wired into triggers and program units,
        and how the pieces fit together. Base the explanation strictly on the
        indexed structure below; use get_trigger/get_program_unit for bodies you
        need to inspect. A body marked "inherited from" is not empty: its code
        lives in the module named, and the call shown reads it. A list that ends
        with "omitted" is cut; the tool it names lists the rest:
    """.trimIndent() + "\n\n" + context
}

/** Where a subclassed body's code lives, and the call that reads it — in the tool hints' terms. */
private fun inheritedFrom(ref: InheritanceRef, tool: String): String {
    val module = ref.module ?: ref.file ?: "a module this XML does not name"
    val where = ref.objectGroup?.let { "object group $it" } ?: ref.ownerPath
    return "inherited from $module" + (where?.let { " ($it)" } ?: "") +
        "; the code is there, not here — $tool(resolve=true) reads it"
}

/** `" … and K more — <call>"` for a list cut inside one line, or nothing when it was not cut. */
private fun moreInline(total: Int, cap: Int, call: String): String =
    if (total > cap) " … and ${total - cap} more — $call" else ""

private fun StringBuilder.appendOmitted(total: Int, cap: Int, what: String, tool: String) {
    if (total > cap) appendLine("(${total - cap} more $what omitted — use $tool.)")
}
