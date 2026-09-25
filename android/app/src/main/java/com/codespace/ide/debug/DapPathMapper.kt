package com.codespace.ide.debug

import android.content.Context
import com.codespace.ide.terminal.ProotInstaller
import java.io.File

/**
 * DG02 (P3b, 2026-09-25): ONE host→guest translation for DAP source.path.
 *
 * Root cause: the launch path converted breakpoint source paths host→guest, but
 * the LIVE sendBreakpoints path sent raw host paths — two different source
 * identities for one file, so live-added/removed/edited breakpoints never bind
 * during a session. NodeDAPAdapter carried the false "already a guest path"
 * comment (UDM's breakpoint store is host-keyed).
 *
 * Rule (defensive in both dialects): if a HOST file exists at the given path,
 * it is host-form — translate host→guest exactly as launch does. If no host
 * file exists, assume it is already guest-form and pass it through unchanged.
 */
object DapPathMapper {

    fun toDapSourcePath(context: Context, path: String): String {
        if (!File(path).exists()) return path
        return ProotInstaller.hostToGuestPath(context, path)
            ?: "/host-files/" + path.removePrefix(context.filesDir.absolutePath + "/")
    }
}
