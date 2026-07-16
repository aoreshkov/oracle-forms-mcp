package app.oreshkov.oracleformsmcp.core

import app.oreshkov.oracleformsmcp.model.Annotation
import app.oreshkov.oracleformsmcp.model.ModuleAnnotations
import app.oreshkov.oracleformsmcp.model.ModuleKey
import app.oreshkov.oracleformsmcp.model.Relation

/**
 * Port for the durable store of AI/user-supplied annotations and relations, keyed by
 * [ModuleKey]. Deliberately separate from [ModuleCache]: annotations are *not* re-derivable from
 * source, so they must survive `fetch_module` re-conversions and cache eviction. An annotation is
 * filed under its target element's module ([Annotation.target]); a relation under its [from]
 * endpoint's module.
 *
 * All operations suspend because the JVM implementation touches the filesystem.
 */
public interface AnnotationStore {

    /** Adds (or replaces, by [Annotation.id]) one annotation. */
    public suspend fun addAnnotation(annotation: Annotation)

    /** Adds (or replaces, by [Relation.id]) one relation. */
    public suspend fun addRelation(relation: Relation)

    /** Everything stored for [module]; an empty (but non-null) record when nothing is stored. */
    public suspend fun forModule(module: ModuleKey): ModuleAnnotations

    /** Removes the annotation or relation with [id] from [module]; `true` when something was removed. */
    public suspend fun remove(module: ModuleKey, id: String): Boolean

    /** Drops every annotation and relation for [module]. */
    public suspend fun clear(module: ModuleKey)
}
