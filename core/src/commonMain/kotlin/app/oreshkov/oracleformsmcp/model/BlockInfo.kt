package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One data block of a form. [queryDataSourceName] is the base table/view (or `null` for control
 * blocks). Trigger bodies live in [ModuleIndex.triggers]; blocks carry only [triggerNames] so the
 * index doesn't duplicate PL/SQL metadata.
 *
 * [inherited] is set when the block is subclassed from another module. What is listed here is
 * then only what this module overrides or adds; the full definition lives where the ref points.
 */
@Serializable
@SerialName("BlockInfo")
public data class BlockInfo(
    val name: String,
    val queryDataSourceName: String? = null,
    val propertyClass: String? = null,
    val items: List<ItemInfo> = emptyList(),
    val triggerNames: List<String> = emptyList(),
    val inherited: InheritanceRef? = null,
    val sourceRef: SourceRef? = null,
)

/**
 * One item of a block (field, button, checkbox, …).
 *
 * [propertyClass] is where an item's semantics usually live in a real application: Forms shops
 * name their classes for the role, so a push button is a *LOV* button or a *filter* list only
 * because of the class it inherits. Without it every push button looks alike.
 *
 * [visible], [required] and [lovName] are `null` when the converted file did not write them, which
 * means the item keeps the Forms default — not that the property is false or absent. Forms2XML
 * emits a property only where it differs from the default, so absence here is genuinely "not
 * overridden".
 *
 * [inherited] is set when the item is subclassed — through its block's parent, or from a module of
 * its own.
 */
@Serializable
@SerialName("ItemInfo")
public data class ItemInfo(
    val name: String,
    val itemType: String? = null,
    val dataType: String? = null,
    val columnName: String? = null,
    val canvasName: String? = null,
    val prompt: String? = null,
    val propertyClass: String? = null,
    val visible: Boolean? = null,
    val required: Boolean? = null,
    val lovName: String? = null,
    val triggerNames: List<String> = emptyList(),
    val inherited: InheritanceRef? = null,
)
