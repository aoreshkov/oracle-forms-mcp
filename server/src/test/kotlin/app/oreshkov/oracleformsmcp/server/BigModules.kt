package app.oreshkov.oracleformsmcp.server

import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Generated modules past every per-section cap, written as pre-converted XML into a forms
 * directory. A real generated form reaches these sizes; the fixtures under `sample-forms/` are
 * deliberately small, so the bounds are exercised here.
 */
internal object BigModules {
    const val BLOCKS = 120
    const val ITEMS_PER_BLOCK = 40
    const val WINDOWS = 600
    const val CANVASES = 600
    const val PROGRAM_UNITS = 200
    const val LIBRARIES = 60
    const val MENUS = 80
    const val MENU_ITEMS = 40

    /** `big_fmb.xml`: [BLOCKS] blocks of [ITEMS_PER_BLOCK] items, and every other section past its cap. */
    fun writeForm(formsDir: Path) {
        val blocks = (1..BLOCKS).joinToString("\n") { b ->
            val items = (1..ITEMS_PER_BLOCK).joinToString("\n") { i ->
                "      <Item Name=\"ITEM_$i\" ItemType=\"Text Item\" CanvasName=\"CV_$b\"/>"
            }
            "    <Block Name=\"BLOCK_$b\" QueryDataSourceName=\"TABLE_$b\">\n$items\n    </Block>"
        }
        val libraries = (1..LIBRARIES).joinToString("\n") { "    <AttachedLibrary Name=\"LIB_$it\"/>" }
        val windows = (1..WINDOWS).joinToString("\n") { "    <Window Name=\"WIN_$it\" Title=\"Window $it\"/>" }
        val canvases = (1..CANVASES).joinToString("\n") {
            "    <Canvas Name=\"CV_$it\" CanvasType=\"Content\" WindowName=\"WIN_$it\"/>"
        }
        val units = (1..PROGRAM_UNITS).joinToString("\n") {
            "    <ProgramUnit Name=\"UNIT_$it\" ProgramUnitType=\"Procedure\" " +
                "ProgramUnitText=\"PROCEDURE unit_$it IS&#10;BEGIN&#10;  NULL;&#10;END;\"/>"
        }
        formsDir.resolve("big_fmb.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="12.2.1.19.0" xmlns="http://xmlns.oracle.com/Forms">
            |  <FormModule Name="BIG">
            |$libraries
            |$blocks
            |$canvases
            |$units
            |$windows
            |  </FormModule>
            |</Module>
            |
            """.trimMargin(),
        )
    }

    /** `bigmenu_mmb.xml`: [MENUS] menus of [MENU_ITEMS] items each. */
    fun writeMenu(formsDir: Path) {
        val menus = (1..MENUS).joinToString("\n") { m ->
            val items = (1..MENU_ITEMS).joinToString("\n") { i ->
                "      <MenuItem Name=\"ENTRY_$i\" Label=\"Entry $i\" CommandType=\"PL/SQL\" CommandText=\"NULL;\"/>"
            }
            "    <Menu Name=\"MENU_$m\">\n$items\n    </Menu>"
        }
        formsDir.resolve("bigmenu_mmb.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="12.2.1.19.0" xmlns="http://xmlns.oracle.com/Forms">
            |  <MenuModule Name="BIGMENU" MainMenu="MENU_1">
            |$menus
            |  </MenuModule>
            |</Module>
            |
            """.trimMargin(),
        )
    }
}
