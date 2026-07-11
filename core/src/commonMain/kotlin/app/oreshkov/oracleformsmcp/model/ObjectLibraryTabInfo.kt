package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One tab page of an `.olb` object library. */
@Serializable
@SerialName("ObjectLibraryTabInfo")
public data class ObjectLibraryTabInfo(
    val name: String,
    val entries: List<ObjectLibraryEntry> = emptyList(),
)

/** One reusable object stored on a library tab. */
@Serializable
@SerialName("ObjectLibraryEntry")
public data class ObjectLibraryEntry(
    val name: String,
    val objectType: String? = null,
)
