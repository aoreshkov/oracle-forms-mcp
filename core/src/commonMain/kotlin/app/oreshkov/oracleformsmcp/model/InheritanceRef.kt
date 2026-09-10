package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a *subclassed* (inherited) object is really defined.
 *
 * Forms lets an object be subclassed from another module: the child stores only its overrides and
 * keeps a pointer to the parent, so the converted XML of the child carries an empty `TriggerText`
 * and the pointer sits on the enclosing owner —
 *
 * ```
 * <Block Name="BAR_LIST" ParentModule="TOOLBAR" ParentName="BAR" ParentFilename="toolbar.fmb">
 *    <Item Name="SELECT" SubclassSubObject="true">
 *       <Trigger Name="WHEN-BUTTON-PRESSED" SubclassSubObject="true"/>
 * ```
 *
 * Without this reference an empty body is indistinguishable from "this object has no code", which
 * is a false assertion of absence a reader cannot detect. Every element that Forms can subclass
 * therefore carries one, and the tools that serve a body say so in their `bodySource`.
 *
 * [name] and [ownerPath] are stated in the **parent** module's vocabulary and in the shape the
 * tools take, so the pointer is directly callable: the trigger above resolves to
 * `get_trigger(module = "toolbar.fmb", name = "WHEN-BUTTON-PRESSED", ownerPath = "BAR.SELECT")`.
 * [ownerPath] is `null` for an object at module level.
 *
 * [module] and [file] are `null` when the converted XML recorded no cross-module pointer — the
 * object is subclassed but its parent is not named here, and only the raw attributes
 * (`get_object_xml`) can say more. [subObject] is `true` when this object inherits through its
 * owner's subclassing (`SubclassSubObject`) rather than carrying a pointer of its own.
 *
 * [objectGroup] is set for the other way Forms copies a definition in: the object arrived as part
 * of an **object group** (usually from an `.olb`). It keeps its own name and place there, so
 * [name] is its own name and [objectGroup] is the group that carried it — unlike plain
 * subclassing, where Forms records the parent object's name in `ParentName`.
 *
 * A parent in the *same* module — a property class, which supplies properties rather than a
 * definition — is deliberately not represented here: nothing is hidden in that case, since the
 * parent is indexed alongside this object and reachable by name.
 */
@Serializable
@SerialName("InheritanceRef")
public data class InheritanceRef(
    val module: String? = null,
    val file: String? = null,
    val name: String? = null,
    val ownerPath: String? = null,
    val objectGroup: String? = null,
    /**
     * Forms2XML's `ParentType`, a raw numeric object-type code for the *parent* object, passed
     * through undecoded — the numbering is version-dependent and the XML does not define it.
     * Usually the same kind as this object; where it is not, the parent is a property class or an
     * object group.
     */
    val parentType: String? = null,
    val subObject: Boolean = false,
)
