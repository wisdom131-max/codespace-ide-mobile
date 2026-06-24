package com.codespace.ide.terminal

import android.content.Context
import org.json.JSONArray
import java.io.File

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

    fun save(ctx: Context, profiles: List<SshProfile>) {
        try {
            val arr = JSONArray()
            profiles.forEach { arr.put(it.toJson()) }
            file(ctx).writeText(arr.toString(2))
        } catch (_: Exception) {}
    }
}
