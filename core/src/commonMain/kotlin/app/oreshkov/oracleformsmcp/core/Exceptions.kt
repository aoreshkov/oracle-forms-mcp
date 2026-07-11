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
