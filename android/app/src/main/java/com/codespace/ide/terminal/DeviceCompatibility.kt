package com.codespace.ide.terminal

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log

class DeviceCompatibility(private val context: Context) {

    fun isLowEndDevice(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        // Read physical RAM from /proc/meminfo (MemTotal) — more accurate than
        // ActivityManager.totalMem which excludes kernel-reserved memory and can
        // report ~2288MB on a 2855MB device (difference: ~567MB kernel overhead).
        val totalRamMb = try {
            val line = java.io.File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemTotal:") } ?: ""
            line.filter { it.isDigit() }.toIntOrNull()?.let { it / 1024 } ?: run {
                // Fallback to ActivityManager if /proc/meminfo unavailable
                val memInfo = ActivityManager.MemoryInfo()
                activityManager?.getMemoryInfo(memInfo)
                (memInfo.totalMem / (1024 * 1024)).toInt()
            }
        } catch (_: Exception) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            (memInfo.totalMem / (1024 * 1024)).toInt()
        }
        val lowRam = totalRamMb < 1024  // require at least 1GB physical RAM

        val storageFreeBytes = try {
            StatFs(context.filesDir.absolutePath).availableBytes
        } catch (t: Throwable) {
            Log.w("DeviceCompatibility", "Unable to inspect storage", t)
            Long.MAX_VALUE
        }
        val lowStorage = storageFreeBytes < 512L * 1024L * 1024L
        val oldSdk = Build.VERSION.SDK_INT < Build.VERSION_CODES.N  // require Android 7+

        return lowRam || lowStorage || oldSdk
    }

    fun shouldUseOfflineOnly(): Boolean = false  // always allow Ubuntu; isLowEndDevice is advisory only

    fun recommendedMode(): String = if (isLowEndDevice()) "offline" else "full"

    fun canInstallUbuntuRootfs(): Boolean {
        val storageFreeBytes = try {
            StatFs(context.filesDir.absolutePath).availableBytes
        } catch (t: Throwable) {
            Long.MAX_VALUE
        }
        return storageFreeBytes > 1_000L * 1024L * 1024L
    }
}
