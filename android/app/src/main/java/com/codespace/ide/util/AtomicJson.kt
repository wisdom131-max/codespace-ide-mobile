package com.codespace.ide.util

import android.util.Log
import java.io.File

/**
 * AtomicJson — the ONE shared atomic-write utility for the integration JSON stores
 * (SK01 corruption family, IG16 batch 2026-09-27).
 *
 * Plain writeText() is not atomic: a process death or disk-full mid-write leaves a
 * truncated JSON file, and every one of these stores degrades a corrupt parse to
 * an EMPTY object — one bad write silently wipes the whole store (agent memory,
 * scheduled tasks, SSH profiles, entity records).
 *
 * Write-to-temp + rename: rename within the same directory is atomic on POSIX, so
 * the file is either the OLD complete content or the NEW complete content, never a
 * truncated mix. Defensive copy+delete fallback for this device family's history
 * of vendor-kernel syscall surprises (rename may fail where copy does not) — the
 * fallback is NOT atomic, but each store's corrupt-parse quarantine covers it.
 *
 * Same shape as JsonSettingsStore.atomicWrite (the SK01 original), now shared.
 */
object AtomicJson {

    private const val TAG = "AtomicJson"

    /** Atomically write content to file. Returns false (and logs) on total failure. */
    fun write(file: File, content: String): Boolean {
        val dir = file.parentFile
        if (dir != null) dir.mkdirs()
        val tmp = File(dir ?: File("/"), file.name + ".tmp")
        return try {
            tmp.writeText(content)
            if (tmp.renameTo(file)) return true
            Log.w(TAG, "Rename failed for ${file.name} — falling back to copy+delete")
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Atomic write failed for ${file.name}", e)
            try { tmp.delete() } catch (_: Exception) {}
            false
        }
    }
}
