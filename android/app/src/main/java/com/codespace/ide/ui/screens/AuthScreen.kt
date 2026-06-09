package com.codespace.ide.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Composable
fun AuthScreen(onAuthenticated: (token: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Visual Node Code", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "The Mobile IDE for Android",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))

        OutlinedTextField(
            value = token,
            onValueChange = { token = it; error = "" },
            label = { Text("GitHub Personal Access Token") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error.isNotEmpty(),
            supportingText = if (error.isNotEmpty()) {
                { Text(error, color = MaterialTheme.colorScheme.error) }
            } else null,
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                if (token.isBlank()) {
                    error = "Please enter your GitHub token"
                    return@Button
                }
                loading = true
                error = ""
                scope.launch {
                    try {
                        val client = OkHttpClient()
                        val request = Request.Builder()
                            .url("https://api.github.com/user")
                            .header("Authorization", "Bearer ${token.trim()}")
                            .header("Accept", "application/vnd.github+json")
                            .build()
                        val response = withContext(Dispatchers.IO) {
                            client.newCall(request).execute()
                        }
                        if (response.isSuccessful) {
                            onAuthenticated(token.trim())
                        } else {
                            error = "Invalid token (${response.code}). Please check and try again."
                        }
                    } catch (e: Exception) {
                        error = "Network error: ${e.message}"
                    } finally {
                        loading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
        ) {
            if (loading) CircularProgressIndicator()
            else Text("Sign In")
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/settings/tokens/new?scopes=repo,read:user&description=CodeSpaceIDE")
                )
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Get GitHub Token")
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "Tap 'Get GitHub Token' → create token with 'repo' and 'read:user' scopes → copy and paste it above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
