package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.model.AlertInfo
import app.oreshkov.oracleformsmcp.model.AttachedLibraryInfo
import app.oreshkov.oracleformsmcp.model.BlockInfo
import app.oreshkov.oracleformsmcp.model.CanvasInfo
import app.oreshkov.oracleformsmcp.model.ItemInfo
import app.oreshkov.oracleformsmcp.model.LovInfo
import app.oreshkov.oracleformsmcp.model.MenuInfo
import app.oreshkov.oracleformsmcp.model.MenuItemInfo
import app.oreshkov.oracleformsmcp.model.ModuleFingerprint
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ObjectLibraryEntry
import app.oreshkov.oracleformsmcp.model.ObjectLibraryTabInfo
import app.oreshkov.oracleformsmcp.model.ObjectRef
import app.oreshkov.oracleformsmcp.model.ParameterInfo
import app.oreshkov.oracleformsmcp.model.ProgramUnitInfo
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
import app.oreshkov.oracleformsmcp.model.RecordGroupInfo
import app.oreshkov.oracleformsmcp.model.SourceRef
import app.oreshkov.oracleformsmcp.model.TriggerInfo
import app.oreshkov.oracleformsmcp.model.TriggerLevel
import app.oreshkov.oracleformsmcp.model.WindowInfo
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.io.path.inputStream
import kotlin.time.Clock

/**
 * Single-pass StAX parser for Forms2XML output (`*_fmb.xml`, `*_mmb.xml`, `*_olb.xml`).
 *
 * Streams with O(element) memory, so multi-MB forms parse without materializing a tree. Unknown
 * elements are skipped generically — the Forms vocabulary is huge and version-dependent, and this
 * parser must never fail on it. PL/SQL attribute values (already entity-decoded by StAX) are
 * written to sidecar files as they are seen; every named element gets an [ObjectRef] with a line
 * range into the XML so `get_object_xml` can slice it back out.
 *
 * Line semantics: StAX reports an event's *end* location, so an element's start line is taken
 * from where the previous event ended (see [startLineOf]) — exact for the one-element-per-line
 * layout Forms2XML writes, and at worst one line early. Pinned by FormsXmlParserTest.
 */
internal object FormsXmlParser {

    private class Frame(
        val element: String,
        val name: String?,
        val startLine: Int,
        val ownerPath: String?,
    )

    private class BlockBuilder(val name: String, val queryDataSourceName: String?) {
        val items = mutableListOf<ItemInfo>()
        val triggerNames = mutableListOf<String>()
    }

    private class ItemBuilder(
        val name: String,
        val itemType: String?,
        val dataType: String?,
        val columnName: String?,
        val canvasName: String?,
        val prompt: String?,
    ) {
        val triggerNames = mutableListOf<String>()
    }

