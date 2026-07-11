package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One menu of an `.mmb` module. */
@Serializable
@SerialName("MenuInfo")
public data class MenuInfo(
    val name: String,
    val items: List<MenuItemInfo> = emptyList(),
)

/**
 * One entry of a menu. When the item's command is PL/SQL, [commandRef] points at the decoded
 * sidecar like a trigger body does.
 */
@Serializable
@SerialName("MenuItemInfo")
public data class MenuItemInfo(
    val name: String,
    val label: String? = null,
    val commandType: String? = null,
    val commandRef: SourceRef? = null,
)
