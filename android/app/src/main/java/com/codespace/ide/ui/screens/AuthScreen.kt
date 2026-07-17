package com.codespace.ide.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

private const val AUTH_ENDPOINT =
    "https://codespace-ide-mobile-production.up.railway.app/api/v1/auth/google"

data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val role: String,
    val isOwner: Boolean = role == "owner",
)

@Composable
fun AuthScreen(onAuthenticated: (AuthResult) -> Unit) {
    val context   = LocalContext.current
    val activity  = context as Activity
    val scope     = rememberCoroutineScope()
    val keyboard  = LocalSoftwareKeyboardController.current
    val focusReq  = remember { FocusRequester() }

    var email   by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error   by remember { mutableStateOf("") }

    val credentialManager = remember { CredentialManager.create(context) }
    val firebaseAuth      = remember { FirebaseAuth.getInstance() }
    val httpClient        = remember { OkHttpClient() }

    suspend fun doGoogleSignIn(hint: String? = null) {
        loading = true
        error   = ""
        keyboard?.hide()
        try {
            val optBuilder = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)

            if (!hint.isNullOrBlank()) {
                try {
                    val m = optBuilder.javaClass.getMethod("setLoginHint", String::class.java)
                    m.invoke(optBuilder, hint)
                } catch (_: Exception) {}
            }

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(optBuilder.build())
                .build()

            val credResult = credentialManager.getCredential(request = request, context = activity)
            val googleIdToken = GoogleIdTokenCredential
                .createFrom(credResult.credential.data).idToken

            val firebaseCred   = GoogleAuthProvider.getCredential(googleIdToken, null)
            val firebaseResult = firebaseAuth.signInWithCredential(firebaseCred).await()
            val firebaseIdToken = firebaseResult.user
                ?.getIdToken(false)?.await()?.token
                ?: throw Exception("Could not get Firebase ID token")

            val body = JSONObject().apply {
                put("firebaseIdToken", firebaseIdToken)
            }.toString().toRequestBody("application/json".toMediaType())

            val resp = withContext(Dispatchers.IO) {
                httpClient.newCall(
                    Request.Builder().url(AUTH_ENDPOINT).post(body).build()
                ).execute()
            }

            if (!resp.isSuccessful) throw Exception("Server error (${resp.code})")

            val json = JSONObject(resp.body!!.string())
            onAuthenticated(
                AuthResult(
                    accessToken  = json.getString("accessToken"),
                    refreshToken = json.getString("refreshToken"),
                    role         = json.optString("role", "user"),
                )
            )
        } catch (e: GetCredentialException) {
            error = "Sign-in cancelled. Try again."
        } catch (e: Exception) {
            error = e.message ?: "Something went wrong"
        } finally {
            loading = false
        }
    }

    // ── Full-screen centered layout ──────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {

            // ── Logo / Title ─────────────────────────────────────────────────
            Text(
                text       = "Codespace IDE",
                fontSize   = 30.sp,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text  = "Your projects. Any device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(48.dp))

            // ── "Sign in / Sign up" single pill button ───────────────────────
            Button(
                onClick  = { scope.launch { doGoogleSignIn(hint = email.trim().ifBlank { null }) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape   = RoundedCornerShape(14.dp),
                enabled = !loading,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier   = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color      = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text       = "Sign in or Sign up",
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Email field with inline send arrow ───────────────────────────
            OutlinedTextField(
                value         = email,
                onValueChange = { email = it; error = "" },
                modifier      = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusReq),
                placeholder   = { Text("Enter your Google email") },
                singleLine    = true,
                enabled       = !loading,
                shape         = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction    = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { scope.launch { doGoogleSignIn(hint = email.trim().ifBlank { null }) } }
                ),
                // Arrow button inside the field on the right
                trailingIcon  = {
                    IconButton(
                        onClick  = { scope.launch { doGoogleSignIn(hint = email.trim().ifBlank { null }) } },
                        enabled  = !loading,
                    ) {
                        Icon(
                            imageVector        = Icons.Default.ArrowForward,
                            contentDescription = "Continue",
                            tint               = if (email.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text      = "Tap the field to pick an account or type your email",
                style     = MaterialTheme.typography.labelSmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // ── Error message ────────────────────────────────────────────────
            if (error.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text      = error,
                    color     = MaterialTheme.colorScheme.error,
                    style     = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
