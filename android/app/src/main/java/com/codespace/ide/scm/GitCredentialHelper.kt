package com.codespace.ide.scm

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * P2c — transient git credential helper (SG04/SG16, 2026-09-25).
 *
 * The old dialect put the GitHub token in the git COMMAND STRING as
 * `-c http.extraheader='Authorization: Basic <base64-of-token>'` — it rode the
 * argv of the proot process, so any AI-executed ps or /proc cmdline read
 * inside the guest saw it, and any command log/notification carrying the
 * command leaked it too.
 *
 * New dialect: git's credential-helper protocol via a throwaway script inside
 * the guest /tmp. The command line carries only the script's random path —
 * the token lives in the script file for the duration of ONE git operation and
 * is deleted immediately after. Guest processes could read the transient file
 * during that window (they are the app UID and can read app storage anyway);
 * what is gone for good is the persistent, greppable argv/command-string leak.
 */
object GitCredentialHelper {

    data class Helper(
        /** The path git sees inside the guest (/tmp/...). */
        val guestPath: String,
        /** The host-side file (rootfs/tmp/...). */
        val hostFile: File,
    )

    /** Write a one-shot credential helper carrying the token; random name, no token in the path. */
    fun write(context: Context, token: String): Helper {
        val rnd = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val suffix = rnd.joinToString("") { String.format("%02x", it) }
        val dir = File(com.codespace.ide.terminal.ProotInstaller.rootfsDir(context), "tmp")
        dir.mkdirs()
        val f = File(dir, ".gitcred-$suffix")
        f.writeText("#!/bin/sh\necho username=x-access-token\necho password=$token\n")
        f.setExecutable(true, false)
        f.setReadable(true, false)
        return Helper("/tmp/.gitcred-$suffix", f)
    }

    /** Delete the helper. Call in a finally block, always. */
    fun delete(helper: Helper?) {
        helper?.hostFile?.delete()
    }
}
