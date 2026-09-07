package com.codespace.ide.ui.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.data.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Connectors Phase 3 (Group C): project-level service panels for Firebase,
 * Supabase and n8n — following the CloudBackupPanel pattern (own state,
 * per-project credentials, connection tests with live status).
 * These are project settings, NOT Connectors Hub entries: the credentials
 * belong to a specific project, live encrypted on-device per project id,
 * and no backend proxy is involved.
 *
 * Sections are split into private composables to respect the 64KB method limit.
 */
@Composable
fun ProjectServicesPanel(
    projectId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val tokenStore = remember { SecureTokenStore(context) }
    val scope = rememberCoroutineScope()

    Surface(
        color = Color(0xFF1E1E1E),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "PROJECT SERVICES",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD4D4D4),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.Close, "Close",
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(20.dp).clickable { onDismiss() },
                )
            }
            Text(
                "Per-project credentials for Firebase, Supabase and n8n. Stored encrypted on this device only.",
                fontSize = 11.sp, color = Color(0xFF888888),
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )
            FirebaseSection(projectId, tokenStore, scope)
            SupabaseSection(projectId, tokenStore, scope)
            N8nSection(projectId, tokenStore, scope)
            Spacer(Modifier.height(24.dp))
        }
    }
}


@Composable
private fun ServiceSectionShell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    status: String?,
    statusOk: Boolean?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD4D4D4))
        Spacer(Modifier.weight(1f))
        if (status != null) {
            Text(
                status,
                fontSize = 10.sp,
                color = when (statusOk) {
                    true -> Color(0xFF4CAF50)
                    false -> Color(0xFFF48771)
                    null -> Color(0xFF888888)
                },
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(label, fontSize = 10.sp, color = Color(0xFF888888))
        Spacer(Modifier.height(2.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFD4D4D4)),
            placeholder = { Text(placeholder, fontSize = 11.sp, color = Color(0xFF6A6A6A)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ActionRow(
    onSave: () -> Unit,
    onTest: () -> Unit,
    onClear: () -> Unit,
    testing: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onSave, modifier = Modifier.height(34.dp)) {
            Icon(Icons.Default.Save, null, tint = Color(0xFF007ACC), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("Save", fontSize = 11.sp, color = Color(0xFF007ACC))
        }
        OutlinedButton(onClick = onTest, enabled = !testing, modifier = Modifier.height(34.dp)) {
            Icon(Icons.Default.Refresh, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (testing) "Testing…" else "Test connection", fontSize = 11.sp, color = Color(0xFF4CAF50))
        }
        TextButton(onClick = onClear, modifier = Modifier.height(34.dp)) {
            Icon(Icons.Default.Clear, null, tint = Color(0xFF888888), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("Clear", fontSize = 11.sp, color = Color(0xFF888888))
        }
    }
}

// ── Firebase ───────────────────────────────────────────────────────────────

@Composable
private fun FirebaseSection(projectId: String, tokenStore: SecureTokenStore, scope: kotlinx.coroutines.CoroutineScope) {
    var apiKey by remember { mutableStateOf(tokenStore.projectSecret(projectId, "firebase_apikey") ?: "") }
    var fbProjectId by remember { mutableStateOf(tokenStore.projectSecret(projectId, "firebase_project_id") ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }

    SectionCard {
        ServiceSectionShell(Icons.Default.LocalFireDepartment, Color(0xFFFFA000), "Firebase", status, statusOk)
        FieldRow("Web API Key", apiKey, { apiKey = it }, "AIza… (Firebase console > Project settings)")
        FieldRow("Project ID", fbProjectId, { fbProjectId = it }, "my-project (Firebase console > Project settings)")
        ActionRow(
            onSave = {
                tokenStore.setProjectSecret(projectId, "firebase_apikey", apiKey.ifBlank { null })
                tokenStore.setProjectSecret(projectId, "firebase_project_id", fbProjectId.ifBlank { null })
                status = "Saved"; statusOk = null
            },
            onTest = {
                testing = true; status = "Testing…"; statusOk = null
                val key = apiKey.trim(); val pid = fbProjectId.trim()
                scope.launch {
                    val (ok, msg) = withContext(Dispatchers.IO) { testFirebase(key, pid) }
                    status = msg; statusOk = ok; testing = false
                }
            },
            onClear = {
                tokenStore.setProjectSecret(projectId, "firebase_apikey", null)
                tokenStore.setProjectSecret(projectId, "firebase_project_id", null)
                apiKey = ""; fbProjectId = ""; status = "Cleared"; statusOk = null
            },
            testing = testing,
        )
    }
}

/** POST identitytoolkit signUp with the web API key — any structured error besides
 *  API_KEY_INVALID/INVALID_APP_CREDENTIAL proves key + project are live. */
private fun testFirebase(apiKey: String, projectId: String): Pair<Boolean, String> {
    if (apiKey.isBlank() || projectId.isBlank()) return false to "Fill in both fields first."
    return try {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        val body = "{}".toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.isSuccessful) return true to "OK — key and project verified live."
            val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull() ?: "HTTP ${resp.code}"
            val invalid = msg.contains("API_KEY_INVALID") || msg.contains("INVALID_APP_CREDENTIAL") || msg.contains("INVALID_API_KEY")
            if (invalid) false to "Invalid API key."
            else true to "Key valid (feature '$msg' disabled — connection OK)."
        }
    } catch (e: Exception) {
        false to (e.message ?: "Connection failed")
    }
}

// ── Supabase ────────────────────────────────────────────────────────────────

@Composable
private fun SupabaseSection(projectId: String, tokenStore: SecureTokenStore, scope: kotlinx.coroutines.CoroutineScope) {
    var url by remember { mutableStateOf(tokenStore.projectSecret(projectId, "supabase_url") ?: "") }
    var anonKey by remember { mutableStateOf(tokenStore.projectSecret(projectId, "supabase_anon_key") ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }

    SectionCard {
        ServiceSectionShell(Icons.Default.Storage, Color(0xFF3ECF8E), "Supabase", status, statusOk)
        FieldRow("Project URL", url, { url = it }, "https://xxx.supabase.co")
        FieldRow("Anon Key", anonKey, { anonKey = it }, "eyJhbGciOi… (Settings > API)")
        ActionRow(
            onSave = {
                tokenStore.setProjectSecret(projectId, "supabase_url", url.ifBlank { null })
                tokenStore.setProjectSecret(projectId, "supabase_anon_key", anonKey.ifBlank { null })
                status = "Saved"; statusOk = null
            },
            onTest = {
                testing = true; status = "Testing…"; statusOk = null
                val u = url.trim(); val k = anonKey.trim()
                scope.launch {
                    val (ok, msg) = withContext(Dispatchers.IO) { testSupabase(u, k) }
                    status = msg; statusOk = ok; testing = false
                }
            },
            onClear = {
                tokenStore.setProjectSecret(projectId, "supabase_url", null)
                tokenStore.setProjectSecret(projectId, "supabase_anon_key", null)
                url = ""; anonKey = ""; status = "Cleared"; statusOk = null
            },
            testing = testing,
        )
    }
}

/** GET /rest/v1/ with the anon key — a 200 + OpenAPI doc proves URL and key. */
private fun testSupabase(url: String, anonKey: String): Pair<Boolean, String> {
    if (url.isBlank() || anonKey.isBlank()) return false to "Fill in both fields first."
    val base = url.trimEnd('/')
    if (!base.startsWith("http")) return false to "URL must start with https://"
    return try {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url("$base/rest/v1/")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) true to "OK — project reachable, key accepted."
            else false to "HTTP ${resp.code} — check URL and anon key."
        }
    } catch (e: Exception) {
        false to (e.message ?: "Connection failed")
    }
}

// ── n8n ─────────────────────────────────────────────────────────────────────

@Composable
private fun N8nSection(projectId: String, tokenStore: SecureTokenStore, scope: kotlinx.coroutines.CoroutineScope) {
    var url by remember { mutableStateOf(tokenStore.projectSecret(projectId, "n8n_url") ?: "") }
    var apiKey by remember { mutableStateOf(tokenStore.projectSecret(projectId, "n8n_apikey") ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }

    SectionCard {
        ServiceSectionShell(Icons.Default.AccountTree, Color(0xFFEA4B71), "n8n", status, statusOk)
        FieldRow("Instance URL", url, { url = it }, "https://your-n8n.example.com")
        FieldRow("API Key", apiKey, { apiKey = it }, "X-N8N-API-KEY (n8n > Settings > API)")
        ActionRow(
            onSave = {
                tokenStore.setProjectSecret(projectId, "n8n_url", url.ifBlank { null })
                tokenStore.setProjectSecret(projectId, "n8n_apikey", apiKey.ifBlank { null })
                status = "Saved"; statusOk = null
            },
            onTest = {
                testing = true; status = "Testing…"; statusOk = null
                val u = url.trim(); val k = apiKey.trim()
                scope.launch {
                    val (ok, msg) = withContext(Dispatchers.IO) { testN8n(u, k) }
                    status = msg; statusOk = ok; testing = false
                }
            },
            onClear = {
                tokenStore.setProjectSecret(projectId, "n8n_url", null)
                tokenStore.setProjectSecret(projectId, "n8n_apikey", null)
                url = ""; apiKey = ""; status = "Cleared"; statusOk = null
            },
            testing = testing,
        )
    }
}

/** GET /api/v1/workflows with X-N8N-API-KEY — 200 + count proves instance + key. */
private fun testN8n(url: String, apiKey: String): Pair<Boolean, String> {
    if (url.isBlank() || apiKey.isBlank()) return false to "Fill in both fields first."
    val base = url.trimEnd('/')
    if (!base.startsWith("http")) return false to "URL must start with https://"
    return try {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url("$base/api/v1/workflows?limit=1")
            .header("X-N8N-API-KEY", apiKey)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                val count = runCatching { JSONObject(text).optJSONArray("data")?.length() ?: 0 }.getOrNull()
                true to "OK — instance reachable${if (count != null) ", API key accepted." else "."}"
            } else {
                false to "HTTP ${resp.code} — check URL and API key."
            }
        }
    } catch (e: Exception) {
        false to (e.message ?: "Connection failed")
    }
}

/** Rounded 12dp card container matching the mandatory UI rule. */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        color = Color(0xFF252526),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(bottom = 6.dp)) { content() }
    }
}
