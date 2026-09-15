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
)
