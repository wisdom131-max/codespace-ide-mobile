package com.codespace.ide.ui.panes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// "Generate Image with AI Here" — added 2026-07-08. Triggered from ExplorerPane's
// folder context menu (right next to "Import Image(s) Here"). Kept as its own small
// file rather than inlined into the already-large ExplorerPane.kt (2370+ lines) —
// same reasoning as ConnectorsHubSheet.kt being split out, avoids pushing that file
// closer to the 64KB Kotlin bytecode method-size limit.
//
// Deliberately does NOT route through a local model (Nemotron/Ollama) first — Gemini
// alone handles a single labeled prompt ("an avatar", "a thumbnail") reliably; a small
// local LLM relaying/labeling requests would just be an extra unreliable hop.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun AiImageGenDialog(
    targetDir: File,
    apiKey: String?,
    onDismiss: () -> Unit,
    onSaved: (File) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var subFolder by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<GeneratedImage?>(null) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    // Rotation fix (#8): key on orientation so this AlertDialog gets a fresh, correctly-
    // sized window on rotate.
    val orientation = LocalConfiguration.current.orientation

    fun runGenerate() {
        if (prompt.isBlank() || apiKey.isNullOrBlank()) return
        error = null
        loading = true
        // First generation for this prompt — pre-fill the folder/name suggestions,
        // but only if the user hasn't already typed their own.
        if (subFolder.isBlank()) subFolder = suggestImageSubfolder(prompt)
        if (fileName.isBlank()) fileName = prompt.take(24)
        scope.launch {
            try {
                result = generateGeminiImage(apiKey, prompt)
            } catch (e: Exception) {
                error = e.message ?: "Image generation failed."
            } finally {
                loading = false
            }
        }
    }

    key(orientation) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (savedFile != null) "Saved" else "Generate Image with AI") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (apiKey.isNullOrBlank()) {
                    Text(
                        "Add a Gemini API key in Settings first — that's what generates the image.",
                        fontSize = 13.sp,
                    )
                    return@Column
                }
                if (savedFile != null) {
                    Text("Saved to:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(savedFile!!.absolutePath, fontSize = 12.sp)
                    return@Column
                }

                Text("Saving into: ${targetDir.name}/", fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Describe the image (e.g. \"a friendly cartoon avatar\")") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = subFolder,
                    onValueChange = { subFolder = it },
                    label = { Text("Subfolder (auto-suggested — edit if you want)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File name (no extension)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                )

                if (loading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                result?.let { img ->
                    Spacer(Modifier.height(8.dp))
                    val bmp = remember(img) {
                        android.graphics.BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                savedFile != null -> TextButton(onClick = onDismiss) { Text("Done") }
                result != null -> Button(onClick = {
                    val dest = File(targetDir, subFolder.trim().trim('/'))
                    savedFile = saveGeneratedImage(dest, result!!, fileName)
                    savedFile?.let { onSaved(it) }
                }) { Text("Save Here") }
                else -> Button(
                    onClick = { runGenerate() },
                    enabled = prompt.isNotBlank() && !loading && !apiKey.isNullOrBlank(),
                ) { Text("Generate") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (savedFile != null) "Close" else "Cancel") }
        },
    )
    }
}
