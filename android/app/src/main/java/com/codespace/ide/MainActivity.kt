package com.codespace.ide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.util.WorkspaceManager
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

        // P7-3 Safe mode: record this launch attempt. If the app has crashed
        // 3+ times in a row (each launch ended within 60s), isSafeMode() returns true.
        WorkspaceManager.recordLaunch(this)
        // Mark stable after 60s of uptime — resets the crash counter.
        Handler(Looper.getMainLooper()).postDelayed({
            WorkspaceManager.recordStable(this)
        }, 60_000L)

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

        // FIXED 2026-07-03: "terminal fills the screen on open, fixes itself after rotating."
        // enableEdgeToEdge() + the SplashScreen API can leave the FIRST WindowInsets dispatch
        // to the freshly-created Compose hierarchy stale/incomplete (a known interaction
        // between the two APIs) -- Compose's content lays out as if there are zero system-bar
        // insets to subtract, so the terminal/editor area renders oversized until something
        // (like a rotation) forces Android to redeliver a fresh WindowInsets pass. Force that
        // redelivery ourselves right after the content is set, instead of waiting on the user
        // to accidentally trigger it via rotation.
        window.decorView.post { ViewCompat.requestApplyInsets(window.decorView) }
        val inSafeMode = WorkspaceManager.isSafeMode(this)
        setContent {
            var crashLogText by remember { mutableStateOf(lastCrash) }
            var showSafeMode by remember { mutableStateOf(inSafeMode) }

            // P7-3 Safe Mode dialog — shown instead of normal startup when crash count >= 3
            if (showSafeMode) {
                val orientation = LocalConfiguration.current.orientation
                key(orientation) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Safe Mode") },
                        text  = {
                            Text(
                                "The app crashed ${WorkspaceManager.crashCount(applicationContext)} times in a row.

" +
                                "Safe mode skips auto-opening projects and terminal sessions.

" +
                                "Tap 'Continue' to proceed normally, or 'Reset' to clear crash history.",
                                fontSize = 13.sp,
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    WorkspaceManager.resetSafeMode(applicationContext)
                                    showSafeMode = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC)),
                            ) { Text("Continue Normally") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showSafeMode = false }) {
                                Text("Enter Safe Mode")
                            }
                        },
                    )
                }
            } else {
                CodeSpaceApp(tokenStore = tokenStore, safeMode = inSafeMode)
            }
            if (crashLogText != null) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                // Rotation fix (#8): key on orientation so this AlertDialog gets a fresh,
                // correctly-sized window on rotate (this app doesn't recreate the Activity
                // on rotation — configChanges="orientation|screenSize" — so the Dialog's
                // window would otherwise stay stuck at the old size).
                val orientation = LocalConfiguration.current.orientation
                key(orientation) {
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
            } // end else (not safe mode)
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
                .joinToString("\n\n---\n\n") { it.readText() }

            // Upload in a BACKGROUND thread — NOT on the main thread.
            // The previous version did a blocking network call (uploadCrashLogToAgent)
            // right here on the main thread during onCreate(). On Android, network I/O
            // on the main thread throws NetworkOnMainThreadException (caught, but the
            // 6-second timeout also causes ANR if it somehow doesn't throw). This was
            // a major contributor to "app opens then closes immediately on reopen after
            // minimize": if crash logs existed from a previous crash, every reopen
            // would block/throw on the network call before the UI could render.
            //
            // Now: read the local file (fast), return the text immediately so the dialog
            // can show, and upload + delete in the background. If the upload fails, the
            // files stay on disk for the next attempt.
            Thread {
                try {
                    uploadCrashLogToAgent(combined)
                    files.forEach { it.delete() }
                } catch (_: Exception) { /* keep files for next attempt */ }
            }.start()

            combined
        } catch (_: Exception) {
            null
        }
    }

    /** Same reportCrash endpoint the JVM crash logger POSTs to -- this is the path that
     *  actually gets a native-signal crash (which has no Java stack trace) to the agent.
     *  BLOCKING by design now (see readLastCrashLog) -- throws on any failure so the
     *  caller knows NOT to delete the local copy yet. */
    private fun uploadCrashLogToAgent(text: String) {
        val url = URL("https://superagent-4bfc55af.base44.app/functions/reportCrash")
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
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) throw java.io.IOException("reportCrash returned HTTP $code")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemUIForOrientation(newConfig.orientation)
    }

    // FIXED 2026-07-03: "back button didn't work, couldn't get to home screen to create a
    // new project" -- happens specifically while the terminal has focus. Traced through
    // Termux's own TerminalView.java (decompiled): a focused TerminalView is a legitimate
    // KeyEvent.Callback, and depending on IME/selection state it can consume KEYCODE_BACK
    // before it ever reaches the Activity's onBackPressed()/OnBackPressedDispatcher, so our
    // Compose BackHandler in ProjectShellScreen never fires. Activity.dispatchKeyEvent() runs
    // BEFORE the event is routed to any child view, so intercepting BACK here guarantees the
    // dispatcher (and therefore the BackHandler) always gets it, regardless of what has focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                onBackPressedDispatcher.onBackPressed()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
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
        // Only ask ONCE — repeated intents on every onCreate() can interfere with startup
        // and cause the "app flashes then closes" symptom on some OEM devices.
        val prefs = getSharedPreferences("app_prefs", 0)
        if (prefs.getBoolean("battery_optimization_requested", false)) return

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("battery_optimization_requested", true).apply()
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
