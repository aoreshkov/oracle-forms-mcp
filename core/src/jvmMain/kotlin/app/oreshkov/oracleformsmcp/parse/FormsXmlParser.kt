package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.model.AlertInfo
import app.oreshkov.oracleformsmcp.model.AttachedLibraryInfo
import app.oreshkov.oracleformsmcp.model.BlockInfo
import app.oreshkov.oracleformsmcp.model.CanvasInfo
import app.oreshkov.oracleformsmcp.model.InheritanceRef
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
import app.oreshkov.oracleformsmcp.model.TextEncoding
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
 *
 * Subclassing is tracked down the element stack (see [inheritanceOf]): an element carrying a
 * cross-module pointer opens a chain, and its `SubclassSubObject` children continue it one
 * segment deeper — so an inherited trigger knows the parent module *and* the path to address it
 * there, which neither its own element nor its owner's carries alone.
 */
internal object FormsXmlParser {

    private class Frame(
        val element: String,
        val name: String?,
        val startLine: Int,
        val ownerPath: String?,
        val inherited: InheritanceRef?,
    )

    private class BlockBuilder(
        val name: String,
        val queryDataSourceName: String?,
        val propertyClass: String?,
        val inherited: InheritanceRef?,
    ) {
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
        val propertyClass: String?,
        val visible: Boolean?,
        val required: Boolean?,
        val lovName: String?,
        val inherited: InheritanceRef?,
    ) {
        val triggerNames = mutableListOf<String>()
    }

