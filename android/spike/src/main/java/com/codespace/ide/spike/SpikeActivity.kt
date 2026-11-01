package com.codespace.ide.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The spike app is now a browser bench, nothing else.
 *
 * It installs beside the main app under its own package, so it can be uninstalled without
 * touching IDE data. Its only job is to answer "does the browser behave the way I want" on
 * this device before any of it is wired into the preview panel.
 */
class SpikeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF101012),
                    surface = Color(0xFF1B1B1F),
                    primary = Color(0xFF4FC3F7),
                    onBackground = Color(0xFFE8E8EA),
                    onSurface = Color(0xFFE8E8EA),
                ),
            ) {
                BrowserScreen()
            }
        }
    }
}
