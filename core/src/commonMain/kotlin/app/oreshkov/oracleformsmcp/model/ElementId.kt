package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable identity of one element inside a module, used as the anchor for AI/user-supplied
 * annotations. Deliberately carries no [SourceRef]: line ranges shift on every re-conversion, so
 * an annotation keyed by location would drift off its element. [name] (and [ownerPath]) survive
 * re-conversion as long as the element is not renamed in the source.
 *
 * Forms names are case-insensitive; use [canonical] for equality/keying so `ORDERS` and `orders`
 * address the same element regardless of how a caller typed them.
 *
 * [ownerPath] mirrors [ObjectRef.ownerPath] — the block for a block-level trigger or an item
 * (`ORDERS`), `block.item` for an item-level one (`ORDERS.ORDER_ID`), or the owning block for an
 * [ElementKind.ITEM]; `null` at module/top level. Two same-kind elements may share a name across
 * scopes (an item `ID` in two blocks, a trigger at form and block level), so the owner is part of
 * the identity. [ElementKind.MENU_ITEM]s carry their owning menu; package
 * [ElementKind.PROGRAM_UNIT]s carry their [ProgramUnitType] name (`PACKAGE_SPEC`/`PACKAGE_BODY`)
 * since spec and body share a name — other unit types stay owner-less.
 */
@Serializable
@SerialName("ElementId")
public data class ElementId(
    val module: ModuleKey,
    val kind: ElementKind,
    val name: String,
    val ownerPath: String? = null,
) {
    init {
        require(name.isNotBlank()) { "element name must not be blank" }
    }

    /** Case-normalized key for equality/dedup: `NAME.ext|KIND|OWNER|NAME`. */
    public fun canonical(): String =
        "$module|$kind|${(ownerPath ?: "").uppercase()}|${name.uppercase()}"
}
