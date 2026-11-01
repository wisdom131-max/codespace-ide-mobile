package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.data.LocalConnectorStore
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
 * Firebase, Supabase and n8n in the Connectors Hub.
 *
 * These three replaced a separate "Project services" page. They do not belong to the backend
 * connector list - their credentials stay on the device and their address is the user's own
 * instance - so they get their own group here rather than being faked into the OAuth rows, and the
 * agent reads the same store through `use_connector`.
 *
 * Each row is collapsed until tapped: the Hub already lists nineteen services, and three forms with
 * two fields each would bury them.
 */
private data class DeviceService(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val color: Color,
    val fieldOne: FieldSpec,
    val fieldTwo: FieldSpec,
)

private data class FieldSpec(val label: String, val hint: String, val secret: Boolean)

private val DEVICE_SERVICES = listOf(
    DeviceService(
        id = "firebase",
        name = "Firebase",
        icon = Icons.Default.LocalFireDepartment,
        color = Color(0xFFFFA000),
        fieldOne = FieldSpec("Web API Key", "AIza… (Project settings > General)", secret = true),
        fieldTwo = FieldSpec("Project ID", "my-project (Project settings > General)", secret = false),
    ),
    DeviceService(
        id = "supabase",
        name = "Supabase",
        icon = Icons.Default.Cloud,
        color = Color(0xFF3ECF8E),
        fieldOne = FieldSpec("Project URL", "https://xxxx.supabase.co", secret = false),
        fieldTwo = FieldSpec("Anon (publishable) key", "eyJ… (Project settings > API)", secret = true),
    ),
    DeviceService(
        id = "n8n",
        name = "n8n",
        icon = Icons.Default.AccountTree,
        color = Color(0xFFEA4B71),
        fieldOne = FieldSpec("Instance URL", "https://your-n8n.example.com", secret = false),
        fieldTwo = FieldSpec("API Key", "X-N8N-API-KEY (n8n > Settings > API)", secret = true),
    ),
)

