package com.codespace.ide.terminal

/**
 * TP03 (P3a, 2026-09-25) — the typed proot execution result.
 *
 * S01 root cause at the proot boundary: execOnce/execOnceWithProcess return a
 * single String in which success output, "Timed out after Ns", "Exit code N"
 * and "Error: ..." are indistinguishable BY TYPE, so every caller either
 * string-matches prefixes (XG05's prose matching) or greps markers that a
 * truncated capture can lose (PR03's BUILD SUCCESSFUL swallow). PR01 (build
 * status clobber), PR03 (marker swallow), SR03 (replace reporting) all inherit
 * from this seam.
 *
 * The migration contract:
 *  - [ProotInstaller.execTyped] is the ONE real implementation and always
 *    returns ProotResult.
 *  - The legacy String executors remain as thin wrappers that render the SAME
 *    byte-identical strings as before, so the ~44 un-migrated call sites keep
 *    their exact behavior while members migrate to the typed API.
 *
 * `exitCode == null` means the process never exited normally: either it was
 * destroyed after a timeout ([timedOut]) or it failed to launch
 * ([launchError] non-null). `succeeded` is the single honest status field.
 */
data class ProotResult(
    /** Noise-stripped stdout (untrimmed, like the legacy raw capture). */
    val stdout: String,
    /** Process exit code. null = never exited (timeout or launch failure). */
    val exitCode: Int?,
    /** True when the process was destroyed after [timeoutSeconds]. */
    val timedOut: Boolean,
    /** True when output capture hit the maxLines cap (late lines were lost). */
    val truncated: Boolean,
    /** Exception message when the command could not be launched at all. */
    val launchError: String?,
) {
    /** The single authoritative status: exit 0, no timeout, no launch error. */
    val succeeded: Boolean
        get() = !timedOut && launchError == null && exitCode == 0
}
