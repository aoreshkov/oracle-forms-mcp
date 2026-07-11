package app.oreshkov.oracleformsmcp.core

import app.oreshkov.oracleformsmcp.model.ModuleIndex
import app.oreshkov.oracleformsmcp.model.ModuleKey

/**
 * Port for the on-disk cache keyed by [ModuleKey]. Tools read through it so a fetched module is
 * never re-converted or re-parsed while its fingerprint still matches.
 *
 * All operations suspend: the JVM implementation touches the filesystem, so suspending keeps
 * callers off blocking IO on their dispatcher.
 */
public interface ModuleCache {

    /** The cached index for [key], or `null` on a cache miss (including a corrupt entry). */
    public suspend fun get(key: ModuleKey): ModuleIndex?

    /** Stores (or replaces) the parsed index for a module. */
    public suspend fun putIndex(index: ModuleIndex)

    /** Every module currently held in the cache. */
    public suspend fun list(): List<ModuleKey>

    /** Evicts a single module's converted files, sidecars, and index. */
    public suspend fun clear(key: ModuleKey)

    /** Total size of the cache on disk, in bytes. */
    public suspend fun size(): Long

    /**
     * Absolute directory holding [key]'s cache entry. Shared with the converter (which writes
     * `converted/` there) and the parser (which writes `plsql/` sidecars), so all [SourceRef]
     * paths resolve against one root.
     */
    public fun moduleDir(key: ModuleKey): String
}
