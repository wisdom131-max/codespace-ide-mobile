package com.codespace.ide.terminal

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * P3 fix: save() now returns a Result so callers can surface write failures
 * instead of silently losing profile data on storage-full / permission errors.
 */
object SshProfileStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "ssh-profiles.json")

    fun load(ctx: Context): MutableList<SshProfile> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { SshProfile.fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    /** @return [Result.success] on write OK, [Result.failure] with the [IOException] on error. */
    fun save(ctx: Context, profiles: List<SshProfile>): Result<Unit> {
        return try {
            val arr = JSONArray()
            profiles.forEach { arr.put(it.toJson()) }
            // IG16 (2026-09-27): non-atomic writeText DELETED — SSH profiles now go
            // through the shared SK01-family atomic write (temp + rename).
            if (!com.codespace.ide.util.AtomicJson.write(file(ctx), arr.toString(2))) {
                throw IOException("Atomic write failed for SSH profiles")
            }
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
