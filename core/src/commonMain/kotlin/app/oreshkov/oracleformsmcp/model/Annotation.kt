package app.oreshkov.oracleformsmcp.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Who asserted an [Annotation] or [Relation]: the model, or a human operator. */
@Serializable
@SerialName("Author")
public enum class Author { AI, USER }

/**
 * What an [Annotation] asserts about an element: a free-form [NOTE] or [SUMMARY], a short [TAG]
 * label, or a [CLASSIFICATION] (e.g. `security-sensitive`, `deprecated`).
 */
@Serializable
@SerialName("AnnotationKind")
public enum class AnnotationKind { NOTE, TAG, SUMMARY, CLASSIFICATION }

/**
 * One piece of meta-information attached to a single element ([target]). Unlike everything else in
 * a [ModuleIndex], an annotation is *asserted about* the element rather than *parsed from* it, so
 * it is stored outside the derived, fingerprinted cache and persists across re-conversions.
 *
 * [sourceFingerprint] snapshots the module's source at the moment the annotation was written; a
 * later mismatch against the file on disk means the note predates the current source (surfaced as
 * a drift flag when served) — it is never a reason to delete the annotation.
 */
@Serializable
@SerialName("Annotation")
public data class Annotation(
    val id: String,
    val target: ElementId,
    val kind: AnnotationKind,
    val body: String,
    val author: Author = Author.AI,
    val createdAt: Instant,
    val sourceFingerprint: ModuleFingerprint? = null,
)

/**
 * A directed cross-reference between two elements ([from] → [to]), stored in active voice like the
 * reference MCP memory server's relations (`calls`, `feeds`, `references`, `deprecated-by`, …).
 * Both endpoints belong to the same module. See [Annotation] for the [sourceFingerprint] contract.
 */
@Serializable
@SerialName("Relation")
public data class Relation(
    val id: String,
    val from: ElementId,
    val to: ElementId,
    val relType: String,
    val note: String? = null,
    val author: Author = Author.AI,
    val createdAt: Instant,
    val sourceFingerprint: ModuleFingerprint? = null,
)

/**
 * The full set of annotations and relations persisted for one module — the on-disk unit the
 * [app.oreshkov.oracleformsmcp.core.AnnotationStore] reads and writes, one file per [ModuleKey].
 */
@Serializable
@SerialName("ModuleAnnotations")
public data class ModuleAnnotations(
    val module: ModuleKey,
    val annotations: List<Annotation> = emptyList(),
    val relations: List<Relation> = emptyList(),
)
