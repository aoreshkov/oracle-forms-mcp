package app.oreshkov.oracleformsmcp.convert

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Builds a fake `ORACLE_HOME` whose `bin` contains stub scripts, so converter behavior is
 * testable without a Forms installation. Writes `.bat` stubs on Windows and `sh` scripts
 * elsewhere.
 */
object FakeOracleHome {

    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    fun binDir(oracleHome: Path): Path = oracleHome.resolve("bin").createDirectories()

    /**
     * Writes a stub tool named [name] that runs [batchLines] (Windows) or [shellLines] (Unix).
     * Lines may use `%CD%` / `$PWD` for the working directory.
     */
    fun stubTool(oracleHome: Path, name: String, batchLines: List<String>, shellLines: List<String>) {
        stubScript(binDir(oracleHome), name, batchLines, shellLines)
    }

    /**
     * Writes an executable stub script [name] into [dir] and returns its path — the same stubs as
     * [stubTool] but at an arbitrary location, for converters that are configured by full path
     * rather than discovered under `ORACLE_HOME/bin`.
     */
    fun stubScript(dir: Path, name: String, batchLines: List<String>, shellLines: List<String>): Path {
        dir.createDirectories()
        return if (isWindows) {
            dir.resolve("$name.bat").apply {
                writeText((listOf("@echo off") + batchLines).joinToString("\r\n") + "\r\n")
            }
        } else {
            dir.resolve(name).apply {
                writeText((listOf("#!/bin/sh") + shellLines).joinToString("\n") + "\n")
                toFile().setExecutable(true)
            }
        }
    }

    /** A stub that copies [source] into the process working directory as [targetName]. */
    fun copyingTool(oracleHome: Path, name: String, source: Path, targetName: String) {
        stubTool(
            oracleHome,
            name,
            batchLines = freshCopyBatch("$source", "%CD%\\$targetName"),
            shellLines = listOf("cp \"$source\" \"\$PWD/$targetName\""),
        )
    }

    /**
     * Batch lines copying [source] to [destination] the way a converter writes its output: with a
     * fresh mtime. `copy` keeps the source's mtime where `cp` does not, and output older than the
     * run is rejected as a leftover — so a plain `copy` of a fixture made seconds earlier passes only
     * inside the 2s grace. `copy /b f+,, f` is cmd's touch.
     */
    fun freshCopyBatch(source: String, destination: String): List<String> = listOf(
        "copy /Y \"$source\" \"$destination\" >nul",
        "copy /b \"$destination\"+,, \"$destination\" >nul",
    )

    /** A stub that prints [message] and exits with code 1 producing nothing. */
    fun failingTool(oracleHome: Path, name: String, message: String) {
        stubTool(
            oracleHome,
            name,
            batchLines = listOf("echo $message", "exit /b 1"),
            shellLines = listOf("echo $message", "exit 1"),
        )
    }

    /** A stub that sleeps for [seconds] (long enough to trip a short timeout). */
    fun sleepingTool(oracleHome: Path, name: String, seconds: Int) {
        stubTool(oracleHome, name, sleepingBatch(seconds), sleepingShell(seconds))
    }

    /**
     * Lines that wait [seconds] in a **child** process (`ping`, `sleep`) holding the stub's output
     * pipe — the shape of a hung converter under its wrapper script. Killing only the stub leaves
     * the child, and the pipe, alive.
     */
    fun sleepingBatch(seconds: Int): List<String> = listOf("ping -n ${seconds + 1} 127.0.0.1 >nul")

    /** The Unix counterpart of [sleepingBatch]. */
    fun sleepingShell(seconds: Int): List<String> = listOf("sleep $seconds")
}
