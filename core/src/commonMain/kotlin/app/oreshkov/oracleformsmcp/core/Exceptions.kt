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
    "Module '$key' was indexed by a ${if (found < current) "older" else "newer"} build of this " +
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
