package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Location of one named object inside the converted XML, feeding `get_object_xml`. [objectType]
 * is the XML element name as written by Forms2XML (`Block`, `Trigger`, `LOV`, …); [ownerPath]
 * disambiguates nested objects (`ORDERS` for a block-level trigger, `ORDERS.ORDER_ID` for an
 * item-level one).
 *
 * [inherited] is the subclassing pointer that applies to *this* object, already resolved against
 * its owner chain — so the escape hatch answers "where does this really come from?" at the level
 * it was asked, not only at the enclosing element that happens to carry the raw `ParentModule`
 * attribute.
 */
@Serializable
@SerialName("ObjectRef")
public data class ObjectRef(
    val objectType: String,
    val name: String,
    val ownerPath: String? = null,
    val inherited: InheritanceRef? = null,
    val ref: SourceRef,
)
