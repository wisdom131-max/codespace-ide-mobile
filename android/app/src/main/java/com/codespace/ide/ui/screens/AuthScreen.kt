package com.codespace.ide.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

private const val AUTH_ENDPOINT = "https://api.codespace-ide.app/api/v1/auth/google"

data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val role: String,
    val isOwner: Boolean = role == "owner",
)

// Which tab the user is on
private enum class AuthTab { SIGN_IN, SIGN_UP }

@Composable
fun AuthScreen(onAuthenticated: (AuthResult) -> Unit) {
    val context  = LocalContext.current
    val activity = context as Activity
    val scope    = rememberCoroutineScope()

    var loading        by remember { mutableStateOf(false) }
    var error          by remember { mutableStateOf("") }
    var activeTab      by remember { mutableStateOf(AuthTab.SIGN_IN) }
    var showManualEntry by remember { mutableStateOf(false) }
    var manualEmail    by remember { mutableStateOf("") }
    var statusMessage  by remember { mutableStateOf("") }

    val credentialManager = remember { CredentialManager.create(context) }
    val firebaseAuth      = remember { FirebaseAuth.getInstance() }
    val httpClient        = remember { OkHttpClient() }

    // Core sign-in logic — shared between both tabs and manual entry
    suspend fun doGoogleSignIn(filterAuthorized: Boolean, loginHint: String? = null) {
        loading = true
        error   = ""
        statusMessage = ""
        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(filterAuthorized)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .apply { loginHint?.let { setLoginHint(it) } }
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

            val firebaseCred    = GoogleAuthProvider.getCredential(googleIdToken, null)
            val firebaseResult  = firebaseAuth.signInWithCredential(firebaseCred).await()
            val firebaseIdToken = firebaseResult.user
                ?.getIdToken(false)
                ?.await()
                ?.token
                ?: throw Exception("Could not get Firebase ID token")

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
                throw Exception("Server auth failed (${backendResp.code})")
            }

            val json = JSONObject(backendResp.body!!.string())
            val result = AuthResult(
                accessToken  = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                role         = json.optString("role", "user"),
            )
            onAuthenticated(result)

        } catch (e: GetCredentialException) {
            error = "Sign-in cancelled or failed. Try again."
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── App title ──────────────────────────────────────────────
        Text("Codespace IDE", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Your projects. Any device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(40.dp))

        // ── Sign In / Sign Up tabs ─────────────────────────────────
        TabRow(selectedTabIndex = activeTab.ordinal) {
            Tab(
                selected  = activeTab == AuthTab.SIGN_IN,
                onClick   = { activeTab = AuthTab.SIGN_IN; error = ""; showManualEntry = false },
                text      = { Text("Sign In") },
            )
            Tab(
                selected  = activeTab == AuthTab.SIGN_UP,
                onClick   = { activeTab = AuthTab.SIGN_UP; error = ""; showManualEntry = false },
                text      = { Text("Sign Up") },
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── Description text per tab ───────────────────────────────
        Text(
            text = if (activeTab == AuthTab.SIGN_IN)
                "Welcome back. Sign in to access your projects from any device."
            else
                "Create your account. Your projects will be saved to the cloud.",
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        // ── Main Google button ─────────────────────────────────────
        OutlinedButton(
            onClick = {
                scope.launch { doGoogleSignIn(filterAuthorized = false) }
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
                Text(
                    if (activeTab == AuthTab.SIGN_IN) "Continue with Google" else "Sign up with Google",
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Use a different / specific Google account ──────────────
        TextButton(
            onClick = { showManualEntry = !showManualEntry; error = "" },
            enabled = !loading,
        ) {
            Text(
                if (showManualEntry) "Hide" else "Use a different Google account",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ── Manual email hint entry ────────────────────────────────
        AnimatedVisibility(visible = showManualEntry) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value         = manualEmail,
                    onValueChange = { manualEmail = it },
                    label         = { Text("Enter your Google email") },
                    placeholder   = { Text("you@gmail.com") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled       = !loading,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (manualEmail.isNotBlank()) {
                            scope.launch {
                                doGoogleSignIn(filterAuthorized = false, loginHint = manualEmail.trim())
                            }
                        } else {
                            error = "Please enter a Google email address."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled  = !loading,
                ) {
                    Text("Continue with this account")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Google will open so you can sign in to this email. " +
                    "If it's not on this device, you can add it in the Google sign-in screen.",
                    style     = MaterialTheme.typography.labelSmall,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // ── Error message ──────────────────────────────────────────
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                error,
                color     = MaterialTheme.colorScheme.error,
                style     = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                statusMessage,
                color     = MaterialTheme.colorScheme.primary,
                style     = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Your projects are saved to the cloud and available on any device when you sign in with the same Google account.",
            style     = MaterialTheme.typography.labelSmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
