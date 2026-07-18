package com.example.ui

import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignInClient

@Composable
fun AuthGuardScreen(
    authState: AuthState,
    googleSignInClient: GoogleSignInClient,
    googleSignInLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>,
    onSignOut: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (authState) {
                AuthState.LOADING -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Authenticating...")
                }
                AuthState.NOT_LOGGED_IN -> {
                    Text(
                        text = "Authentication Required",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Please log in with your Google account to access this application. Your account will be securely bound to this device.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) }) {
                        Text("Log In with Google")
                    }
                }
                AuthState.DEVICE_MISMATCH -> {
                    Text(
                        text = "Device Not Authorized",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your account is already bound to another device. You cannot use the same account on multiple devices.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = { onSignOut() }) {
                        Text("Sign Out")
                    }
                }
                AuthState.PENDING_APPROVAL -> {
                    Text(
                        text = "Pending Approval",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your device and account have been registered. Please wait for the admin to approve your access.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = { onSignOut() }) {
                        Text("Sign Out")
                    }
                }
                AuthState.APPROVED -> {
                    // Handled externally
                }
            }
        }
    }
}
