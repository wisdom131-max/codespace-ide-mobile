package com.codespace.ide.terminal

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log

class DeviceCompatibility(private val context: Context) {
    fun isLowEndDevice(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memClassMb = activityManager?.memoryClass ?: 256
        val largeMemClassMb = activityManager?.largeMemoryClass ?: memClassMb
        val usableRamMb = minOf(memClassMb, largeMemClassMb)
        val lowRam = usableRamMb < 256

        val storageFreeBytes = try {
            StatFs(context.filesDir.absolutePath).availableBytes
        } catch (t: Throwable) {
            Log.w("DeviceCompatibility", "Unable to inspect storage", t)
            Long.MAX_VALUE
        }
        val lowStorage = storageFreeBytes < 512L * 1024L * 1024L

        val oldSdk = Build.VERSION.SDK_INT <= Build.VERSION_CODES.M
        return lowRam || lowStorage || oldSdk
    }

    fun shouldUseOfflineOnly(): Boolean = isLowEndDevice()

    fun recommendedMode(): String = if (shouldUseOfflineOnly()) "offline" else "full"

    fun canInstallUbuntuRootfs(): Boolean {
        val storageFreeBytes = try {
            StatFs(context.filesDir.absolutePath).availableBytes
        } catch (t: Throwable) {
            Long.MAX_VALUE
        }
        return !shouldUseOfflineOnly() && storageFreeBytes > 1_000L * 1024L * 1024L
    }
}
