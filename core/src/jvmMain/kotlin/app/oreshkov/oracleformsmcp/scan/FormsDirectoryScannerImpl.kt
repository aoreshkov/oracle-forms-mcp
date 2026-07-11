package app.oreshkov.oracleformsmcp.scan

import app.oreshkov.oracleformsmcp.core.FormsDirectoryScanner
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [FormsDirectoryScanner] over a flat directory: binaries matched by extension, pre-converted
 * text forms by [ModuleType.matchConverted], both case-insensitively, merged per [ModuleKey].
 */
public class FormsDirectoryScannerImpl(
    private val formsDir: Path,
) : FormsDirectoryScanner {

    override suspend fun scan(): List<ScannedModule> = withContext(Dispatchers.IO) {
        if (!formsDir.exists()) return@withContext emptyList()
        val files = formsDir.listDirectoryEntries().filter { it.isRegularFile() }

        val binaries = mutableMapOf<ModuleKey, Path>()
        val preConverted = mutableMapOf<ModuleKey, Path>()
        for (file in files) {
            val type = ModuleType.fromExtension(file.extension)
            if (type != null && file.nameWithoutExtension.isNotBlank()) {
                binaries[ModuleKey.of(file.nameWithoutExtension, type)] = file
                continue
            }
            val converted = ModuleType.matchConverted(file.name) ?: continue
            preConverted[ModuleKey.of(converted.first, converted.second)] = file
        }

        (binaries.keys + preConverted.keys).sortedBy { it.toString() }.map { key ->
            ScannedModule(
                key = key,
                binaryPath = binaries[key]?.toAbsolutePath()?.toString(),
                preConvertedPath = preConverted[key]?.toAbsolutePath()?.toString(),
            )
        }
    }
}