@Composable
internal fun LocalConnectorsSection(menuText: Color, onToast: (String) -> Unit) {
    var expanded by remember { mutableStateOf<String?>(null) }
    Text(
        "On this device",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF888888),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
    )
    for (service in DEVICE_SERVICES) {
        DeviceServiceRow(
            service = service,
            menuText = menuText,
            expanded = expanded == service.id,
            onToggle = { expanded = if (expanded == service.id) null else service.id },
            onToast = onToast,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DeviceServiceRow(
    service: DeviceService,
    menuText: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    onToast: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val store = LocalConnectorStore
    // Recomputed on expand so a save or a clear is reflected without leaving the sheet.
    val projectId = store.activeProjectId(context)
    val configured = projectId != null && store.isConfigured(context, service.id)

    var first by remember(expanded, projectId) {
        mutableStateOf(projectId?.let { store.value(context, service.id, store.fieldsFor(service.id)[0]) } ?: "")
    }
    var second by remember(expanded, projectId) {
        mutableStateOf(projectId?.let { store.value(context, service.id, store.fieldsFor(service.id)[1]) } ?: "")
    }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(service.icon, null, tint = service.color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(service.name, fontSize = 13.sp, color = menuText)
                Text(
                    when {
                        projectId == null -> "Open a project to configure this"
                        configured -> "Configured on this device"
                        else -> "Not configured"
                    },
                    fontSize = 11.sp,
                    color = if (configured) Color(0xFF4CAF50) else Color(0xFF888888),
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (expanded) "Collapse" else "Expand",
                tint = Color(0xFF888888),
                modifier = Modifier.size(18.dp),
            )
        }

        if (expanded && projectId != null) {
            Spacer(Modifier.height(10.dp))
            DeviceField(service.fieldOne, first, { first = it }, menuText)
            Spacer(Modifier.height(8.dp))
            DeviceField(service.fieldTwo, second, { second = it }, menuText)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceAction("Save", Color(0xFF007ACC), testing = false) {
                    val fields = store.fieldsFor(service.id)
                    store.setValue(context, service.id, fields[0], first.trim())
                    store.setValue(context, service.id, fields[1], second.trim())
                    ok = store.isConfigured(context, service.id)
                    status = if (ok == true) "Saved" else "Both fields are needed"
                    onToast("${service.name} saved")
                }
                DeviceAction("Test", Color(0xFF3C3C3C), testing = testing) {
                    testing = true
                    status = "Testing…"
                    ok = null
                    val one = first.trim()
                    val two = second.trim()
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { probe(service.id, one, two) }
                        status = result.second
                        ok = result.first
                        testing = false
                    }
                }
                DeviceAction("Forget", Color(0xFF7A3B3B), testing = false) {
                    store.clear(context, service.id)
                    first = ""
                    second = ""
                    ok = null
                    status = "Removed from this device"
                    onToast("${service.name} forgotten")
                }
            }
            status?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = when (ok) {
                        true -> Color(0xFF4CAF50)
                        false -> Color(0xFFE06C75)
                        null -> Color(0xFFE5C07B)
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceField(spec: FieldSpec, value: String, onChange: (String) -> Unit, menuText: Color) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(spec.label, fontSize = 11.sp) },
        placeholder = { Text(spec.hint, fontSize = 11.sp, color = Color(0xFF666666)) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = menuText),
        visualTransformation = if (spec.secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (spec.secret) KeyboardType.Password else KeyboardType.Uri),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF007ACC),
            unfocusedBorderColor = Color(0xFF3C3C3C),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DeviceAction(label: String, tint: Color, testing: Boolean, onClick: () -> Unit) {
    Text(
        if (testing) "$label…" else label,
        fontSize = 12.sp,
        color = Color(0xFFE0E0E0),
        modifier = Modifier
            .background(tint.copy(alpha = if (testing) 0.5f else 1f), RoundedCornerShape(8.dp))
            .clickable(enabled = !testing) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/**
 * A live check per service, because "Saved" only means the text was stored. Each probe proves the
 * credential is accepted by the real endpoint rather than merely well-formed.
 */
private fun probe(service: String, one: String, two: String): Pair<Boolean, String> {
    if (one.isBlank() || two.isBlank()) return false to "Fill in both fields first."
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
    return try {
        when (service) {
            "firebase" -> {
                // signUp with a web API key: any structured error other than an invalid-key one
                // still proves the key and project are live.
                val req = Request.Builder()
                    .url("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$one")
                    .post("{ }".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) true to "OK — key and project verified live."
                    else {
                        val msg = runCatching {
                            JSONObject(text).getJSONObject("error").optString("message")
                        }.getOrNull() ?: "HTTP ${resp.code}"
                        val invalid = msg.contains("API_KEY_INVALID") ||
                            msg.contains("INVALID_APP_CREDENTIAL") || msg.contains("INVALID_API_KEY")
                        if (invalid) false to "Invalid API key."
                        else true to "Key valid (feature '$msg' disabled — connection OK)."
                    }
                }
            }
            "supabase" -> {
                val base = one.trimEnd('/')
                if (!base.startsWith("http")) return false to "URL must start with https://"
                val req = Request.Builder()
                    .url("$base/rest/v1/")
                    .header("apikey", two)
                    .header("Authorization", "Bearer $two")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) true to "OK — project reachable, key accepted."
                    else false to "HTTP ${resp.code} — check URL and anon key."
                }
            }
            else -> {
                val base = one.trimEnd('/')
                if (!base.startsWith("http")) return false to "URL must start with https://"
                val req = Request.Builder()
                    .url("$base/api/v1/workflows?limit=1")
                    .header("X-N8N-API-KEY", two)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) true to "OK — instance reachable, API key accepted."
                    else false to "HTTP ${resp.code} — check URL and API key."
                }
            }
        }
    } catch (e: Exception) {
        false to (e.message ?: "Connection failed")
    }
}
