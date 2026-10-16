package app.oreshkov.oracleformsmcp.scan

import app.oreshkov.oracleformsmcp.core.FormsDirectoryScanner
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ModuleType
import app.oreshkov.oracleformsmcp.model.ScannedModule
import co.touchlab.kermit.Logger
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
 *
 * A file whose stem no module can have (`orders..bak.fmb`) is skipped with a warning rather than
 * failing the scan, since one stray backup would otherwise take down every tool that lists the
 * directory. Module names are case-insensitive, so on a case-sensitive file system `ORDERS.fmb`
 * and `orders.fmb` are one module: the first name in sorted order wins, whatever order the
 * directory listing happens to return, and the other is named in a warning.
 */
public class FormsDirectoryScannerImpl(
    private val formsDir: Path,
) : FormsDirectoryScanner {

    private val log = Logger.withTag("FormsDirectoryScanner")

    override suspend fun scan(): List<ScannedModule> = withContext(Dispatchers.IO) {
        if (!formsDir.exists()) return@withContext emptyList()
        val files = formsDir.listDirectoryEntries().filter { it.isRegularFile() }.sortedBy { it.name }

        val binaries = mutableMapOf<ModuleKey, Path>()
        val preConverted = mutableMapOf<ModuleKey, Path>()
        for (file in files) {
            val type = ModuleType.fromExtension(file.extension)
            if (type != null && file.nameWithoutExtension.isNotBlank()) {
                binaries.claim(ModuleKey.ofOrNull(file.nameWithoutExtension, type), file)
                continue
            }
            val converted = ModuleType.matchConverted(file.name) ?: continue
            preConverted.claim(ModuleKey.ofOrNull(converted.first, converted.second), file)
        }

        (binaries.keys + preConverted.keys).sortedBy { it.toString() }.map { key ->
            ScannedModule(
                key = key,
                binaryPath = binaries[key]?.toAbsolutePath()?.toString(),
                preConvertedPath = preConverted[key]?.toAbsolutePath()?.toString(),
            )
        }
    }

    /** Records [file] under [key] unless the key is invalid or an earlier file already holds it. */
    private fun MutableMap<ModuleKey, Path>.claim(key: ModuleKey?, file: Path) {
        if (key == null) {
            log.w { "Skipping '${file.name}' in $formsDir: its name is not a valid module name (no '..', ':' or path separators)" }
            return
        }
        val holder = putIfAbsent(key, file) ?: return
        log.w { "'${file.name}' and '${holder.name}' in $formsDir are the same module $key; using '${holder.name}'" }
    }
}
