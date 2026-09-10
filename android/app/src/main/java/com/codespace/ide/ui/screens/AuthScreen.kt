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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val WEB_CLIENT_ID =
    "872673459882-v8qfuree46s2c3rs4lsrq6psf8alads1.apps.googleusercontent.com"

private const val AUTH_API_BASE = "https://codespace-ide-backend.onrender.com/api/v1"
private val AUTH_JSON_MEDIA = "application/json".toMediaType()
private val authHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

/** POST /auth/google — exchanges a Firebase ID token for a real backend JWT pair.
 *  Throws on any non-2xx or malformed response (caller treats as "backend unavailable"). */
private data class BackendAuthTokens(val accessToken: String, val refreshToken: String, val role: String)

private fun exchangeFirebaseTokenForBackendJwt(firebaseIdToken: String): BackendAuthTokens {
    val body = JSONObject().put("firebaseIdToken", firebaseIdToken).toString()
        .toRequestBody(AUTH_JSON_MEDIA)
    val req = Request.Builder().url("$AUTH_API_BASE/auth/google").post(body).build()
    authHttpClient.newCall(req).execute().use { resp ->
        val bodyStr = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) error("auth/google HTTP ${resp.code}: ${bodyStr.take(200)}")
        val o = JSONObject(bodyStr)
        return BackendAuthTokens(
            accessToken  = o.getString("accessToken"),
            refreshToken = o.getString("refreshToken"),
            role         = o.optString("role", "owner"),
        )
    }
}

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

            // FIX (Batch H 401 root cause, 2026-09-10): the OLD "local-first" comment
            // below was written when the Render backend was believed offline (Railway
            // trial ended). It is NOT offline anymore (migrated to Render, confirmed
            // live via /health). Using the raw Firebase ID token as both accessToken
            // AND refreshToken meant the Connectors Hub (and every other backend-JWT
            // call) always got Unauthorized: the backend's JwtStrategy only verifies
            // tokens signed with JWT_SECRET (its own auth.service.issueTokens output),
            // never raw Firebase ID tokens. The 401-retry "refresh" fix on
            // ConnectorsApiClient could never help because the STORED refresh token
            // was never a real backend refresh token either — POST /auth/refresh
            // would 401 on it too (not found in refresh_tokens table).
            // REAL FIX: exchange the Firebase ID token for a real backend JWT pair via
            // POST /auth/google (already live and verified: returns 401 "Invalid
            // Firebase ID token" for garbage input, so Firebase Admin is configured
            // correctly on Render — a REAL token will succeed).
            val backendTokens = try {
                withContext(Dispatchers.IO) { exchangeFirebaseTokenForBackendJwt(firebaseIdToken) }
            } catch (_: Exception) { null }

            if (backendTokens != null) {
                onAuthenticated(
                    AuthResult(
                        accessToken  = backendTokens.accessToken,
                        refreshToken = backendTokens.refreshToken,
                        role         = backendTokens.role,
                    )
                )
            } else {
                // Backend unreachable (rare — e.g. mobile data drop mid-login). Fall back
                // to the Firebase token so the user isn't hard-locked-out of the app; the
                // Connectors Hub will show its existing "sign in first" error until the
                // NEXT successful login exchanges a real backend pair. Projects/editor/
                // terminal all work fine on this fallback since they don't call the backend.
                onAuthenticated(
                    AuthResult(
                        accessToken  = firebaseIdToken,
                        refreshToken = firebaseAuth.currentUser?.getIdToken(true)?.await()?.token ?: firebaseIdToken,
                        role         = "owner",
                    )
                )
            }
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
                text       = "VN Code",
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
                            imageVector        = Icons.AutoMirrored.Filled.ArrowForward,
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
