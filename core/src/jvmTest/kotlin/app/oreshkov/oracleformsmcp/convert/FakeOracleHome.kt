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
        val bin = binDir(oracleHome)
        if (isWindows) {
            bin.resolve("$name.bat").writeText(
                (listOf("@echo off") + batchLines).joinToString("\r\n") + "\r\n",
            )
        } else {
            val script = bin.resolve(name)
            script.writeText((listOf("#!/bin/sh") + shellLines).joinToString("\n") + "\n")
            script.toFile().setExecutable(true)
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
