package com.codespace.ide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.ui.CodeSpaceApp
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenStore: SecureTokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updateSystemUIForOrientation(resources.configuration.orientation)

        // ── Storage permissions ───────────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ needs MANAGE_EXTERNAL_STORAGE for full file access
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } else {
            val permissions = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1001)
            }
        }

        // ── Battery optimization exemption ────────────────────────────────────
        // Without this, aggressive OEM power managers (TECNO, Infinix, Samsung)
        // send SIGRTMIN (signal 31) to kill terminal processes within seconds.
        // This is exactly the "[Process completed (signal 31)]" crash we see.
        // We request exemption on first launch — user just taps "Allow".
        requestBatteryOptimizationExemption()

        // BusyboxInstaller removed (2026-07-03): Ubuntu proot is the only terminal
        // environment this app ships now — busybox/ash is fully dead code (see AGENTS.md).

        // Pull the most recent crash log (if any) written by CodeSpaceApplication's
        // uncaught-exception handler on the PREVIOUS run. Lets us diagnose real Android
        // crashes (as opposed to terminal child-process crashes) with no ADB needed —
        // user just taps "Copy" and pastes it back to us.
        val lastCrash = readLastCrashLog()

        setContent {
            var crashLogText by remember { mutableStateOf(lastCrash) }
            CodeSpaceApp(tokenStore = tokenStore)
            if (crashLogText != null) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                AlertDialog(
                    onDismissRequest = { crashLogText = null },
                    title = { Text("App crashed last time it closed") },
                    text = {
                        Text(
                            crashLogText!!.take(3000),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText("crash log", crashLogText))
                            crashLogText = null
                        }) { Text("Copy") }
                    },
                    dismissButton = {
                        TextButton(onClick = { crashLogText = null }) { Text("Dismiss") }
                    }
                )
            }
        }
    }

    /** Reads the newest file from filesDir/crash_logs/, if any, and deletes it after reading
     *  so the dialog doesn't repeat on the next launch. */
    private fun readLastCrashLog(): String? {
        return try {
            val dir = File(filesDir, "crash_logs")
            val latest = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return null
            val text = latest.readText()
            dir.listFiles()?.forEach { it.delete() }
            text
        } catch (_: Exception) {
            null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemUIForOrientation(newConfig.orientation)
    }

    private fun updateSystemUIForOrientation(orientation: Int) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            hideSystemUI()
        } else {
            showSystemUI()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.statusBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { ctrl ->
                ctrl.show(WindowInsets.Type.statusBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Some OEMs block this intent — fall back to battery settings page
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) { /* give up gracefully */ }
            }
        }
    }
}
