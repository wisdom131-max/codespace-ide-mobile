package com.codespace.ide.ui.screens

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val WEB_CLIENT_ID =
    "872673459882-v8qfuree46s2c3rs4lsrq6psf8alads1.apps.googleusercontent.com"

// Passed in by the caller — different per flavor (dev/staging/prod)
// Defaults to prod if not overridden
private const val AUTH_ENDPOINT = "https://api.codespace-ide.app/api/v1/auth/google"

data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val role: String,           // "owner" | "user"
    val isOwner: Boolean = role == "owner",
)

@Composable
fun AuthScreen(onAuthenticated: (AuthResult) -> Unit) {
    val context  = LocalContext.current
    val activity = context as Activity
    val scope    = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var error   by remember { mutableStateOf("") }

    val credentialManager = remember { CredentialManager.create(context) }
    val firebaseAuth      = remember { FirebaseAuth.getInstance() }
    val httpClient        = remember { OkHttpClient() }

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
            color  = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(64.dp))

        OutlinedButton(
            onClick = {
                loading = true
                error   = ""
                scope.launch {
                    try {
                        // 1. Credential Manager — pick Google account
                        val googleIdOption = GetGoogleIdOption.Builder()
                            .setFilterByAuthorizedAccounts(false)
                            .setServerClientId(WEB_CLIENT_ID)
                            .setAutoSelectEnabled(false)
                            .build()

                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(googleIdOption)
                            .build()

                        val credResult = credentialManager.getCredential(
                            request = request,
                            context = activity,
                        )

                        val googleIdToken = GoogleIdTokenCredential
                            .createFrom(credResult.credential.data)
                            .idToken

                        // 2. Sign into Firebase and get its ID token
                        val firebaseCred = GoogleAuthProvider.getCredential(googleIdToken, null)
                        val authResult   = firebaseAuth.signInWithCredential(firebaseCred).await()
                        val firebaseIdToken = authResult.user
                            ?.getIdToken(false)
                            ?.await()
                            ?.token
                            ?: throw Exception("Could not get Firebase ID token")

                        // 3. Exchange with our backend — get JWT + role
                        val body = JSONObject().apply {
                            put("firebaseIdToken", firebaseIdToken)
                        }.toString().toRequestBody("application/json".toMediaType())

                        val backendResp = withContext(Dispatchers.IO) {
                            httpClient.newCall(
                                Request.Builder()
                                    .url(AUTH_ENDPOINT)
                                    .post(body)
                                    .build()
                            ).execute()
                        }

                        if (!backendResp.isSuccessful) {
                            throw Exception("Backend auth failed (${backendResp.code})")
                        }

                        val json = JSONObject(backendResp.body!!.string())
                        val result = AuthResult(
                            accessToken  = json.getString("accessToken"),
                            refreshToken = json.getString("refreshToken"),
                            role         = json.optString("role", "user"),
                        )

                        onAuthenticated(result)

                    } catch (e: GetCredentialException) {
                        error = "Sign-in cancelled: ${e.message}"
                    } catch (e: Exception) {
                        error = "Error: ${e.message}"
                    } finally {
                        loading = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape   = RoundedCornerShape(8.dp),
            border  = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            enabled = !loading,
            colors  = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text("Continue with Google", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (error.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Sign in with your Google account to get started.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
