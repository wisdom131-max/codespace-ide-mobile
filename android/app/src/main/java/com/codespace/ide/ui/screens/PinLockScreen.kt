package com.codespace.ide.ui.screens

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.codespace.ide.data.SecureTokenStore

/**
 * Full-screen PIN lock shown on app launch when biometricLockEnabled is true.
 * Shows PIN entry with optional biometric prompt.
 */
@Composable
fun PinLockScreen(
    tokenStore: SecureTokenStore,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var attemptsLeft by remember { mutableStateOf(3) }
    var showPin by remember { mutableStateOf(false) }
    var biometricTried by remember { mutableStateOf(false) }

    // Check if biometric is available on this device
    val biometricManager = remember { BiometricManager.from(context) }
    val biometricAvailable = remember {
        biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // Auto-trigger biometric prompt on first show if available
    LaunchedEffect(Unit) {
        if (biometricAvailable && !biometricTried) {
            biometricTried = true
            showBiometricPrompt(context as? FragmentActivity, tokenStore, onUnlocked) { msg ->
                errorMessage = msg
                showError = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Visual Node Code",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter your PIN to continue",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = pinInput,
                onValueChange = {
                    pinInput = it.filter { c -> c.isDigit() }.take(8)
                    showError = false
                },
                label = { Text("PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = if (showPin) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPin = !showPin }) {
                        Icon(
                            if (showPin) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = if (showPin) "Hide PIN" else "Show PIN",
                        )
                    }
                },
                isError = showError,
                supportingText = if (showError) {
                    { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
                } else null,
                modifier = Modifier.width(200.dp),
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    if (pinInput.isBlank()) {
                        errorMessage = "Please enter a PIN"
                        showError = true
                        return@Button
                    }
                    if (tokenStore.verifyPin(pinInput)) {
                        onUnlocked()
                    } else {
                        attemptsLeft--
                        if (attemptsLeft <= 0) {
                            errorMessage = "Too many wrong attempts. PIN lock disabled."
                            showError = true
                            // Don't permanently lock the user out — disable the lock
                            tokenStore.biometricLockEnabled = false
                            // Auto-unlock after a brief delay
                            onUnlocked()
                        } else {
                            errorMessage = "Wrong PIN. $attemptsLeft attempt(s) left."
                            showError = true
                            pinInput = ""
                        }
                    }
                },
                modifier = Modifier.width(200.dp),
            ) {
                Text("Unlock")
            }

            if (biometricAvailable) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    showBiometricPrompt(context as? FragmentActivity, tokenStore, onUnlocked) { msg ->
                        errorMessage = msg
                        showError = true
                    }
                }) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Use fingerprint")
                }
            }
        }
    }
}

/**
 * PIN registration dialog — shown in Settings when enabling the lock.
 * Asks user to set a 4-8 digit PIN, then confirm it.
 */
@Composable
fun PinRegistrationDialog(
    onPinSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(1) } // 1 = enter, 2 = confirm
    var firstPin by remember { mutableStateOf("") }
    var secondPin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (step == 1) "Set a PIN" else "Confirm PIN",
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column {
                if (step == 1) {
                    Text(
                        "Enter a 4-8 digit PIN. You'll need this every time you open the app.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Re-enter the same PIN to confirm.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = if (step == 1) firstPin else secondPin,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }.take(8)
                        if (step == 1) firstPin = filtered else secondPin = filtered
                        error = ""
                    },
                    label = { Text(if (step == 1) "New PIN" else "Confirm PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (showPin) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPin = !showPin }) {
                            Icon(
                                if (showPin) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    isError = error.isNotEmpty(),
                    supportingText = if (error.isNotEmpty()) {
                        { Text(error, color = MaterialTheme.colorScheme.error) }
                    } else null,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val currentPin = if (step == 1) firstPin else secondPin
                if (currentPin.length < 4) {
                    error = "PIN must be at least 4 digits"
                    return@Button
                }
                if (step == 1) {
                    step = 2
                    secondPin = ""
                } else {
                    if (firstPin == secondPin) {
                        onPinSet(firstPin)
                    } else {
                        error = "PINs don't match. Try again."
                        secondPin = ""
                    }
                }
            }) {
                Text(if (step == 1) "Next" else "Set PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                step = 1
                firstPin = ""
                secondPin = ""
                error = ""
                onDismiss()
            }) { Text("Cancel") }
        },
    )
}

private fun showBiometricPrompt(
    activity: FragmentActivity?,
    tokenStore: SecureTokenStore,
    onUnlocked: () -> Unit,
    onError: (String) -> Unit,
) {
    if (activity == null) {
        onError("Biometric not available on this device")
        return
    }

    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Non-fatal errors — just show message, let user fall back to PIN
                onError(errString.toString())
            }
            override fun onAuthenticationFailed() {
                // Wrong biometric — BiometricPrompt handles retry automatically
            }
        }
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Visual Node Code")
        .setSubtitle("Verify it's you to continue")
        .setNegativeButtonText("Use PIN instead")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()

    prompt.authenticate(promptInfo)
}
