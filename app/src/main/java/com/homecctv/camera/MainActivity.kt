package com.homecctv.camera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.homecctv.camera.ui.theme.HomeCCTVTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var firebaseManager: FirebaseManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen alive so camera hardware doesn't sleep
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        firebaseManager = FirebaseManager(this)

        setContent {
            HomeCCTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigationHost(firebaseManager = firebaseManager)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (firebaseManager.isSignedIn()) {
            firebaseManager.setDeviceOnlineStatus(true)
        }
    }

    override fun onPause() {
        super.onPause()
        if (firebaseManager.isSignedIn()) {
            firebaseManager.setDeviceOnlineStatus(false)
        }
    }
}

private enum class ScreenState {
    LOGIN,
    CAMERA,
    CONTROLLER
}

@Composable
private fun AppNavigationHost(firebaseManager: FirebaseManager) {
    val localContext = LocalContext.current
    val preferences = remember {
        localContext.getSharedPreferences("homecctv_preferences", Context.MODE_PRIVATE)
    }

    val cameraRegistry = remember { CameraRegistry(localContext) }
    val googleDriveManager = remember { GoogleDriveManager(localContext) }

    var currentScreen by remember {
        val initialScreen = if (!firebaseManager.isSignedIn()) {
            ScreenState.LOGIN
        } else {
            val savedRole = preferences.getString("device_role", "camera") ?: "camera"
            if (savedRole == "controller") ScreenState.CONTROLLER else ScreenState.CAMERA
        }
        mutableStateOf(initialScreen)
    }

    when (currentScreen) {
        ScreenState.LOGIN -> {
            MandatoryGoogleLoginScreen(
                googleDriveManager = googleDriveManager,
                firebaseManager = firebaseManager,
                onLoginSuccess = { selectedRole ->
                    preferences.edit().putString("device_role", selectedRole).apply()
                    currentScreen = if (selectedRole == "controller") {
                        ScreenState.CONTROLLER
                    } else {
                        ScreenState.CAMERA
                    }
                }
            )
        }

        ScreenState.CAMERA -> {
            HomeCCTVScreen(
                firebaseManager = firebaseManager,
                onSignOut = {
                    firebaseManager.signOut()
                    currentScreen = ScreenState.LOGIN
                },
                onSwitchToController = {
                    preferences.edit().putString("device_role", "controller").apply()
                    currentScreen = ScreenState.CONTROLLER
                }
            )
        }

        ScreenState.CONTROLLER -> {
            ControllerDashboardScreen(
                registry = cameraRegistry,
                onSignOut = {
                    firebaseManager.signOut()
                    currentScreen = ScreenState.LOGIN
                },
                onSwitchToCamera = {
                    preferences.edit().putString("device_role", "camera").apply()
                    currentScreen = ScreenState.CAMERA
                }
            )
        }
    }
}

@Composable
private fun MandatoryGoogleLoginScreen(
    googleDriveManager: GoogleDriveManager,
    firebaseManager: FirebaseManager,
    onLoginSuccess: (String) -> Unit
) {
    val localContext = LocalContext.current
    var role by remember { mutableStateOf("camera") }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        loading = true
        errorMessage = ""
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)

            if (account != null && account.idToken != null) {
                googleDriveManager.initFromAccount(account)

                val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                FirebaseAuth.getInstance().signInWithCredential(credential)
                    .addOnCompleteListener { authTask ->
                        if (authTask.isSuccessful) {
                            firebaseManager.saveDevice(role) { ok, msg ->
                                loading = false
                                if (ok) {
                                    onLoginSuccess(role)
                                } else {
                                    errorMessage = msg ?: "Device registration failed"
                                }
                            }
                        } else {
                            loading = false
                            errorMessage = authTask.exception?.localizedMessage ?: "Firebase auth failed"
                        }
                    }
            } else {
                loading = false
                errorMessage = "Google Sign-In failed (No ID token)"
            }
        } catch (e: ApiException) {
            loading = false
            errorMessage = "Google Sign-In Error (${e.statusCode}): ${e.message}"
        } catch (e: Exception) {
            loading = false
            errorMessage = "Sign-In Error: ${e.localizedMessage}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("HomeCCTV Security", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Sign in with Google to enable Cloud Sync & Remote Control", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Device Role:", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = role == "camera", onClick = { role = "camera" })
                    Text("Camera Phone (Redmi Note 7)")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = role == "controller", onClick = { role = "controller" })
                    Text("Controller Phone (Samsung A36)")
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val signInIntent = googleDriveManager.getSignInClient().signInIntent
                googleSignInLauncher.launch(signInIntent)
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "Connecting to Google..." else "Sign in with Google")
        }

        if (errorMessage.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
    }
}

