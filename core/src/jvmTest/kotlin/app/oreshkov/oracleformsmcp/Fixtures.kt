package app.oreshkov.oracleformsmcp

import java.nio.file.Files
import java.nio.file.Path

/** Copies a classpath fixture into [dir] under [fileName] and returns its path. */
fun copyFixture(name: String, dir: Path, fileName: String = name): Path {
    val resource = object {}.javaClass.getResourceAsStream("/fixtures/$name")
        ?: error("Missing test fixture /fixtures/$name")
    val target = dir.resolve(fileName)
    resource.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    return target
}
