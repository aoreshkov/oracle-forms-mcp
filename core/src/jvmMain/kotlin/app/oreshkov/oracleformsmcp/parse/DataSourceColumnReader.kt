package app.oreshkov.oracleformsmcp.parse

import app.oreshkov.oracleformsmcp.model.DataSourceColumnInfo
import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Reads a block's data-source columns out of the block's own XML fragment.
 *
 * Deliberately not part of indexing: one form can carry thousands of these (the same wide table
 * behind several blocks), and they answer one kind of question — which columns exist that no item
 * supplies. So the index only counts them, and this reads the fragment `get_block` already knows
 * how to slice, when that question is asked.
 *
 * Only `DataSourceColumn` elements that are **direct** children of the fragment's root are read,
 * unknown attributes are ignored, and a fragment that does not parse yields what was read before
 * the failure rather than an error — the same never-fail stance as the module parser.
 */
public object DataSourceColumnReader {

    public fun read(blockXml: String): List<DataSourceColumnInfo> {
        val columns = mutableListOf<DataSourceColumnInfo>()
        val reader = factory().createXMLStreamReader(StringReader(blockXml))
        try {
            var depth = 0
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        if (depth == 2 && reader.localName == "DataSourceColumn") columns += reader.column()
                    }
                    XMLStreamConstants.END_ELEMENT -> depth--
                }
            }
        } catch (_: javax.xml.stream.XMLStreamException) {
            // A slice that does not parse to the end still gave us everything before the damage.
        } finally {
            reader.close()
        }
        return columns
    }

    private fun XMLStreamReader.column(): DataSourceColumnInfo = DataSourceColumnInfo(
        name = attr("DSCName").orEmpty(),
        dataType = attr("DSCType")?.takeIf { it.isNotBlank() },
        length = attr("DSCLength")?.trim()?.toIntOrNull() ?: 0,
        precision = attr("DSCPrecision")?.trim()?.toIntOrNull() ?: 0,
        scale = attr("DSCScale")?.trim()?.toIntOrNull() ?: 0,
        mandatory = attr("DSCMandatory").equals("true", ignoreCase = true),
        type = attr("Type")?.takeIf { it.isNotBlank() },
    )

    private fun XMLStreamReader.attr(name: String): String? = getAttributeValue(null, name)

    // One per call, as in the module parser: tool calls run concurrently.
    private fun factory(): XMLInputFactory = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }
}
