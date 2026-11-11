
package com.homecctv.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.DisposableEffect

@Composable
fun CameraManagementScreen(
    registry: CameraRegistry
) {
    var cameras by remember {
        mutableStateOf<List<CameraDevice>>(emptyList())
    }

    var cameraName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    var editingCamera by remember {
        mutableStateOf<CameraDevice?>(null)
    }

    var removingCamera by remember {
        mutableStateOf<CameraDevice?>(null)
    }

    var newName by remember { mutableStateOf("") }

    DisposableEffect(registry) {
        val listener = registry.observeCameras(
            onCamerasChanged = { cameras = it },
            onError = { message = it }
        )

        onDispose {
            registry.stopObserving(listener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "HomeCCTV Cameras",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Registered cameras: ${cameras.size}",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Add a camera phone",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = cameraName,
            onValueChange = { cameraName = it },
            label = { Text("Camera name") },
            placeholder = { Text("Example: Front Door") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                if (cameraName.isBlank()) {
                    message = "Enter a camera name first"
                } else {
                    loading = true
                    message = ""

                    registry.registerDevice(
                        role = "camera",
                        deviceName = cameraName
                    ) { success, result ->
                        loading = false
                        message = result ?: ""

                        if (success) {
                            cameraName = ""
                        }
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (loading) "Registering..."
                else "Register This Phone as Camera"
            )
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 18.dp)
        )

        Text(
            text = "Your cameras",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(8.dp))

        if (cameras.isEmpty()) {
            Text("No cameras registered yet.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = cameras,
                    key = { it.deviceId }
                ) { camera ->

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Text(
                                camera.name,
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text("Device: ${camera.model}")
                            Text("ID: ${camera.deviceId}")
                            Text("Status: Registered")

                            Spacer(Modifier.height(8.dp))

                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        editingCamera = camera
                                        newName = camera.name
                                    }
                                ) {
                                    Text("Rename")
                                }

                                OutlinedButton(
                                    onClick = {
                                        removingCamera = camera
                                    }
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingCamera?.let { camera ->
        AlertDialog(
            onDismissRequest = {
                editingCamera = null
            },
            title = { Text("Rename camera") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New camera name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        registry.renameCamera(
                            camera.deviceId,
                            newName
                        ) { success, result ->
                            message = result ?: ""
                            if (success) editingCamera = null
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { editingCamera = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    removingCamera?.let { camera ->
        AlertDialog(
            onDismissRequest = {
                removingCamera = null
            },
            title = { Text("Remove camera?") },
            text = {
                Text(
                    "Remove ${camera.name} from this HomeCCTV account? " +
                            "This does not delete its recordings or uninstall its app."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        registry.removeCamera(
                            camera.deviceId
                        ) { success, result ->
                            message = result ?: ""
                            if (success) removingCamera = null
                        }
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { removingCamera = null }
                ) {
                    Text("Cancel")
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        )
    }
}
