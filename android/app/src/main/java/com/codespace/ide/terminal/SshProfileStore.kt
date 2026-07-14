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
            file(ctx).writeText(arr.toString(2))
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
