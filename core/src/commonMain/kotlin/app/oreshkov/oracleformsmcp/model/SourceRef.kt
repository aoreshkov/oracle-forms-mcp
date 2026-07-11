package app.oreshkov.oracleformsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A 1-based, inclusive line range inside a file under the module's cache directory. [file] is a
 * cache-relative path (e.g. `converted/orders_fmb.xml` or `plsql/triggers/ORDERS.KEY-COMMIT.sql`)
 * so refs stay valid wherever the cache root lives.
 *
 * Line ranges (not char offsets) because StAX character offsets are implementation-specified,
 * while line numbers are reliable — and slicing by lines is trivial to test.
 */
@Serializable
@SerialName("SourceRef")
public data class SourceRef(
    val file: String,
    val startLine: Int,
    val endLine: Int,
) {
    init {
        require(file.isNotBlank()) { "file must not be blank" }
        require(startLine >= 1) { "startLine must be >= 1, was $startLine" }
        require(endLine >= startLine) { "endLine ($endLine) must be >= startLine ($startLine)" }
    }
}
