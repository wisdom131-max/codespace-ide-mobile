package com.codespace.ide.ui.screens

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.launch

// Web client ID from google-services.json (client_type: 3)
private const val WEB_CLIENT_ID =
    "872673459882-v8qfuree46s2c3rs4lsrq6psf8alads1.apps.googleusercontent.com"

@Composable
fun AuthScreen(onAuthenticated: (token: String) -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val credentialManager = remember { CredentialManager.create(context) }
    val firebaseAuth = remember { FirebaseAuth.getInstance() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Visual Node Code",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "The Mobile IDE for Android",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(64.dp))

        // Google Sign-In button
        OutlinedButton(
            onClick = {
                loading = true
                error = ""
                scope.launch {
                    try {
                        // Build the Google ID request
                        val googleIdOption = GetGoogleIdOption.Builder()
                            .setFilterByAuthorizedAccounts(false) // show all accounts
                            .setServerClientId(WEB_CLIENT_ID)
                            .setAutoSelectEnabled(false)
                            .build()

                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(googleIdOption)
                            .build()

                        // Launch the credential picker
                        val result = credentialManager.getCredential(
                            request = request,
                            context = activity,
                        )

                        // Extract Google ID token
                        val googleIdToken = GoogleIdTokenCredential
                            .createFrom(result.credential.data)
                            .idToken

                        // Exchange with Firebase
                        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                        firebaseAuth.signInWithCredential(firebaseCredential)
                            .addOnSuccessListener { authResult ->
                                val uid = authResult.user?.uid ?: ""
                                // Pass Firebase UID as the auth token downstream
                                // (swap for real JWT from your backend if needed)
                                onAuthenticated(uid)
                            }
                            .addOnFailureListener { e ->
                                error = "Firebase sign-in failed: ${e.message}"
                                loading = false
                            }

                    } catch (e: GetCredentialException) {
                        error = "Sign-in cancelled or unavailable: ${e.message}"
                        loading = false
                    } catch (e: Exception) {
                        error = "Unexpected error: ${e.message}"
                        loading = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            enabled = !loading,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    // Google "G" icon — place ic_google.xml in res/drawable
                    // or swap for Text("G") if you skip the asset
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Continue with Google",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
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