    fun parse(key: ModuleKey, convertedFile: Path, moduleCacheDir: Path): ModuleIndex {
        val sidecars = PlsqlSidecars(moduleCacheDir)
        val xmlPath = cacheRelative(convertedFile, moduleCacheDir)

        var formsVersion: String? = null
        val blocks = mutableListOf<BlockInfo>()
        val triggers = mutableListOf<TriggerInfo>()
        val programUnits = mutableListOf<ProgramUnitInfo>()
        val attachedLibraries = mutableListOf<AttachedLibraryInfo>()
        val lovs = mutableListOf<LovInfo>()
        val recordGroups = mutableListOf<RecordGroupInfo>()
        val windows = mutableListOf<WindowInfo>()
        val canvases = mutableListOf<CanvasInfo>()
        val alerts = mutableListOf<AlertInfo>()
        val parameters = mutableListOf<ParameterInfo>()
        val visualAttributes = mutableListOf<String>()
        val propertyClasses = mutableListOf<String>()
        val editors = mutableListOf<String>()
        val menus = mutableListOf<MenuInfo>()
        val objectLibraryTabs = mutableListOf<ObjectLibraryTabInfo>()
        val objectRefs = mutableListOf<ObjectRef>()

        var block: BlockBuilder? = null
        var item: ItemBuilder? = null
        var lov: LovInfo? = null
        var recordGroup: RecordGroupInfo? = null
        var menu: MenuInfo? = null
        var tab: ObjectLibraryTabInfo? = null

        val stack = ArrayDeque<Frame>()
        var prevEventEndLine = 1

        val reader = xmlInputFactory().createXMLStreamReader(convertedFile.inputStream())
        try {
            while (reader.hasNext()) {
                reader.next()
                val eventEndLine = reader.location.lineNumber.coerceAtLeast(1)
                when (reader.eventType) {
                    XMLStreamConstants.START_ELEMENT -> {
                        val element = reader.localName
                        val name = reader.attr("Name")
                        val startLine = startLineOf(prevEventEndLine, eventEndLine)
                        val parent = stack.lastOrNull()
                        val ownerPath = ownerPathOf(stack)
                        stack.addLast(Frame(element, name, startLine, ownerPath))

                        when (element) {
                            "Module" -> formsVersion = reader.attr("version")

                            "Block" -> if (parent?.element == "FormModule") {
                                block = BlockBuilder(name ?: "", reader.attr("QueryDataSourceName"))
                            }

                            "Item" -> if (block != null && parent?.element == "Block") {
                                item = ItemBuilder(
                                    name = name ?: "",
                                    itemType = reader.attr("ItemType"),
                                    dataType = reader.attr("DataType"),
                                    columnName = reader.attr("ColumnName"),
                                    canvasName = reader.attr("CanvasName"),
                                    prompt = reader.attr("Prompt"),
                                )
                            }

                            "Trigger" -> {
                                val text = reader.attr("TriggerText").orEmpty()
                                val level = when (parent?.element) {
                                    "Block" -> TriggerLevel.BLOCK
                                    "Item" -> TriggerLevel.ITEM
                                    "Menu", "MenuItem" -> TriggerLevel.MENU
                                    else -> TriggerLevel.FORM
                                }
                                val triggerName = name ?: ""
                                val scope = ownerPath ?: "FORM"
                                val textRef = sidecars.write(
                                    PlsqlSidecars.TRIGGERS, "$scope.$triggerName", text,
                                )
                                triggers += TriggerInfo(
                                    name = triggerName,
                                    level = level,
                                    blockName = if (level == TriggerLevel.BLOCK || level == TriggerLevel.ITEM) block?.name else null,
                                    itemName = if (level == TriggerLevel.ITEM) item?.name else null,
                                    firstLine = firstCodeLine(text),
                                    lineCount = lineCountOf(text),
                                    textRef = textRef,
                                    // xmlRef is filled in at the matching END_ELEMENT.
                                )
                                when (level) {
                                    TriggerLevel.BLOCK -> block?.triggerNames?.add(triggerName)
                                    TriggerLevel.ITEM -> item?.triggerNames?.add(triggerName)
                                    else -> Unit
                                }
                            }

                            "ProgramUnit" -> {
                                val unitType = ProgramUnitType.fromForms(reader.attr("ProgramUnitType").orEmpty())
                                val text = reader.attr("ProgramUnitText").orEmpty()
                                val unitName = name ?: ""
                                val textRef = sidecars.write(
                                    PlsqlSidecars.PROGRAM_UNITS, "$unitName.${unitType.name}", text,
                                )
                                programUnits += ProgramUnitInfo(
                                    name = unitName,
                                    unitType = unitType,
                                    lineCount = lineCountOf(text),
                                    textRef = textRef,
                                )
                            }

                            "AttachedLibrary" -> attachedLibraries +=
                                AttachedLibraryInfo(name ?: "", reader.attr("LibraryLocation"))

                            "LOV" -> lov = LovInfo(name ?: "", reader.attr("RecordGroupName"))

                            "LOVColumnMapping" -> lov?.let {
                                lov = it.copy(columnMappings = it.columnMappings + (name ?: ""))
                            }

                            "RecordGroup" -> recordGroup =
                                RecordGroupInfo(name ?: "", reader.attr("RecordGroupQuery"))

                            "RecordGroupColumn" -> recordGroup?.let {
                                recordGroup = it.copy(columns = it.columns + (name ?: ""))
                            }

                            "Window" -> windows += WindowInfo(name ?: "", reader.attr("Title"))

                            "Canvas" -> canvases += CanvasInfo(
                                name ?: "", reader.attr("CanvasType"), reader.attr("WindowName"),
                            )

                            "Alert" -> alerts += AlertInfo(name ?: "", reader.attr("AlertMessage"))

                            "ModuleParameter" -> parameters += ParameterInfo(
                                name ?: "", reader.attr("DataType"), reader.attr("DefaultValue"),
                            )

                            "VisualAttribute" -> if (tab == null) visualAttributes += name ?: ""
                            "PropertyClass" -> propertyClasses += name ?: ""
                            "Editor" -> editors += name ?: ""

                            "Menu" -> menu = MenuInfo(name ?: "")

                            "MenuItem" -> menu?.let {
                                val command = reader.attr("CommandText")
                                val commandRef = command?.let { text ->
                                    sidecars.write(
                                        PlsqlSidecars.MENU_ITEMS, "${it.name}.${name ?: ""}", text,
                                    )
                                }
                                menu = it.copy(
                                    items = it.items + MenuItemInfo(
                                        name = name ?: "",
                                        label = reader.attr("Label"),
                                        commandType = reader.attr("CommandType"),
                                        commandRef = commandRef,
                                    ),
                                )
                            }

                            "ObjectLibraryTab" -> tab = ObjectLibraryTabInfo(name ?: "")
                        }

                        // Any named element directly on an object-library tab is a stored object.
                        if (tab != null && parent?.element == "ObjectLibraryTab" && name != null) {
                            tab = tab!!.copy(entries = tab!!.entries + ObjectLibraryEntry(name, element))
                        }
                    }

                    XMLStreamConstants.END_ELEMENT -> {
                        val frame = stack.removeLast()
                        val endLine = eventEndLine.coerceAtLeast(frame.startLine)
                        if (frame.name != null) {
                            objectRefs += ObjectRef(
                                objectType = frame.element,
                                name = frame.name,
                                ownerPath = frame.ownerPath,
                                ref = SourceRef(xmlPath, frame.startLine, endLine),
                            )
                        }
                        when (frame.element) {
                            "Trigger" -> if (triggers.isNotEmpty() && triggers.last().xmlRef == null) {
                                triggers[triggers.lastIndex] = triggers.last()
                                    .copy(xmlRef = SourceRef(xmlPath, frame.startLine, endLine))
                            }
                            "ProgramUnit" -> if (programUnits.isNotEmpty() && programUnits.last().xmlRef == null) {
                                programUnits[programUnits.lastIndex] = programUnits.last()
                                    .copy(xmlRef = SourceRef(xmlPath, frame.startLine, endLine))
                            }
                            "Block" -> block?.let {
                                blocks += BlockInfo(
                                    name = it.name,
                                    queryDataSourceName = it.queryDataSourceName,
                                    items = it.items.toList(),
                                    triggerNames = it.triggerNames.toList(),
                                    sourceRef = SourceRef(xmlPath, frame.startLine, endLine),
                                )
                                block = null
                            }
                            "Item" -> {
                                val built = item
                                if (built != null) {
                                    block?.items?.add(
                                        ItemInfo(
                                            name = built.name,
                                            itemType = built.itemType,
                                            dataType = built.dataType,
                                            columnName = built.columnName,
                                            canvasName = built.canvasName,
                                            prompt = built.prompt,
                                            triggerNames = built.triggerNames.toList(),
                                        ),
                                    )
                                    item = null
                                }
                            }
                            "LOV" -> lov?.let { lovs += it; lov = null }
                            "RecordGroup" -> recordGroup?.let { recordGroups += it; recordGroup = null }
                            "Menu" -> menu?.let { menus += it; menu = null }
                            "ObjectLibraryTab" -> tab?.let { objectLibraryTabs += it; tab = null }
                        }
                    }
                }
                prevEventEndLine = eventEndLine
            }
        } finally {
            reader.close()
        }

        return ModuleIndex(
            key = key,
            formsVersion = formsVersion,
            sourceFile = convertedFile.toString(),
            fingerprint = ModuleFingerprint(0, 0, ""), // stamped by FormsModuleParser/service
            convertedFile = xmlPath,
            parsedAt = Clock.System.now(),
            blocks = blocks,
            triggers = triggers,
            programUnits = programUnits,
            attachedLibraries = attachedLibraries,
            lovs = lovs,
            recordGroups = recordGroups,
            windows = windows,
            canvases = canvases,
            alerts = alerts,
            parameters = parameters,
            visualAttributes = visualAttributes,
            propertyClasses = propertyClasses,
            editors = editors,
            menus = menus,
            objectLibraryTabs = objectLibraryTabs,
            objectRefs = objectRefs,
        )
    }

    /**
     * StAX reports where an event *ends*; the element being opened started after the previous
     * event ended. `min` keeps this correct even if an implementation reports start locations.
     */
    private fun startLineOf(prevEventEndLine: Int, currentEventEndLine: Int): Int =
        minOf(prevEventEndLine, currentEventEndLine).coerceAtLeast(1)

    /** Dotted names of the enclosing named elements below the module root, or `null` at top level. */
    private fun ownerPathOf(stack: ArrayDeque<Frame>): String? =
        stack.asSequence()
            .filter { it.element !in setOf("Module", "FormModule", "MenuModule", "ObjectLibrary") }
            .mapNotNull { it.name }
            .joinToString(".")
            .ifEmpty { null }

    private fun XMLStreamReader.attr(name: String): String? = getAttributeValue(null, name)

    private fun xmlInputFactory(): XMLInputFactory = XMLInputFactory.newInstance().apply {
        // Converted files are local, but XXE hardening is free.
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_COALESCING, true)
    }
}
