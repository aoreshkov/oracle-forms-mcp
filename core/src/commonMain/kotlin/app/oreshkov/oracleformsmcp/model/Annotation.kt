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
 * The on-disk format of [ModuleAnnotations] that this build writes.
 *
 * Unlike the module index, annotations cannot be rebuilt from source, so a format change cannot be
 * handled by discarding what no longer decodes: the store has to know which format it is reading
 * to migrate it, and must refuse to rewrite a file from a newer build, whose fields it would drop.
 * **Bump this whenever a stored field is renamed, removed, or changes meaning**, and migrate the
 * older formats on read.
 *
 * `0` is the implicit format of every file written before the stamp existed, which is what the
 * default on [ModuleAnnotations.formatVersion] gives them. History: `1` added the stamp and
 * changed nothing else, so a `0` file reads as a `1` file.
 */
public const val CURRENT_ANNOTATIONS_FORMAT_VERSION: Int = 1

/**
 * The full set of annotations and relations persisted for one module — the on-disk unit the
 * [app.oreshkov.oracleformsmcp.core.AnnotationStore] reads and writes, one file per [ModuleKey].
 * [formatVersion] is the format the file was written in (see [CURRENT_ANNOTATIONS_FORMAT_VERSION]).
 */
@Serializable
@SerialName("ModuleAnnotations")
public data class ModuleAnnotations(
    val module: ModuleKey,
    val annotations: List<Annotation> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val formatVersion: Int = 0,
)
