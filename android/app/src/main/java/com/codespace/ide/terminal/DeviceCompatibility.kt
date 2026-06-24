package com.codespace.ide.terminal

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log

class DeviceCompatibility(private val context: Context) {

    fun isLowEndDevice(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        // Use actual total RAM (not per-app memoryClass which is often 128–256MB
        // even on high-end devices and causes Ubuntu to be hidden incorrectly)
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val totalRamMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val lowRam = totalRamMb < 1024  // require at least 1GB actual RAM

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
