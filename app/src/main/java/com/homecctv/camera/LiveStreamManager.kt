package com.homecctv.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import androidx.camera.core.ImageProxy
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream

class LiveStreamManager(private val deviceId: String) {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase
        .getInstance("https://homecctv-3d855-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference

    private var frameListener: ValueEventListener? = null
    private var lastSentTime = 0L
    private val frameThrottleMs = 150L // ~7-8 frames per second to stay smooth and save data

    /**
     * Called by Redmi to compress and push live camera frame
     */
    fun pushLiveFrame(imageProxy: ImageProxy) {
        val uid = auth.currentUser?.uid ?: run {
            imageProxy.close()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSentTime < frameThrottleMs) {
            imageProxy.close()
            return
        }
        lastSentTime = now

        try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            // Compress down to 320x240 @ 40% JPEG for low latency streaming
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 40, out)
            val jpegBytes = out.toByteArray()

            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            database.child("users").child(uid).child("live_feed").child(deviceId).setValue(base64)
        } catch (_: Exception) {
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Called by Samsung Controller to receive and render live camera bitmap
     */
    fun startReceiving(onBitmapReceived: (Bitmap) -> Unit) {
        val uid = auth.currentUser?.uid ?: return
        val ref = database.child("users").child(uid).child("live_feed").child(deviceId)

        frameListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val base64 = snapshot.getValue(String::class.java) ?: return
                if (base64.isBlank()) return
                try {
                    val bytes = Base64.decode(base64, Base64.NO_WRAP)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        onBitmapReceived(bitmap)
                    }
                } catch (_: Exception) {}
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(frameListener!!)
    }

    fun stopReceiving() {
        val uid = auth.currentUser?.uid ?: return
        frameListener?.let {
            database.child("users").child(uid).child("live_feed").child(deviceId).removeEventListener(it)
        }
    }
}