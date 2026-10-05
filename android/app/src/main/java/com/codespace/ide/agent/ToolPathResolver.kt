package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.security.TrustState

/** One host-path identity for direct file tools, staged edits, Apply and Force apply. */
object ToolPathResolver {
    /**
     * V0-e (2026-10-05): THIN FORWARDER to the resolver core
     * (resolver/PathResolver.toHost -> resolver/HostResolution.toHostPath).
     * The branch tree was lifted VERBATIM into HostResolution and is JVM-tested
     * there; this signature stays so every existing caller compiles unchanged
     * (staging = revertability only).
     */
    fun resolve(context: Context, raw: String, projectRoot: String? = TrustState.activeProjectRoot(context)): String =
        com.codespace.ide.resolver.PathResolver.toHost(context, raw, projectRoot)
}
