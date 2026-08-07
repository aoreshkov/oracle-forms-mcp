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
            batchLines = listOf("copy /Y \"$source\" \"%CD%\\$targetName\" >nul"),
            shellLines = listOf("cp \"$source\" \"\$PWD/$targetName\""),
        )
    }

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
        stubTool(
            oracleHome,
            name,
            batchLines = listOf("ping -n ${seconds + 1} 127.0.0.1 >nul"),
            shellLines = listOf("sleep $seconds"),
        )
    }
}