private data class VideoItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateAdded: Long
)

@Composable
private fun ControllerDashboardScreen(
    registry: CameraRegistry,
    onSignOut: () -> Unit,
    onSwitchToCamera: () -> Unit
) {
    val localContext = LocalContext.current

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(localContext, "Microphone permission is required for intercom talk", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(localContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var cameras by remember { mutableStateOf<List<CameraDevice>>(emptyList()) }
    var selectedCamera by remember { mutableStateOf<CameraDevice?>(null) }
    var liveBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val intercom = remember { IntercomManager("controller") }
    var isTalkingToGuest by remember { mutableStateOf(false) }

    val commandManager = remember(selectedCamera?.deviceId) {
        selectedCamera?.let { RemoteCommandManager(it.deviceId) }
    }

    val streamReceiver = remember(selectedCamera?.deviceId) {
        selectedCamera?.let { LiveStreamManager(it.deviceId) }
    }

    DisposableEffect(registry) {
        val listener = registry.observeCameras(
            onCamerasChanged = { list ->
                cameras = list
                if (selectedCamera == null && list.isNotEmpty()) {
                    selectedCamera = list.first()
                }
            },
            onError = {}
        )
        onDispose { registry.stopObserving(listener) }
    }

    DisposableEffect(selectedCamera?.deviceId) {
        intercom.startListening()
        streamReceiver?.startReceiving { bmp ->
            liveBitmap = bmp
        }
        onDispose {
            streamReceiver?.stopReceiving()
            intercom.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("HomeCCTV Controller", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSwitchToCamera) { Text("Camera") }
                OutlinedButton(onClick = onSignOut) { Text("Sign Out") }
            }
        }

        if (cameras.isNotEmpty()) {
            Text("Active Camera: ${selectedCamera?.name ?: "None"} (${selectedCamera?.model})", style = MaterialTheme.typography.titleMedium)
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("Waiting for camera phone to connect...", modifier = Modifier.padding(16.dp))
            }
        }

        // Live Video Stream Box
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "📡 LIVE STREAM (Mobile Data / Wi-Fi)",
                    color = Color.Green,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(6.dp))

                val bmp = liveBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Live Remote View",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Connecting to live video stream...", color = Color.White)
                    }
                }
            }
        }

        // Two-Way Intercom
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isTalkingToGuest) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Two-Way Live Voice Intercom", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isTalkingToGuest) "🎙️ Speaking... Guest is hearing your voice on camera speaker!"
                    else "Tap button below to speak live to guests at camera",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        isTalkingToGuest = !isTalkingToGuest
                        if (isTalkingToGuest) {
                            intercom.startTalking()
                            Toast.makeText(localContext, "Microphone active: Speak now", Toast.LENGTH_SHORT).show()
                        } else {
                            intercom.stopTalking()
                            Toast.makeText(localContext, "Microphone stopped", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTalkingToGuest) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (isTalkingToGuest) "STOP SPEAKING" else "🎤 TALK TO GUEST")
                }
            }
        }

        // Remote Controls
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Remote Controls", style = MaterialTheme.typography.titleMedium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            commandManager?.sendCommand("TOGGLE_TORCH")
                            Toast.makeText(localContext, "Command sent: Toggle Torch", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔦 Torch")
                    }

                    Button(
                        onClick = {
                            commandManager?.sendCommand("TOGGLE_SIREN")
                            Toast.makeText(localContext, "Command sent: Toggle Siren", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🚨 Siren")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            commandManager?.sendCommand("RECORD_CLIP")
                            Toast.makeText(localContext, "Command sent: Record 10s Clip", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔴 Record 10s")
                    }

                    Button(
                        onClick = {
                            commandManager?.sendCommand("TAKE_SNAPSHOT")
                            Toast.makeText(localContext, "Command sent: Take Snapshot", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📸 Snapshot")
                    }
                }

                Button(
                    onClick = {
                        commandManager?.sendCommand("SWITCH_CAMERA")
                        Toast.makeText(localContext, "Command sent: Switch Camera", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔄 Switch Front/Back Camera")
                }
            }
        }

        CameraManagementScreen(registry = registry)
    }
}

@Composable
private fun HomeCCTVScreen(
    firebaseManager: FirebaseManager,
    onSignOut: () -> Unit,
    onSwitchToController: () -> Unit
) {
    val localContext = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val sirenManager = remember { SirenManager(localContext) }
    val emailAlertManager = remember { EmailAlertManager(localContext) }
    val retentionManager = remember { RetentionManager(localContext) }
    val googleDriveManager = remember { GoogleDriveManager(localContext) }
    val evidenceManager = remember { EvidenceManager(localContext) }
    val intercom = remember { IntercomManager("camera") }
    val liveStream = remember { LiveStreamManager(firebaseManager.deviceId) }
    val remoteCommandManager = remember { RemoteCommandManager(firebaseManager.deviceId) }

    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(localContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var audioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(localContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<androidx.camera.video.Recording?>(null) }

    var recordingActive by remember { mutableStateOf(false) }
    var recordingPaused by remember { mutableStateOf(false) }
    var audioEnabled by remember { mutableStateOf(true) }
    var torchEnabled by remember { mutableStateOf(false) }
    var frontCamera by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Waiting for camera permission") }

    var backgroundServiceEnabled by remember { mutableStateOf(false) }
    var isStealthMode by remember { mutableStateOf(false) }
    var lastTapTime by remember { mutableStateOf(0L) }

    var motionDetectionEnabled by remember { mutableStateOf(false) }
    var motionDetectedBanner by remember { mutableStateOf(false) }
    var motionCountdown by remember { mutableStateOf(0) }
    var alarmMode by remember { mutableStateOf("Voice") }
    var motionTimer by remember { mutableStateOf<CountDownTimer?>(null) }
    var currentClipName by remember { mutableStateOf("") }

    var configuredAlertEmail by remember { mutableStateOf(emailAlertManager.getRecipientEmail()) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf(configuredAlertEmail) }

    var driveConnected by remember { mutableStateOf(googleDriveManager.isDriveConnected()) }
    var driveAccountEmail by remember { mutableStateOf(googleDriveManager.getConnectedAccountName() ?: "") }
    var lastUploadStatus by remember { mutableStateOf("") }

    var selectedEvidenceCertificate by remember { mutableStateOf<String?>(null) }

    var supportedQualities by remember { mutableStateOf<List<Quality>>(emptyList()) }
    var selectedQuality by remember { mutableStateOf(Quality.HD) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var maximumZoom by remember { mutableStateOf(4f) }

    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var protectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var videoToDelete by remember { mutableStateOf<VideoItem?>(null) }
    var retentionReportMsg by remember { mutableStateOf("") }

    // Control screen brightness for stealth mode
    fun setScreenBrightness(brightness: Float) {
        val activity = localContext as? Activity ?: return
        val layout = activity.window.attributes
        layout.screenBrightness = brightness
        activity.window.attributes = layout
    }

    fun loadVideos() {
        val result = mutableListOf<VideoItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("Movies/HomeCCTV/")

        try {
            localContext.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    result.add(
                        VideoItem(
                            uri = uri,
                            name = cursor.getString(nameColumn) ?: "Recording",
                            size = cursor.getLong(sizeColumn),
                            dateAdded = cursor.getLong(dateColumn)
                        )
                    )
                }
            }
        } catch (exception: Exception) {
            status = "Could not load recordings: ${exception.message}"
        }
        videos = result
    }

    fun run14DayRetentionCheck() {
        val report = retentionManager.purgeExpiredRecordings()
        retentionReportMsg = "14-Day Retention: Purged ${report.deletedCount} expired, Kept ${report.preservedCount}, Protected ${report.protectedCount}"
        loadVideos()
    }

    fun startRecording(prefix: String = "HomeCCTV_") {
        val capture = videoCapture ?: run {
            status = "Camera is not ready"
            return
        }
        if (recordingActive) return

        val fileName = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        currentClipName = fileName

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HomeCCTV/")
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            localContext.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        var pendingRecording = capture.output.prepareRecording(localContext, outputOptions)
        if (audioEnabled && audioPermissionGranted) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        recording = pendingRecording.start(ContextCompat.getMainExecutor(localContext)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingActive = true
                    recordingPaused = false
                    status = if (prefix.contains("MOTION")) "🔴 Auto-recording motion clip (10s)..." else "Recording"
                }
                is VideoRecordEvent.Pause -> {
                    recordingPaused = true
                    status = "Recording paused"
                }
                is VideoRecordEvent.Resume -> {
                    recordingPaused = false
                    status = "Recording resumed"
                }
                is VideoRecordEvent.Finalize -> {
                    recordingActive = false
                    recordingPaused = false
                    recording = null

                    if (event.hasError()) {
                        status = "Recording error: ${event.error}"
                    } else {
                        status = "Recording saved locally"
                        val savedUri = event.outputResults.outputUri

                        evidenceManager.recordEvidence(
                            fileName = fileName,
                            uri = savedUri,
                            deviceId = firebaseManager.deviceId,
                            driveFileId = ""
                        )

                        if (fileName.contains("MOTION")) {
                            emailAlertManager.sendMotionAlert(
                                clipFileName = fileName
                            ) { success, _ ->
                                ContextCompat.getMainExecutor(localContext).execute {
                                    if (success) {
                                        Toast.makeText(localContext, "Alert email dispatched", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }

                        if (googleDriveManager.isDriveConnected()) {
                            lastUploadStatus = "Uploading $fileName to Drive..."
                            googleDriveManager.uploadVideoClip(savedUri, fileName) { ok, resultMsg ->
                                ContextCompat.getMainExecutor(localContext).execute {
                                    lastUploadStatus = if (ok) "Drive: $fileName uploaded" else "Drive upload error: $resultMsg"
                                }
                            }
                        }

                        run14DayRetentionCheck()
                    }
                    loadVideos()
                }
            }
        }
    }

    fun stopRecordingManual() {
        motionTimer?.cancel()
        motionTimer = null
        motionCountdown = 0
        sirenManager.stopSiren()
        recording?.stop()
    }

    fun onMotionTriggered() {
        if (!motionDetectionEnabled || recordingActive) return

        ContextCompat.getMainExecutor(localContext).execute {
            motionDetectedBanner = true

            when (alarmMode) {
                "Siren" -> sirenManager.startSiren()
                "Voice" -> sirenManager.speakWarning("Warning! Motion detected. Recording started.")
            }

            startRecording(prefix = "MOTION_10s_")

            motionTimer?.cancel()
            motionTimer = object : CountDownTimer(10000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    motionCountdown = (millisUntilFinished / 1000).toInt() + 1
                }

                override fun onFinish() {
                    motionCountdown = 0
                    motionDetectedBanner = false
                    sirenManager.stopSiren()
                    recording?.stop()
                }
            }.start()
        }
    }

    fun toggleTorch() {
        val activeCamera = camera ?: return
        if (!activeCamera.cameraInfo.hasFlashUnit()) {
            status = "Flash is not available on this camera"
            return
        }
        torchEnabled = !torchEnabled
        activeCamera.cameraControl.enableTorch(torchEnabled)
    }

    fun takeSnapshot() {
        val capture = imageCapture ?: run {
            status = "Camera is not ready"
            return
        }

        val fileName = "HomeCCTV_Photo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HomeCCTV/")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            localContext.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(localContext),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    status = "Snapshot saved"
                    Toast.makeText(localContext, "Snapshot saved", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    status = "Snapshot failed: ${exception.message}"
                }
            }
        )
    }

    // Remote Commands Listener from Samsung
    DisposableEffect(Unit) {
        remoteCommandManager.listenForCommands { action, _ ->
            ContextCompat.getMainExecutor(localContext).execute {
                when (action) {
                    "TOGGLE_TORCH" -> toggleTorch()
                    "TOGGLE_SIREN" -> {
                        if (sirenManager.isSirenActive()) sirenManager.stopSiren()
                        else sirenManager.startSiren()
                    }
                    "RECORD_CLIP" -> {
                        if (!recordingActive) startRecording("REMOTE_10s_")
                        else stopRecordingManual()
                    }
                    "TAKE_SNAPSHOT" -> takeSnapshot()
                    "SWITCH_CAMERA" -> {
                        if (!recordingActive) frontCamera = !frontCamera
                    }
                }
            }
        }
        onDispose { remoteCommandManager.stopListening() }
    }

    fun bindCamera() {
        val provider = cameraProvider ?: return
        val view = previewView ?: return
        if (!cameraPermissionGranted) return

        try {
            val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            if (!provider.hasCamera(selector)) {
                status = "Selected camera is not available"
                return
            }

            provider.unbindAll()

            val cameraInfo = provider.getCameraInfo(selector)
            val qualities = QualitySelector.getSupportedQualities(cameraInfo).filter {
                it == Quality.SD || it == Quality.HD || it == Quality.FHD
            }
            supportedQualities = qualities
            if (qualities.isEmpty()) {
                status = "No supported recording quality found"
                return
            }
            if (!qualities.contains(selectedQuality)) {
                selectedQuality = qualities.first()
            }

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        selectedQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                ).build()

            val video = VideoCapture.withOutput(recorder)

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imgProxy ->
                        liveStream.pushLiveFrame(imgProxy)
                    }
                }

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                capture,
                video,
                imageAnalyzer
            )

            imageCapture = capture
            videoCapture = video

            val zoomState = camera?.cameraInfo?.zoomState?.value
            maximumZoom = zoomState?.maxZoomRatio ?: 4f
            zoomRatio = zoomState?.zoomRatio ?: 1f
            torchEnabled = false
            status = "Camera connected & streaming"

        } catch (exception: Exception) {
            status = "Camera error: ${exception.message}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        cameraPermissionGranted = permissions[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(localContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        audioPermissionGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(localContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (cameraPermissionGranted) status = "Camera permission granted"
        else status = "Camera permission is required"
    }

    LaunchedEffect(Unit) {
        configuredAlertEmail = emailAlertManager.getRecipientEmail()
        emailInput = configuredAlertEmail
        protectedUris = retentionManager.getProtectedUris()
        loadVideos()
        run14DayRetentionCheck()
        googleDriveManager.initializeIfSignedIn()
        driveConnected = googleDriveManager.isDriveConnected()
        driveAccountEmail = googleDriveManager.getConnectedAccountName() ?: ""

        intercom.startListening()

        if (!cameraPermissionGranted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    LaunchedEffect(cameraPermissionGranted, frontCamera, selectedQuality) {
        if (!cameraPermissionGranted) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(localContext)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (exception: Exception) {
                status = "Could not start camera: ${exception.message}"
            }
        }, ContextCompat.getMainExecutor(localContext))
    }

    DisposableEffect(Unit) {
        onDispose {
            setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            motionTimer?.cancel()
            sirenManager.release()
            intercom.release()
            cameraExecutor.shutdown()
        }
    }

    fun updateZoom(value: Float) {
        zoomRatio = value
        camera?.cameraControl?.setZoomRatio(value)
    }

    fun toggleProtection(item: VideoItem) {
        retentionManager.toggleProtection(item.uri)
        protectedUris = retentionManager.getProtectedUris()
    }

    fun deleteVideo(item: VideoItem) {
        if (retentionManager.isProtected(item.uri)) {
            status = "Unprotect this recording before deleting it"
            return
        }
        try {
            val deleted = localContext.contentResolver.delete(item.uri, null, null)
            if (deleted > 0) {
                status = "Recording deleted"
                loadVideos()
            } else {
                status = "Could not delete recording"
            }
        } catch (exception: Exception) {
            status = "Delete failed: ${exception.message}"
        }
    }

    // MAIN BOX HOLDING NORMAL SCREEN OR STEALTH BLACK SCREEN
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "HomeCCTV Camera",
                    style = MaterialTheme.typography.headlineMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSwitchToController) {
                        Text("Controller")
                    }
                    OutlinedButton(onClick = onSignOut) {
                        Text("Sign Out")
                    }
                }
            }

            if (motionDetectedBanner || motionCountdown > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🚨 MOTION DETECTED! Recording: ${motionCountdown}s remaining",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium
            )

            if (!cameraPermissionGranted) {
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Camera Permission")
                }
            } else {

                // CAMERA PREVIEW
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Live Camera Preview")

                        AndroidView(
                            factory = { viewContext ->
                                PreviewView(viewContext).also {
                                    it.scaleType = PreviewView.ScaleType.FILL_CENTER
                                    previewView = it
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (!recordingActive) frontCamera = !frontCamera
                                },
                                enabled = !recordingActive,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (frontCamera) "Front Camera" else "Back Camera")
                            }

                            OutlinedButton(
                                onClick = { toggleTorch() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (torchEnabled) "Torch ON" else "Torch OFF")
                            }
                        }
                    }
                }

                // STEALTH / SCREEN-OFF SURVEILLANCE CARD
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "🌙 Stealth / Screen-Off Surveillance",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Turns the screen completely black with lowest brightness while keeping the camera, motion detection, and live streaming running 24/7 without Android killing the sensor.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = {
                                isStealthMode = true
                                setScreenBrightness(0.01f) // Dims to minimum
                                CameraForegroundService.startService(localContext)
                                Toast.makeText(localContext, "Double-tap screen anytime to wake up", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Turn Screen Off (Stealth Mode)")
                        }
                    }
                }

                // MOTION SENSING & AUTO 10S CARD
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Motion Detection & Auto-10s Clips", style = MaterialTheme.typography.titleMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (motionDetectionEnabled) "Motion Sensing: ACTIVE" else "Motion Sensing: OFF")
                            Switch(
                                checked = motionDetectionEnabled,
                                onCheckedChange = {
                                    motionDetectionEnabled = it
                                    if (!it) {
                                        sirenManager.stopSiren()
                                        motionTimer?.cancel()
                                    }
                                }
                            )
                        }

                        Text("Alarm Action on Motion:")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Off", "Voice", "Siren").forEach { mode ->
                                if (alarmMode == mode) {
                                    Button(onClick = {}, modifier = Modifier.weight(1f)) {
                                        Text(mode)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { alarmMode = mode },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(mode)
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (sirenManager.isSirenActive()) {
                                        sirenManager.stopSiren()
                                    } else {
                                        sirenManager.startSiren()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (sirenManager.isSirenActive()) "Stop Siren" else "Test Siren")
                            }

                            OutlinedButton(
                                onClick = {
                                    sirenManager.speakWarning("Warning! Motion detected. You are on CCTV.")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Test Voice Alert")
                            }
                        }
                    }
                }

                // CLOUD SYNC & EMAIL ALERTS CARD
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Google Drive & Alerts", style = MaterialTheme.typography.titleMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Google Drive:", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (driveConnected) "Connected ($driveAccountEmail)" else "Connected via Google Sign-In",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        if (lastUploadStatus.isNotBlank()) {
                            Text(lastUploadStatus, style = MaterialTheme.typography.bodySmall)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Alert Email:", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (configuredAlertEmail.isNotBlank()) configuredAlertEmail else "Not set",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            OutlinedButton(onClick = {
                                emailInput = configuredAlertEmail
                                showEmailDialog = true
                            }) {
                                Text(if (configuredAlertEmail.isNotBlank()) "Change" else "Set Email")
                            }
                        }
                    }
                }

                // RETENTION STATUS CARD
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("14-Day Expiry Retention", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { run14DayRetentionCheck() }) {
                                Text("Run Purge")
                            }
                        }

                        Text("Clips expire after 14 days (14 × 24h). Protected clips are never deleted.")
                        if (retentionReportMsg.isNotBlank()) {
                            Text(retentionReportMsg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // QUALITY & ZOOM CARD
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Recording Quality", style = MaterialTheme.typography.titleMedium)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            supportedQualities.forEach { quality ->
                                val label = when (quality) {
                                    Quality.SD -> "480p"
                                    Quality.HD -> "720p"
                                    Quality.FHD -> "1080p"
                                    else -> quality.toString()
                                }

                                if (selectedQuality == quality) {
                                    Button(onClick = {}, enabled = false) { Text(label) }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            if (!recordingActive) selectedQuality = quality
                                        },
                                        enabled = !recordingActive
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                        }

                        Text("Zoom: ${String.format(Locale.US, "%.1f", zoomRatio)}x")

                        Slider(
                            value = zoomRatio.coerceIn(1f, maximumZoom),
                            onValueChange = { updateZoom(it) },
                            valueRange = 1f..maximumZoom.coerceAtLeast(1.1f),
                            enabled = camera != null
                        )

                        OutlinedButton(
                            onClick = { updateZoom(1f) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reset Zoom")
                        }
                    }
                }

                // MANUAL RECORDING CONTROLS CARD
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Manual Controls", style = MaterialTheme.typography.titleMedium)

                        OutlinedButton(
                            onClick = {
                                audioEnabled = !audioEnabled
                                recording?.mute(!audioEnabled)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (audioEnabled) "Audio: ON" else "Audio: OFF")
                        }

                        Button(
                            onClick = { startRecording() },
                            enabled = !recordingActive && camera != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start Recording")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { recording?.pause() },
                                enabled = recordingActive && !recordingPaused,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pause")
                            }

                            OutlinedButton(
                                onClick = { recording?.resume() },
                                enabled = recordingActive && recordingPaused,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Resume")
                            }
                        }

                        Button(
                            onClick = { stopRecordingManual() },
                            enabled = recordingActive,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Stop Recording")
                        }

                        OutlinedButton(
                            onClick = { takeSnapshot() },
                            enabled = camera != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Take Snapshot")
                        }
                    }
                }

                // LOCAL RECORDINGS & EVIDENCE CERTIFICATE
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Local Recordings & Legal Evidence", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { loadVideos() }) { Text("Refresh") }
                        }

                        if (videos.isEmpty()) {
                            Text("No recordings found yet.")
                        }

                        videos.forEach { item ->
                            val isProtected = protectedUris.contains(item.uri.toString())
                            val evidence = evidenceManager.getEvidenceForFile(item.name)

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                Text("Size: ${String.format(Locale.US, "%.1f", item.size / 1024.0 / 1024.0)} MB")

                                if (evidence != null) {
                                    Text(
                                        "SHA-256: ${evidence.sha256Checksum.take(16)}...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Text(
                                    if (isProtected) "🔒 Protected (Immune to 14-day auto-purge)"
                                    else "⏳ Auto-expires after 14 days"
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(item.uri, "video/mp4")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            try {
                                                localContext.startActivity(intent)
                                            } catch (_: Exception) {
                                                Toast.makeText(localContext, "No video player available", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Text("Play")
                                    }

                                    OutlinedButton(onClick = { toggleProtection(item) }) {
                                        Text(if (isProtected) "Unprotect" else "Protect")
                                    }

                                    OutlinedButton(onClick = {
                                        val ev = evidence ?: evidenceManager.recordEvidence(
                                            fileName = item.name,
                                            uri = item.uri,
                                            deviceId = firebaseManager.deviceId
                                        )
                                        val certText = evidenceManager.generateSection65BCertificate(
                                            record = ev,
                                            certifierName = "HomeCCTV Security Owner"
                                        )
                                        selectedEvidenceCertificate = certText
                                    }) {
                                        Text("Sec 65B Cert")
                                    }

                                    OutlinedButton(
                                        onClick = { videoToDelete = item },
                                        enabled = !isProtected
                                    ) {
                                        Text("Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // FULLSCREEN STEALTH / BLACK SCREEN OVERLAY
        if (isStealthMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 500) {
                            // Double tap detected: Exit stealth mode
                            isStealthMode = false
                            setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                            Toast.makeText(localContext, "Stealth Mode Deactivated", Toast.LENGTH_SHORT).show()
                        } else {
                            lastTapTime = now
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "●",
                        color = Color(0xFF151515), // Ultra faint indicator
                        fontSize = 24.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Double-tap anywhere to wake up",
                        color = Color(0xFF1E1E1E), // Extremely faint text
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    // Evidence Certificate Dialog
    selectedEvidenceCertificate?.let { certText ->
        AlertDialog(
            onDismissRequest = { selectedEvidenceCertificate = null },
            title = { Text("Section 65B Evidence Certificate") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(certText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "HomeCCTV Section 65B Certificate")
                            putExtra(Intent.EXTRA_TEXT, certText)
                        }
                        localContext.startActivity(Intent.createChooser(shareIntent, "Share Legal Certificate"))
                    }
                ) {
                    Text("Share / Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedEvidenceCertificate = null }) { Text("Close") }
            }
        )
    }

    // Set Alert Email Dialog
    if (showEmailDialog) {
        AlertDialog(
            onDismissRequest = { showEmailDialog = false },
            title = { Text("Motion Notification Email") },
            text = {
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = { emailInput = it },
                    label = { Text("Recipient Email") },
                    placeholder = { Text("you@example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = emailInput.trim()
                        emailAlertManager.setAlertEmail(trimmed)
                        configuredAlertEmail = trimmed
                        showEmailDialog = false
                        Toast.makeText(localContext, "Alert email saved: $trimmed", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmailDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation dialog
    if (videoToDelete != null) {
        AlertDialog(
            onDismissRequest = { videoToDelete = null },
            title = { Text("Delete recording?") },
            text = { Text("This will delete this local recording immediately. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        videoToDelete?.let { deleteVideo(it) }
                        videoToDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { videoToDelete = null }) { Text("Cancel") }
            }
        )
    }
}