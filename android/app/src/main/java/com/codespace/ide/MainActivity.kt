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
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    /**
     * Reads ALL files from filesDir/crash_logs/ (both the JVM crash logger's timestamped
     * files AND the native signal handler's native_crash_pending.txt -- see
     * CodeSpaceApplication for both), combines them, uploads the combined text to the
     * agent's reportCrash function in the background (covers native crashes, which can't
     * safely make a network call from inside a signal handler), and deletes the files so
     * the dialog doesn't repeat on the next launch.
     */
    private fun readLastCrashLog(): String? {
        return try {
            val dir = File(filesDir, "crash_logs")
            val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: return null
            if (files.isEmpty()) return null
            val combined = files.sortedByDescending { it.lastModified() }
                .joinToString("

---

") { it.readText() }
            files.forEach { it.delete() }

            Thread {
                try { uploadCrashLogToAgent(combined) } catch (_: Exception) { /* best-effort */ }
            }.apply { isDaemon = true }.start()

            combined
        } catch (_: Exception) {
            null
        }
    }

    /** Same reportCrash endpoint the JVM crash logger POSTs to -- this is the path that
     *  actually gets a native-signal crash (which has no Java stack trace) to the agent. */
    private fun uploadCrashLogToAgent(text: String) {
        val url = URL("https://superagent-7c842a7e.base44.app/functions/reportCrash")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.setRequestProperty("Content-Type", "application/json")

        val body = JSONObject().apply {
            put("app_package", packageName)
            put("device_model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("thread_name", "recovered_on_next_launch")
            put("stack_trace", text)
            put("app_version", BuildConfig.VERSION_NAME)
            put("device_timestamp", SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()))
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        conn.responseCode
        conn.disconnect()
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
