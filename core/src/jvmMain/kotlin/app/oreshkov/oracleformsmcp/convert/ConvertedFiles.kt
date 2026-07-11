package app.oreshkov.oracleformsmcp.convert

import app.oreshkov.oracleformsmcp.model.ModuleType
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Shared helpers for locating and copying pre-converted module files. */
internal object ConvertedFiles {

    /** Whether [file]'s name is a converted text form (`*_fmb.xml`, `*.pld`, …). */
    fun isConverted(file: Path): Boolean = ModuleType.matchConverted(file.name) != null

    /**
     * The pre-converted sibling of [binary] for [type] (`ORDERS.fmb` → `orders_fmb.xml` next to
     * it), matched case-insensitively, or `null`.
     */
    fun findSibling(binary: Path, type: ModuleType): Path? {
        val expected = binary.name.dropLast(type.extension.length + 1) + type.convertedSuffix
        val dir = binary.parent ?: return null
        return dir.listDirectoryEntries()
            .firstOrNull { it.isRegularFile() && it.name.equals(expected, ignoreCase = true) }
    }

    /** Copies [source] into [targetDir] (created if needed), replacing any stale copy. */
    fun copyInto(source: Path, targetDir: Path): Path {
        targetDir.createDirectories()
        val target = targetDir.resolve(source.name)
        return source.copyTo(target, StandardCopyOption.REPLACE_EXISTING)
    }
}