    fun parse(key: ModuleKey, convertedFile: Path, moduleCacheDir: Path): ModuleIndex {
        val sidecars = PlsqlSidecars(moduleCacheDir)
        val xmlPath = cacheRelative(convertedFile, moduleCacheDir)

        var formsVersion: String? = null
        // The module's own name, to tell a parent in another module from a property class in this
        // one. Taken from the module element itself; the key is the fallback for a headless parse.
        var moduleName: String? = key.name
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
                        val inherited = inheritanceOf(reader, name, parent?.inherited, moduleName)
                        stack.addLast(Frame(element, name, startLine, ownerPath, inherited))

                        when (element) {
                            "Module" -> formsVersion = reader.attr("version")

                            // Every object below the module root is compared against this name.
                            "FormModule", "MenuModule", "ObjectLibrary" ->
                                moduleName = name ?: moduleName

                            "Block" -> if (parent?.element == "FormModule") {
                                block = BlockBuilder(
                                    name = name ?: "",
                                    queryDataSourceName = reader.attr("QueryDataSourceName"),
                                    propertyClass = reader.localParentName(moduleName),
                                    inherited = inherited,
                                )
                            }

                            "Item" -> if (block != null && parent?.element == "Block") {
                                item = ItemBuilder(
                                    name = name ?: "",
                                    itemType = reader.attr("ItemType"),
                                    dataType = reader.attr("DataType"),
                                    columnName = reader.attr("ColumnName"),
                                    canvasName = reader.attr("CanvasName"),
                                    prompt = reader.attr("Prompt"),
                                    propertyClass = reader.localParentName(moduleName),
                                    visible = reader.boolAttr("Visible"),
                                    required = reader.boolAttr("Required"),
                                    // Forms2XML has been inconsistent about this one's casing.
                                    lovName = reader.attr("LOVName") ?: reader.attr("LovName"),
                                    inherited = inherited,
                                )
                            }

                            "Trigger" -> {
                                val body = decodeDoubleEscaped(reader.attr("TriggerText").orEmpty())
                                val text = body.text
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
                                    inherited = inherited,
                                    textEncoding = body.encoding,
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
                                val body = decodeDoubleEscaped(reader.attr("ProgramUnitText").orEmpty())
                                val text = body.text
                                val unitName = name ?: ""
                                val textRef = sidecars.write(
                                    PlsqlSidecars.PROGRAM_UNITS, "$unitName.${unitType.name}", text,
                                )
                                programUnits += ProgramUnitInfo(
                                    name = unitName,
                                    unitType = unitType,
                                    lineCount = lineCountOf(text),
                                    inherited = inherited,
                                    textEncoding = body.encoding,
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

                            "Window" -> windows += WindowInfo(
                                name = name ?: "",
                                title = reader.attr("Title"),
                                modal = reader.boolAttr("Modal"),
                                width = reader.intAttr("Width"),
                                height = reader.intAttr("Height"),
                                horizontalToolbarCanvasName = reader.attr("HorizontalToolbarCanvasName"),
                                verticalToolbarCanvasName = reader.attr("VerticalToolbarCanvasName"),
                            )

                            "Canvas" -> canvases += CanvasInfo(
                                name = name ?: "",
                                canvasType = reader.attr("CanvasType"),
                                windowName = reader.attr("WindowName"),
                                propertyClass = reader.localParentName(moduleName),
                                raiseOnEnter = reader.boolAttr("RaiseOnEnter"),
                                width = reader.intAttr("Width"),
                                height = reader.intAttr("Height"),
                                viewportWidth = reader.intAttr("ViewportWidth"),
                                viewportHeight = reader.intAttr("ViewportHeight"),
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
                                val command = reader.attr("CommandText")?.let(::decodeDoubleEscaped)
                                val commandRef = command?.let { body ->
                                    sidecars.write(
                                        PlsqlSidecars.MENU_ITEMS, "${it.name}.${name ?: ""}", body.text,
                                    )
                                }
                                menu = it.copy(
                                    items = it.items + MenuItemInfo(
                                        name = name ?: "",
                                        label = reader.attr("Label"),
                                        commandType = reader.attr("CommandType"),
                                        commandRef = commandRef,
                                        textEncoding = command?.encoding ?: TextEncoding.ORIGINAL,
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
                                inherited = frame.inherited,
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
                                    propertyClass = it.propertyClass,
                                    items = it.items.toList(),
                                    triggerNames = it.triggerNames.toList(),
                                    inherited = it.inherited,
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
                                            propertyClass = built.propertyClass,
                                            visible = built.visible,
                                            required = built.required,
                                            lovName = built.lovName,
                                            triggerNames = built.triggerNames.toList(),
                                            inherited = built.inherited,
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

        // Property classes are declared after the objects that use them, so the candidates
        // collected during the pass are confirmed here, against what the document actually has.
        val declaredClasses = propertyClasses.mapTo(HashSet()) { it.uppercase() }
        val classedBlocks = blocks.map { b ->
            b.copy(
                propertyClass = b.propertyClass.asPropertyClass(declaredClasses),
                items = b.items.map { it.copy(propertyClass = it.propertyClass.asPropertyClass(declaredClasses)) },
            )
        }
        val classedCanvases = canvases.map { it.copy(propertyClass = it.propertyClass.asPropertyClass(declaredClasses)) }

        return ModuleIndex(
            key = key,
            formsVersion = formsVersion,
            sourceFile = convertedFile.toString(),
            fingerprint = ModuleFingerprint(0, 0, ""), // stamped by FormsModuleParser/service
            convertedFile = xmlPath,
            parsedAt = Clock.System.now(),
            blocks = classedBlocks,
            triggers = triggers,
            programUnits = programUnits,
            attachedLibraries = attachedLibraries,
            lovs = lovs,
            recordGroups = recordGroups,
            windows = windows,
            canvases = classedCanvases,
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

    /**
     * The pointer to another module's copy of the element being opened, or `null` when its
     * definition is right here.
     *
     * Forms records three different things with the same family of `Parent*` attributes, and they
     * do not mean the same thing:
     *
     * 1. **Subclassed from another module** — `ParentFilename` names the file and `ParentName` the
     *    object's name *over there* (`BAR_LIST` here is `BAR` in `toolbar.fmb`). The definition is
     *    hidden; this is the case the pointer exists for.
     * 2. **Copied in with an object group** — `SubclassObjectGroup="true"`. The object keeps its
     *    own name and place in the parent, and `ParentName` names the **group**, not the object.
     *    Taking `ParentName` as the name here would point every member of a group at the group.
     * 3. **A property class in this same module** — `ParentModule` naming the module itself, with
     *    no `ParentFilename`. Nothing is hidden: the property class is indexed alongside and
     *    supplies properties, not a body. Emitting a ref would be both wrong (it addresses an
     *    object of a different kind, at a path that does not exist) and noisy — a real form runs
     *    to dozens of them.
     *
     * Cases 1 and 2 are recorded; case 3 is not — it is the property class of gap "item
     * semantics", not an inheritance pointer.
     *
     * A child of an inherited object carries only `SubclassSubObject="true"`, which says neither
     * which module nor which path, so the owner's pointer is threaded down the stack one segment
     * at a time. Only [ownerRef], the *immediately* enclosing element's pointer, is followed:
     * Forms marks every link of a chain, so a gap means the object in between was defined here,
     * and continuing across it would invent a parent path.
     *
     * A `SubclassSubObject` with no pointer above it still yields a bare [InheritanceRef] — the
     * object is known to be inherited even though this file does not say from where, and saying
     * so is the whole point: an empty body with no ref reads as "there is no code".
     */
    private fun inheritanceOf(
        reader: XMLStreamReader,
        name: String?,
        ownerRef: InheritanceRef?,
        moduleName: String?,
    ): InheritanceRef? {
        val parentModule = reader.attr("ParentModule")
        val parentFilename = reader.attr("ParentFilename")
        val parentName = reader.attr("ParentName")
        val parentType = reader.attr("ParentType")
        val objectGroup = reader.attr("SubclassObjectGroup").equals("true", ignoreCase = true)
        val subObject = reader.attr("SubclassSubObject").equals("true", ignoreCase = true)
        // A parent in this same module hides nothing (case 3 above); a filename always means
        // another file, and is checked first so a converter that omits ParentModule still works.
        val elsewhere = parentFilename != null ||
            (parentModule != null && !parentModule.equals(moduleName, ignoreCase = true))
        return when {
            // Case 2: keep the object's own identity, record the group that carried it.
            elsewhere && objectGroup -> InheritanceRef(
                module = parentModule,
                file = parentFilename,
                name = name,
                ownerPath = ownerRef?.let { joinPath(it.ownerPath, it.name) },
                objectGroup = parentName,
                parentType = parentType,
            )
            // Case 1: ParentName is this object's name in the parent module.
            elsewhere -> InheritanceRef(
                module = parentModule,
                file = parentFilename,
                name = parentName ?: name,
                ownerPath = ownerRef?.let { joinPath(it.ownerPath, it.name) },
                parentType = parentType,
            )
            // Inherited through the owner: same parent module, one path segment deeper. Forms
            // writes no ParentName on a sub-object — it is the same name on both sides.
            subObject && ownerRef != null -> InheritanceRef(
                module = ownerRef.module,
                file = ownerRef.file,
                name = name,
                ownerPath = joinPath(ownerRef.ownerPath, ownerRef.name),
                objectGroup = ownerRef.objectGroup,
                parentType = parentType,
                subObject = true,
            )
            // Subclassed, but nothing above it named a parent module.
            subObject -> InheritanceRef(parentType = parentType, subObject = true)
            else -> null
        }
    }

    /** Dotted join of an owner path and a name, `null` when both are absent. */
    private fun joinPath(ownerPath: String?, name: String?): String? =
        listOfNotNull(ownerPath, name).joinToString(".").ifEmpty { null }

    /** Dotted names of the enclosing named elements below the module root, or `null` at top level. */
    private fun ownerPathOf(stack: ArrayDeque<Frame>): String? =
        stack.asSequence()
            .filter { it.element !in setOf("Module", "FormModule", "MenuModule", "ObjectLibrary") }
            .mapNotNull { it.name }
            .joinToString(".")
            .ifEmpty { null }

    private fun XMLStreamReader.attr(name: String): String? = getAttributeValue(null, name)

    /**
     * A boolean property, or `null` when the file did not write it. Forms2XML emits a property
     * only where it differs from the default, so absence means "not overridden" — reporting
     * `false` for it would invent a fact.
     */
    private fun XMLStreamReader.boolAttr(name: String): Boolean? = when {
        attr(name).equals("true", ignoreCase = true) -> true
        attr(name).equals("false", ignoreCase = true) -> false
        else -> null
    }

    /** An integer property, or `null` when absent or not a number (never a parse failure). */
    private fun XMLStreamReader.intAttr(name: String): Int? = attr(name)?.trim()?.toIntOrNull()

    /**
     * `ParentName` when the parent is in *this* module — the candidate property class.
     *
     * The same attribute names three unrelated relationships (see [inheritanceOf]), and this is
     * the one that hides nothing: the parent is indexed alongside. It is only a *candidate* here
     * because a same-module parent could also be another object of the same kind; [asPropertyClass]
     * settles it against the property classes the document actually declares.
     */
    private fun XMLStreamReader.localParentName(moduleName: String?): String? {
        if (attr("ParentFilename") != null) return null
        if (attr("SubclassObjectGroup").equals("true", ignoreCase = true)) return null
        val parentModule = attr("ParentModule")
        if (parentModule != null && !parentModule.equals(moduleName, ignoreCase = true)) return null
        return attr("ParentName")
    }

    /**
     * Keeps a candidate parent name only if the module really declares a property class by that
     * name.
     *
     * `ParentType` would answer this directly, but its numbering is version-dependent and the XML
     * defines it nowhere, so it is not something to hard-code. The document says it instead: the
     * `PropertyClass` elements are right there. They are also written *after* the blocks that use
     * them, which is why this runs once at the end rather than inline.
     */
    private fun String?.asPropertyClass(declared: Set<String>): String? =
        this?.takeIf { it.uppercase() in declared }

    private fun xmlInputFactory(): XMLInputFactory = XMLInputFactory.newInstance().apply {
        // Converted files are local, but XXE hardening is free.
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_COALESCING, true)
    }
}
