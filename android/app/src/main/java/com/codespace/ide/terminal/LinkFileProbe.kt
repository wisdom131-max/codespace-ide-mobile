package com.codespace.ide.terminal

/**
 * BATCH11-S1/S3 (advisor 2026-10-19): the file-system seam for the tap resolver.
 *
 * The resolver previously called File.exists() directly, which made its ordering
 * and caching rules impossible to JVM-test. All existence/kind probes now go
 * through this interface; the REAL implementation is a thin wrapper, tests use
 * an in-memory fake file system (advisor: "focused JVM tests per commit with a
 * fake file system").
 *
 * Pure interface: no Android imports. Implementations must be cheap, never
 * throw (return defaults), and are always invoked OFF the main thread.
 */
fun interface LinkFileProbe {
    /** True when a file/dir exists at [path]. Never throws. */
    fun exists(path: String): Boolean

    /** True when [path] exists AND is a directory. Never throws. */
    fun isDirectory(path: String): Boolean

    companion object {
        /** The production probe: real host file system. */
        val REAL: LinkFileProbe = object : LinkFileProbe {
            override fun exists(path: String): Boolean {
                return try { java.io.File(path).exists() } catch (_: Exception) { false }
            }
            override fun isDirectory(path: String): Boolean {
                return try { java.io.File(path).isDirectory } catch (_: Exception) { false }
            }
        }
    }
}

/**
 * BATCH11-S1: honest outcome classification for a tap resolve. The old
 * TappedLinkResult collapsed every failure into timedOut=true, and the tap
 * handler then printed "(storage busy)" for ALL of them — including plain
 * not-found tokens (the advisor's "the message names the wrong cause").
 * Each reason now maps to its own user-facing wording.
 */
enum class LinkResolveReason {
    RESOLVED,
    /** Every branch was probed inside the time budget; nothing matched. */
    NOT_FOUND,
    /** The 3s probe budget really was exceeded (this is the ONLY case where
     *  "storage busy" is an honest suggestion). */
    TIMEOUT,
    /** The resolve threw; errorClass carries the throwable's class name. */
    ERROR,
    /** BATCH11-S3: real tie between distinct existing files (design item 6:
     *  "real ties show a choice or a clear message, never a silent pick").
     *  ambiguityPaths carries the list. */
    AMBIGUOUS,
    /** BATCH11-S3: the only match was a DIRECTORY (design item 6: a directory
     *  is never opened as a file). */
    DIRECTORY,
}
