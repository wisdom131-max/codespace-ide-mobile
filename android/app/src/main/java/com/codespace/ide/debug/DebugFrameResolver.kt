package com.codespace.ide.debug

import java.io.File

/**
 * BATCH6-P2 (advisor C, 2026-10-17): resolve a BARE source name reported by a
 * debugger frame (e.g. "DebugJava.java") against the debuggee's OWN context.
 *
 * Owner evidence ([BAND-DIAG], build 2c9e9a9): the JVM debugger reports the
 * paused frame's file as the bare name, so canonicalHostKey returns null and
 * the paused band never matched the open tab. Variables DID arrive
 * (frames=1 vars=3) — the miss is purely the file-identity hop.
 *
 * Rules (advisor ruling):
 *  - Search ONLY the launched file's directory and its ancestors (bounded),
 *    plus any provided project/source roots (depth-1). NEVER a global basename
 *    match across the project (DG11/LS06 removed that family for squiggles).
 *  - If TWO distinct existing files match the name, return null — show NO band
 *    rather than the wrong one.
 *  - exists() is injected, so the decision core is pure and JVM-unit-tested.
 */
object DebugFrameResolver {

    /** Bound on the ancestor walk — a launched file deep in a tree still finds
     * its source root without walking to the filesystem root. */
    const val MAX_ANCESTOR_HOPS = 8

    fun resolveBareFrameFile(
        bareName: String,
        launchedPath: String,
        roots: List<String> = emptyList(),
        exists: (String) -> Boolean = { File(it).exists() },
    ): String? {
        if (bareName.isBlank() || bareName.contains('/')) return null
        val launchedDir = File(launchedPath).parentFile?.path ?: return null
        val candidates = LinkedHashSet<String>()
        // 1. the debuggee's own package folder, then the source tree above it
        var dir: File? = File(launchedDir)
        var hops = 0
        while (dir != null && hops < MAX_ANCESTOR_HOPS) {
            candidates.add(File(dir, bareName).path)
            dir = dir.parentFile
            hops++
        }
        // 2. project / source roots, depth-1
        for (root in roots) {
            val r = root.trimEnd('/')
            if (r.isNotEmpty()) candidates.add("$r/$bareName")
        }
        val hits = candidates.filter { exists(it) }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 -> hits[0]
            else -> null // ambiguity: NO band rather than the wrong one
        }
    }

    /**
     * BATCH6-P2c (advisor 2c): the FULL frame-file normalizer, pure and
     * exists-injected. One shared rule set for every language adapter's frames:
     *  - ABSOLUTE path (starts with '/') -> returned AS-IS (host translation is
     *    the caller's job: canonicalHostKey);
     *  - BARE name (no '/') -> resolveBareFrameFile (launched dir + bounded
     *    ancestors + roots, depth-1);
     *  - RELATIVE path (contains '/' but not leading) -> same search context,
     *    joined as dir/<relative>;
     *  - blank / unresolvable / AMBIGUOUS -> null = NO band. Never a global
     *    basename match (LS06/DG11).
     */
    fun resolveFrameFile(
        frameFile: String,
        launchedPath: String,
        roots: List<String> = emptyList(),
        exists: (String) -> Boolean = { File(it).exists() },
    ): String? {
        val f = frameFile.trim()
        if (f.isEmpty()) return null
        if (f.startsWith("/")) return f
        if (!f.contains('/')) {
            return resolveBareFrameFile(f, launchedPath, roots, exists)
        }
        val launchedDir = File(launchedPath).parentFile?.path ?: return null
        val candidates = LinkedHashSet<String>()
        var dir: File? = File(launchedDir)
        var hops = 0
        while (dir != null && hops < MAX_ANCESTOR_HOPS) {
            candidates.add(File(dir, f).path)
            dir = dir.parentFile
            hops++
        }
        for (root in roots) {
            val r = root.trimEnd('/')
            if (r.isNotEmpty()) candidates.add("$r/$f")
        }
        val hits = candidates.filter { exists(it) }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 -> hits[0]
            else -> null // ambiguity: NO band rather than the wrong one
        }
    }
}
