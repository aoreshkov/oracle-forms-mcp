package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.model.ModuleFingerprint
import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.ProgramUnitInfo
import app.oreshkov.oracleformsmcp.model.ProgramUnitType
import app.oreshkov.oracleformsmcp.model.SourceRef
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.time.Clock

/**
 * Line-based parser for `frmcmp Script=YES` PL/SQL library dumps (`.pld`).
 *
 * A unit starts at a column-0 header (`PROCEDURE x`, `FUNCTION y`, `PACKAGE z`, `PACKAGE BODY z`)
 * and runs until the line before the next header (or EOF) — splitting at headers avoids fragile
 * `END;` matching and stays correct when the word PROCEDURE appears inside a string literal or a
 * nested (indented) declaration. The `.pld` is already plain PL/SQL, so [ProgramUnitInfo.textRef]
 * ranges point straight into it — no sidecar extraction.
 *
 * frmcmp writes in the client NLS charset: decoded as strict UTF-8 first, falling back to
 * windows-1252 on malformed input.
 */
internal object PldParser {

    private val HEADER = Regex(
        """^(PACKAGE\s+BODY|PACKAGE|PROCEDURE|FUNCTION)\s+([A-Za-z0-9_$#]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(key: ModuleKey, pldFile: Path, moduleCacheDir: Path): ModuleIndex {
        val lines = readLines(pldFile)
        val pldPath = cacheRelative(pldFile, moduleCacheDir)

        data class Header(val line: Int, val kind: String, val name: String)

        val headers = lines.mapIndexedNotNull { i, line ->
            HEADER.find(line)?.let { Header(i + 1, it.groupValues[1], it.groupValues[2]) }
        }

        val units = headers.mapIndexed { i, header ->
            val lastLine = if (i + 1 < headers.size) headers[i + 1].line - 1 else lines.size
            // Trim trailing blank separator lines out of the unit's range.
            var endLine = lastLine
            while (endLine > header.line && lines[endLine - 1].isBlank()) endLine--
            ProgramUnitInfo(
                name = header.name.uppercase(),
                unitType = ProgramUnitType.fromForms(header.kind.replace(Regex("\\s+"), " ")),
                lineCount = endLine - header.line + 1,
                textRef = SourceRef(pldPath, header.line, endLine),
            )
        }

        return ModuleIndex(
            key = key,
            sourceFile = pldFile.toString(),
            fingerprint = ModuleFingerprint(0, 0, ""), // stamped by FormsModuleParser/service
            convertedFile = pldPath,
            parsedAt = Clock.System.now(),
            programUnits = units,
        )
    }

    private fun readLines(file: Path): List<String> {
        val bytes = file.readBytes()
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
        return text.replace("\r\n", "\n").split("\n")
    }
}
