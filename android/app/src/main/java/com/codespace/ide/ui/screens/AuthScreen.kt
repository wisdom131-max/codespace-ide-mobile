package com.codespace.ide.ui.screens

import android.app.Activity
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

private enum class AuthTab { SIGN_IN, SIGN_UP }

@Composable
fun AuthScreen(onAuthenticated: (AuthResult) -> Unit) {
    val context  = LocalContext.current
    val activity = context as Activity
    val scope    = rememberCoroutineScope()

    var loading       by remember { mutableStateOf(false) }
    var error         by remember { mutableStateOf("") }
    var activeTab     by remember { mutableStateOf(AuthTab.SIGN_IN) }
    var manualEmail   by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }

    val credentialManager = remember { CredentialManager.create(context) }
    val firebaseAuth      = remember { FirebaseAuth.getInstance() }
    val httpClient        = remember { OkHttpClient() }

    // Core sign-in logic — loginHint is optional; if blank, normal account picker shows
    suspend fun doGoogleSignIn(loginHint: String? = null) {
        loading = true
        error   = ""
        statusMessage = ""
        try {
            val baseBuilder = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
            val googleIdOption = if (!loginHint.isNullOrBlank()) {
                try {
                    // setLoginHint available in googleid >= 1.1.0
                    val m = baseBuilder.javaClass.getMethod("setLoginHint", String::class.java)
                    m.invoke(baseBuilder, loginHint)
                    baseBuilder.build()
                } catch (_: Exception) { baseBuilder.build() }
            } else {
                baseBuilder.build()
            }

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
                selected = activeTab == AuthTab.SIGN_IN,
                onClick  = { activeTab = AuthTab.SIGN_IN; error = "" },
                text     = { Text("Sign In") },
            )
            Tab(
                selected = activeTab == AuthTab.SIGN_UP,
                onClick  = { activeTab = AuthTab.SIGN_UP; error = "" },
                text     = { Text("Sign Up") },
            )
        }

        Spacer(Modifier.height(28.dp))

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

        // ── Main Google button — opens account picker (no hint) ────
        OutlinedButton(
            onClick = { scope.launch { doGoogleSignIn() } },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(8.dp),
            border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            enabled  = !loading,
            colors   = ButtonDefaults.outlinedButtonColors(
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

        Spacer(Modifier.height(20.dp))

        // ── Divider with label ────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Divider(modifier = Modifier.weight(1f))
            Text(
                "  or type your email  ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Divider(modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        // ── Manual email field — always visible ───────────────────
        // Typing here pre-fills the Google sign-in sheet with that email.
        // The phone account picker still appears — the email just highlights
        // the matching account (or lets the user add a new one).
        OutlinedTextField(
            value         = manualEmail,
            onValueChange = { manualEmail = it; error = "" },
            label         = { Text("Google email (optional)") },
            placeholder   = { Text("you@gmail.com") },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled       = !loading,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "Leave blank to pick from your phone accounts, or type to use a specific / new Google account.",
            style     = MaterialTheme.typography.labelSmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        // ── Single continue button — uses hint if email typed ─────
        Button(
            onClick = {
                scope.launch {
                    doGoogleSignIn(loginHint = manualEmail.trim().takeIf { it.isNotBlank() })
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled  = !loading,
        ) {
            Text(
                if (manualEmail.isBlank()) "Pick Google account"
                else "Continue with ${manualEmail.trim()}",
                fontWeight = FontWeight.Medium,
            )
        }

        // ── Error / status messages ───────────────────────────────
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
