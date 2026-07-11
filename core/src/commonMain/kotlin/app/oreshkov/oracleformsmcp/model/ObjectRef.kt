package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Location of one named object inside the converted XML, feeding `get_object_xml`. [objectType]
 * is the XML element name as written by Forms2XML (`Block`, `Trigger`, `LOV`, …); [ownerPath]
 * disambiguates nested objects (`ORDERS` for a block-level trigger, `ORDERS.ORDER_ID` for an
 * item-level one).
 */
@Serializable
@SerialName("ObjectRef")
public data class ObjectRef(
    val objectType: String,
    val name: String,
    val ownerPath: String? = null,
    val ref: SourceRef,
)
