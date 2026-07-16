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
 * [ElementKind.ITEM]; `null` at module/top level.
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
