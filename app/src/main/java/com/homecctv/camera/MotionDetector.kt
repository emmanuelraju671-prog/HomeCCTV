package com.homecctv.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class MotionDetector(
    var sensitivityThreshold: Int = 25, // Pixel brightness difference (1-100)
    var minChangedPixelsFraction: Float = 0.04f, // Percentage of frame changed to trigger (4%)
    private val onMotionDetected: () -> Unit
) : ImageAnalysis.Analyzer {

    private var previousFrame: ByteArray? = null
    private var lastTriggerTimestamp = 0L
    private val cooldownPeriodMs = 12000L // 12-second cooldown so it doesn't re-trigger mid-clip

    override fun analyze(image: ImageProxy) {
        val plane = image.planes[0] // Y plane (luminance)
        val buffer = plane.buffer
        val currentLuma = buffer.toByteArray()

        val width = image.width
        val height = image.height

        // Downsample check (sample 1 in every 8 pixels to keep CPU usage low)
        val step = 8
        var changedPixels = 0
        var totalSampledPixels = 0

        val prev = previousFrame

        if (prev != null && prev.size == currentLuma.size) {
            val totalBytes = currentLuma.size

            var i = 0
            while (i < totalBytes) {
                val diff = kotlin.math.abs((currentLuma[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
                if (diff > sensitivityThreshold) {
                    changedPixels++
                }
                totalSampledPixels++
                i += step
            }

            val fractionChanged = changedPixels.toFloat() / totalSampledPixels.coerceAtLeast(1)

            val now = System.currentTimeMillis()
            if (fractionChanged >= minChangedPixelsFraction && (now - lastTriggerTimestamp > cooldownPeriodMs)) {
                lastTriggerTimestamp = now
                onMotionDetected()
            }
        }

        // Store current frame for next comparison
        previousFrame = currentLuma.clone()
        image.close()
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        val data = ByteArray(remaining())
        get(data)
        return data
    }
}