
package com.homecctv.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.homecctv.camera.ui.theme.HomeCCTVTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HomeCCTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeCCTVScreen()
                }
            }
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
private fun HomeCCTVScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var audioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var cameraProvider by remember {
        mutableStateOf<ProcessCameraProvider?>(null)
    }

    var previewView by remember {
        mutableStateOf<PreviewView?>(null)
    }

    var camera by remember {
        mutableStateOf<Camera?>(null)
    }

    var imageCapture by remember {
        mutableStateOf<ImageCapture?>(null)
    }

    var videoCapture by remember {
        mutableStateOf<VideoCapture<Recorder>?>(null)
    }

    var recording by remember {
        mutableStateOf<androidx.camera.video.Recording?>(null)
    }

    var recordingActive by remember { mutableStateOf(false) }
    var recordingPaused by remember { mutableStateOf(false) }
    var audioEnabled by remember { mutableStateOf(true) }
    var torchEnabled by remember { mutableStateOf(false) }
    var frontCamera by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Waiting for camera permission") }

    var supportedQualities by remember {
        mutableStateOf<List<Quality>>(emptyList())
    }

    var selectedQuality by remember {
        mutableStateOf(Quality.HD)
    }

    var zoomRatio by remember { mutableStateOf(1f) }
    var maximumZoom by remember { mutableStateOf(4f) }

    var videos by remember {
        mutableStateOf<List<VideoItem>>(emptyList())
    }

    var protectedUris by remember {
        mutableStateOf<Set<String>>(emptySet())
    }

    var videoToDelete by remember {
        mutableStateOf<VideoItem?>(null)
    }

    val preferences = remember {
        context.getSharedPreferences("homecctv_preferences", Context.MODE_PRIVATE)
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
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->

                val idColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Video.Media._ID
                )
                val nameColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Video.Media.DISPLAY_NAME
                )
                val sizeColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Video.Media.SIZE
                )
                val dateColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Video.Media.DATE_ADDED
                )

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

    fun bindCamera() {
        val provider = cameraProvider ?: return
        val view = previewView ?: return

        if (!cameraPermissionGranted) return

        try {
            val selector = if (frontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            if (!provider.hasCamera(selector)) {
                status = "Selected camera is not available"
                return
            }

            provider.unbindAll()

            val cameraInfo = provider.getCameraInfo(selector)

            val qualities = QualitySelector.getSupportedQualities(cameraInfo)
                .filter {
                    it == Quality.SD ||
                            it == Quality.HD ||
                            it == Quality.FHD
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
                )
                .build()

            val video = VideoCapture.withOutput(recorder)

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                capture,
                video
            )

            imageCapture = capture
            videoCapture = video

            val zoomState = camera?.cameraInfo?.zoomState?.value
            maximumZoom = zoomState?.maxZoomRatio ?: 4f
            zoomRatio = zoomState?.zoomRatio ?: 1f

            torchEnabled = false
            status = "Camera connected"

        } catch (exception: Exception) {
            status = "Camera error: ${exception.message}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->

        cameraPermissionGranted =
            permissions[Manifest.permission.CAMERA] == true ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

        audioPermissionGranted =
            permissions[Manifest.permission.RECORD_AUDIO] == true ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

        if (cameraPermissionGranted) {
            status = "Camera permission granted"
        } else {
            status = "Camera permission is required"
        }
    }

    LaunchedEffect(Unit) {
        protectedUris = preferences
            .getStringSet("protected_videos", emptySet())
            ?.toSet() ?: emptySet()

        loadVideos()

        if (!cameraPermissionGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    LaunchedEffect(cameraPermissionGranted, frontCamera, selectedQuality) {
        if (!cameraPermissionGranted) return@LaunchedEffect

        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (exception: Exception) {
                status = "Could not start camera: ${exception.message}"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    fun startRecording() {
        val capture = videoCapture

        if (capture == null) {
            status = "Camera is not ready"
            return
        }

        if (recordingActive) return

        val fileName = "HomeCCTV_" +
                SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.US
                ).format(Date()) + ".mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "Movies/HomeCCTV/"
            )
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()

        var pendingRecording = capture.output.prepareRecording(
            context,
            outputOptions
        )

        if (audioEnabled && audioPermissionGranted) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        recording = pendingRecording.start(
            ContextCompat.getMainExecutor(context)
        ) { event ->

            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingActive = true
                    recordingPaused = false
                    status = "Recording"
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
                        status = "Recording saved"
                    }

                    loadVideos()
                }
            }
        }
    }

    fun takeSnapshot() {
        val capture = imageCapture

        if (capture == null) {
            status = "Camera is not ready"
            return
        }

        val fileName = "HomeCCTV_Photo_" +
                SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.US
                ).format(Date()) + ".jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/HomeCCTV/"
            )
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    status = "Snapshot saved"
                    Toast.makeText(
                        context,
                        "Snapshot saved",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    status = "Snapshot failed: ${exception.message}"
                }
            }
        )
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

    fun updateZoom(value: Float) {
        zoomRatio = value
        camera?.cameraControl?.setZoomRatio(value)
    }

    fun toggleProtection(item: VideoItem) {
        val updated = protectedUris.toMutableSet()

        if (updated.contains(item.uri.toString())) {
            updated.remove(item.uri.toString())
        } else {
            updated.add(item.uri.toString())
        }

        protectedUris = updated

        preferences.edit()
            .putStringSet("protected_videos", updated)
            .apply()
    }

    fun deleteVideo(item: VideoItem) {
        if (protectedUris.contains(item.uri.toString())) {
            status = "Unprotect this recording before deleting it"
            return
        }

        try {
            val deleted = context.contentResolver.delete(
                item.uri,
                null,
                null
            )

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "HomeCCTV",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium
        )

        if (!cameraPermissionGranted) {
            Button(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Camera Permission")
            }
        } else {

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Live Camera Preview")

                    AndroidView(
                        factory = { viewContext ->
                            PreviewView(viewContext).also {
                                it.scaleType =
                                    PreviewView.ScaleType.FILL_CENTER
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
                                if (!recordingActive) {
                                    frontCamera = !frontCamera
                                }
                            },
                            enabled = !recordingActive,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (frontCamera) "Front Camera"
                                else "Back Camera"
                            )
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Recording Quality",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        supportedQualities.forEach { quality ->
                            val label = when (quality) {
                                Quality.SD -> "480p"
                                Quality.HD -> "720p"
                                Quality.FHD -> "1080p"
                                else -> quality.toString()
                            }

                            if (selectedQuality == quality) {
                                Button(
                                    onClick = {},
                                    enabled = false
                                ) {
                                    Text(label)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        if (!recordingActive) {
                                            selectedQuality = quality
                                        }
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Recording Controls",
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedButton(
                        onClick = {
                            audioEnabled = !audioEnabled
                            recording?.mute(!audioEnabled)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (audioEnabled) "Audio: ON"
                            else "Audio: OFF"
                        )
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
                            onClick = {
                                recording?.pause()
                            },
                            enabled = recordingActive && !recordingPaused,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pause")
                        }

                        OutlinedButton(
                            onClick = {
                                recording?.resume()
                            },
                            enabled = recordingActive && recordingPaused,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Resume")
                        }
                    }

                    Button(
                        onClick = {
                            recording?.stop()
                        },
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

                    Text(
                        when {
                            recordingPaused -> "Status: Paused"
                            recordingActive -> "Status: Recording"
                            else -> "Status: Not recording"
                        }
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Local Recordings",
                            style = MaterialTheme.typography.titleMedium
                        )

                        TextButton(onClick = { loadVideos() }) {
                            Text("Refresh")
                        }
                    }

                    if (videos.isEmpty()) {
                        Text("No recordings found yet.")
                    }

                    videos.forEach { item ->
                        val isProtected =
                            protectedUris.contains(item.uri.toString())

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Text(item.name)
                            Text(
                                "Size: ${
                                    String.format(
                                        Locale.US,
                                        "%.1f",
                                        item.size / 1024.0 / 1024.0
                                    )
                                } MB"
                            )

                            Text(
                                if (isProtected) "Protected"
                                else "Not protected"
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val intent =
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW
                                            ).apply {
                                                setDataAndType(
                                                    item.uri,
                                                    "video/mp4"
                                                )
                                                addFlags(
                                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                )
                                            }

                                        try {
                                            context.startActivity(intent)
                                        } catch (exception: Exception) {
                                            Toast.makeText(
                                                context,
                                                "No video player available",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                ) {
                                    Text("Play")
                                }

                                OutlinedButton(
                                    onClick = { toggleProtection(item) }
                                ) {
                                    Text(
                                        if (isProtected) "Unprotect"
                                        else "Protect"
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        videoToDelete = item
                                    },
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

    if (videoToDelete != null) {
        AlertDialog(
            onDismissRequest = { videoToDelete = null },
            title = { Text("Delete recording?") },
            text = {
                Text(
                    "This will delete this local recording. " +
                            "This action cannot be undone."
                )
            },
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
                TextButton(
                    onClick = { videoToDelete = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
