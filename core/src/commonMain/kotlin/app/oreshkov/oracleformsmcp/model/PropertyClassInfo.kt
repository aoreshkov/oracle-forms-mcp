package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A property class declared in this module, with the properties it supplies to the items and
 * blocks that name it.
 *
 * An item's role in a real application usually lives here rather than on the item: whether it is a
 * database item, whether it may be inserted or updated. That is only useful if the class's
 * properties are indexed, and very often they are not in this module at all — a shop keeps its
 * classes in one shared form and every other form declares a **stub**: a `PropertyClass` carrying
 * nothing but the pointer to the shared one. [inherited] is that pointer; [item] and [block] are
 * then `null`, and the values are found by following it into a cached parent.
 *
 * [item] and [block] are `null` when the class writes none of those properties itself, following
 * the same "not written ≠ false" rule as everywhere else.
 *
 * [propertyClass] is set when the class is itself based on another class declared in this module;
 * what it does not write, that class supplies.
 */
@Serializable
@SerialName("PropertyClassInfo")
public data class PropertyClassInfo(
    val name: String,
    val item: ItemDml? = null,
    val block: BlockDml? = null,
    val propertyClass: String? = null,
    val inherited: InheritanceRef? = null,
)
