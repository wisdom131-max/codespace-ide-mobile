package com.codespace.ide.agent.embedding

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * PROJECT NINE 10.30 - where the model lives so it survives an uninstall.
 *
 * The LSP servers are not backed up by any app code: they are installed inside the Ubuntu
 * container at /opt, and the whole container is archived to public shared storage at
 * CodespaceIDE/container-backup.tar.gz. That folder is outside the app sandbox, so a full
 * uninstall leaves it alone and the next install finds it again.
 *
 * The model follows the same rule rather than inventing a second one. Its home is the same
 * public folder, so an uninstall costs nothing and no download is needed on reinstall. The
 * app's private folder is the fallback for the rare case where public storage is not writable.
 */
object ModelHome {

    private const val FOLDER = "CodespaceIDE"

    fun publicDir(): File = File(Environment.getExternalStorageDirectory(), FOLDER + "/models")

    fun privateDir(context: Context): File = File(context.filesDir, "models")

    /** Public first, because that is the copy that survives an uninstall. */
    fun searchOrder(context: Context): List<File> = listOf(publicDir(), privateDir(context))

    /**
     * The folder to write into. Public storage is probed with a real write rather than trusted,
     * because a granted permission and a writable path are not the same thing on every device.
     */
    fun writableHome(context: Context): File {
        val pub = publicDir()
        val ok = runCatching {
            pub.mkdirs()
            val probe = File(pub, ".write-probe")
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrDefault(false)
        if (ok) return pub
        val priv = privateDir(context)
        priv.mkdirs()
        return priv
    }

    /** True when the model is living in the copy that survives an uninstall. */
    fun isPublic(context: Context): Boolean =
        runCatching { writableHome(context).absolutePath == publicDir().absolutePath }.getOrDefault(false)
}
