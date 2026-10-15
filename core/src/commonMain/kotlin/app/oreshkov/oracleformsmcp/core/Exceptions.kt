package app.oreshkov.oracleformsmcp.core

import app.oreshkov.oracleformsmcp.model.ModuleKey

/*
 * Expected failure modes, converted by the tool layer into `isError` results the model can read.
 * Every message tells the model what to do next.
 */

/** A read tool was called for a module that has no cache entry yet. */
public class ModuleNotFetchedException(key: ModuleKey) : Exception(
    "Module '$key' is not fetched yet. Call fetch_module with module \"$key\" first.",
)

/** The cached index no longer matches the file on disk. */
public class ModuleStaleException(key: ModuleKey) : Exception(
    "Module '$key' changed on disk since it was indexed. " +
        "Call fetch_module with module \"$key\" to re-index it.",
)

/**
 * The cached index was written by a different parser than the one now serving it.
 *
 * The source file is unchanged, so nothing else marks this entry as anything but current — which
 * is the whole problem: the facts in it are the other build's, and they are wrong in ways that
 * read as plausible (an inherited trigger reported empty, a whole procedure reported as one line).
 * A downgrade is no safer than an upgrade — an index read back by an older parser loses whatever
 * it does not know — so any difference is reported, not only a lower version.
 *
 * The repair re-parses the converted file that is already in the cache entry, and the message says
 * so: a caller weighing a re-fetch on a large directory should know that no conversion runs.
 */
public class ModuleIndexOutdatedException(key: ModuleKey, found: Int, current: Int) : Exception(
    "Module '$key' was indexed by ${if (found < current) "an older" else "a newer"} build of this " +
        "server (index v$found, current v$current), so its facts may be incomplete. Call " +
        "fetch_module with module \"$key\" to re-index it — the converted file is reused, so no " +
        "conversion runs.",
)

/** Base type for conversion failures. */
public sealed class ConversionException(message: String) : Exception(message)

/** `ORACLE_HOME` is set but the required tool is not in its `bin` directory. */
public class ConverterNotFoundException(message: String) : ConversionException(message)

/** The external tool ran but produced no usable output. */
public class ConversionFailedException(message: String) : ConversionException(message)

/** The external tool exceeded the conversion timeout and was killed. */
public class ConversionTimeoutException(message: String) : ConversionException(message)

/** Copy mode found no pre-converted file for the module. */
public class PreConvertedFileMissingException(message: String) : ConversionException(message)

/**
 * A write found the module's stored annotations undecodable — damaged, or in a shape this build
 * cannot read. Annotations are asserted, so nothing can rebuild them: the store moved the file
 * aside as [keptAs] instead of writing over it, and wrote nothing.
 */
public class AnnotationsUnreadableException(key: ModuleKey, keptAs: String) : Exception(
    "The stored annotations for module '$key' could not be read, so this call changed nothing. " +
        "The unreadable file was kept as '$keptAs' in the annotation store: tell the user, since " +
        "its annotations can only be recovered by hand. Until then get_element_annotations reports " +
        "none for '$key', and the next annotate_element or relate_elements call starts a new set.",
)

/**
 * A write found the module's annotations written by a newer build. Rewriting them here would drop
 * whatever that build stores and this one does not know, so the file is left untouched.
 */
public class AnnotationsFormatTooNewException(key: ModuleKey, found: Int, current: Int) : Exception(
    "The stored annotations for module '$key' were written by a newer build of this server " +
        "(format v$found, this build writes v$current), so this call changed nothing — rewriting " +
        "them would lose what that build stored. They are left as they are; upgrade the server to " +
        "change them.",
)
