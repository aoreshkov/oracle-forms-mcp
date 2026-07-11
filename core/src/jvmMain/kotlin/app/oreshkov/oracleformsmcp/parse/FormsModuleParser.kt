package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.core.ModuleParser
import app.oreshkov.oracleformsmcp.io.Fingerprints
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import java.nio.file.Path
import kotlin.io.path.name

/**
 * [ModuleParser] entry point dispatching on the converted file's format: `.pld` text dumps go to
 * [PldParser], everything else to the StAX [FormsXmlParser].
 *
 * The returned index carries the converted file itself as placeholder source metadata; the
 * service overwrites [ModuleIndex.sourceFile]/[ModuleIndex.fingerprint] with the file it actually
 * fingerprints for staleness.
 */
public class FormsModuleParser : ModuleParser {

    override fun parse(key: ModuleKey, convertedFile: String, moduleCacheDir: String): ModuleIndex {
        val file = Path.of(convertedFile)
        val cacheDir = Path.of(moduleCacheDir)
        val partial =
            if (file.name.endsWith(".pld", ignoreCase = true)) {
                PldParser.parse(key, file, cacheDir)
            } else {
                FormsXmlParser.parse(key, file, cacheDir)
            }
        return partial.copy(
            sourceFile = file.toAbsolutePath().toString(),
            fingerprint = Fingerprints.of(file),
        )
    }
}

/**
 * [file] as a cache-relative path string for [SourceRef]s, falling back to the bare file name
 * when [file] lives outside [cacheDir] (only happens in tests).
 */
internal fun cacheRelative(file: Path, cacheDir: Path): String {
    val relative = runCatching {
        cacheDir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
    }.getOrNull()
    if (relative == null || relative.startsWith("..")) return file.name
    return relative.joinToString("/")
}

/** Replaces characters that are invalid in Windows file names. */
internal fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_")
