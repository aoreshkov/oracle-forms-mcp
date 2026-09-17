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
 *
 * [dml] holds the block's data-manipulation properties as this module wrote them. An inline
 * `FROM`-clause subquery in [queryDataSourceName] is recovered from double escaping like a PL/SQL
 * body, and [BlockDml.sqlEncoding] says so.
 *
 * [dataSourceColumnCount] counts the block's `DataSourceColumn` elements — the base table's
 * columns as Forms recorded them. The columns themselves are not indexed: a form that repeats one
 * wide table across several blocks carries thousands of them, and they are read from the block's
 * own XML when asked for.
 *
 * [relations] are the master-detail relations this block is the **master** of — Forms writes a
 * relation on its master block, and only there. Which relations name a block as their detail is
 * worked out when served rather than stored twice.
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
    val dml: BlockDml? = null,
    val dataSourceColumnCount: Int = 0,
    val relations: List<RelationInfo> = emptyList(),
)

/**
 * One master-detail relation, as written on its master block (Forms2XML's `Relation`).
 *
 * The structure a screen queries through: a detail block is populated from its master's current
 * record by [joinCondition], and with [preventMasterlessOperations] it cannot be queried or written
 * without one. That limits what the form lets a user reach, and the form enforces it — not the
 * database — so a port has to re-implement it wherever the data is served.
 *
 * Every property is `null` when the file did not write it, meaning "not overridden" (the Forms
 * default), never `false`. [joinCondition] is SQL and goes through the same double-escaping recovery
 * as PL/SQL bodies; [joinEncoding] is [TextEncoding.RECOVERED] when it was recovered. [deleteRecord]
 * and [relationType] are kept as Forms wrote them (`Isolated`, `Non Isolated`, `Cascading`; `Join`,
 * `REF`).
 *
 * [inherited] is set when the relation is subclassed with its block: what is written here is then
 * only this module's overrides, and an absent [joinCondition] is defined with the parent.
 */
@Serializable
@SerialName("RelationInfo")
public data class RelationInfo(
    val name: String,
    val detailBlock: String? = null,
    val joinCondition: String? = null,
    val joinEncoding: TextEncoding = TextEncoding.ORIGINAL,
    val deferred: Boolean? = null,
    val autoQuery: Boolean? = null,
    val preventMasterlessOperations: Boolean? = null,
    val deleteRecord: String? = null,
    val relationType: String? = null,
    val inherited: InheritanceRef? = null,
)

/**
 * One column of a block's data source, as Forms recorded it from the table or view (Forms2XML's
 * `DataSourceColumn`, whose attributes are `DSCName`, `DSCType`, `DSCLength`, …).
 *
 * [mandatory] is the database's NOT NULL, not the item's `Required`: a mandatory column that no
 * item supplies has to be assigned by a trigger, or the insert fails. [type] is Forms' own
 * classification of the column's use (`Query` in every export seen so far).
 */
@Serializable
@SerialName("DataSourceColumnInfo")
public data class DataSourceColumnInfo(
    val name: String,
    val dataType: String? = null,
    val length: Int = 0,
    val precision: Int = 0,
    val scale: Int = 0,
    val mandatory: Boolean = false,
    val type: String? = null,
)

/**
 * A block's data-manipulation properties, as written in this module.
 *
 * Every field is `null` when the file did not write it, meaning "not overridden here" — which is
 * the Forms default only when no property class supplies the value. An unset [dmlDataTargetName]
 * means DML goes to the query data source, not that the block has no target.
 *
 * [whereClause] and [orderByClause] are SQL and go through the same double-escaping recovery as
 * PL/SQL bodies (as does the block's `queryDataSourceName`); [sqlEncoding] is
 * [TextEncoding.RECOVERED] when any of the three was recovered.
 */
@Serializable
@SerialName("BlockDml")
public data class BlockDml(
    val databaseBlock: Boolean? = null,
    val insertAllowed: Boolean? = null,
    val updateAllowed: Boolean? = null,
    val deleteAllowed: Boolean? = null,
    val queryAllowed: Boolean? = null,
    val keyMode: String? = null,
    val lockMode: String? = null,
    val dmlDataTargetName: String? = null,
    val whereClause: String? = null,
    val orderByClause: String? = null,
    val sqlEncoding: TextEncoding = TextEncoding.ORIGINAL,
)

/**
 * The properties that decide what an item contributes to a record's insert and update: whether it
 * is a database item at all, whether it may be written, its initial value, and whether it can be
 * reached.
 *
 * As written in one place — an item, or a property class. Every field is `null` when that place
 * did not write it. On an item whose [ItemInfo.propertyClass] is set, `null` therefore does **not**
 * mean the Forms default: the class may supply the value, and very often does. `get_block` resolves
 * that separately and never back-fills it here.
 *
 * [initialValue] is Forms' `InitializeValue`, the value a new record starts with.
 */
@Serializable
@SerialName("ItemDml")
public data class ItemDml(
    val databaseItem: Boolean? = null,
    val insertAllowed: Boolean? = null,
    val updateAllowed: Boolean? = null,
    val updateIfNull: Boolean? = null,
    val queryAllowed: Boolean? = null,
    val enabled: Boolean? = null,
    val keyboardNavigable: Boolean? = null,
    val primaryKey: Boolean? = null,
    val required: Boolean? = null,
    val maximumLength: Int? = null,
    val initialValue: String? = null,
    val copyValueFromItem: String? = null,
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
 *
 * [dml] carries the insert/update-deciding properties this item writes itself (see [ItemDml]);
 * `null` when it writes none. [required] is kept at the top level where it has always been, and
 * mirrored into [dml] so a resolution over item and class reads one shape.
 *
 * [width] and [height] are the item's size in the form's coordinate units, usually pixels. A text
 * item's [width] says nothing about how long a value it accepts — that is [ItemDml.maximumLength].
 * `null` when the file did not write them (or a property class supplies them).
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
    val dml: ItemDml? = null,
    val width: Int? = null,
    val height: Int? = null,
)
