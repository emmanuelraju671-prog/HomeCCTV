package com.homecctv.camera

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun FirebaseLoginScreen(
    firebaseManager: FirebaseManager,
    onLoginSuccess: (role: String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("camera") }
    var isRegistering by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "HomeCCTV",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email address") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Text("This device role:")

        Row {
            RadioButton(
                selected = role == "camera",
                onClick = { role = "camera" }
            )
            Text(
                "Camera (Redmi Note 7)",
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Row {
            RadioButton(
                selected = role == "controller",
                onClick = { role = "controller" }
            )
            Text(
                "Controller (Samsung A36)",
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (email.isBlank() || password.length < 6) {
                    message = "Enter an email and a password of at least 6 characters."
                    return@Button
                }

                loading = true
                message = ""

                val callback: (Boolean, String?) -> Unit = { success, result ->
                    loading = false
                    message = result ?: ""

                    if (success) {
                        onLoginSuccess(role)
                    }
                }

                if (isRegistering) {
                    firebaseManager.register(
                        email,
                        password,
                        role,
                        callback
                    )
                } else {
                    firebaseManager.signIn(
                        email,
                        password,
                        role,
                        callback
                    )
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (loading) "Please wait..."
                else if (isRegistering) "Create account"
                else "Sign in"
            )
        }

        TextButton(
            onClick = {
                isRegistering = !isRegistering
                message = ""
            }
        ) {
            Text(
                if (isRegistering)
                    "Already registered? Sign in"
                else
                    "New user? Create account"
            )
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(message)
        }
    }
}