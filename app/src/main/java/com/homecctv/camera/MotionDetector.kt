package com.homecctv.camera

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class MotionDetector(
    private val threshold: Int = 25,
    private val pixelRatioThreshold: Float = 0.04f,
    private val onMotionDetected: () -> Unit
) {
    private var previousBuffer: ByteArray? = null
    private var lastTriggerTime: Long = 0L

    fun processLuminance(imageProxy: ImageProxy) {
        try {
            val planes = imageProxy.planes
            if (planes.isEmpty()) return

            val plane = planes[0] ?: return
            val buffer: ByteBuffer = plane.buffer ?: return
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            buffer.rewind() // Rewind buffer so liveStream can also read it!

            val step = 8
            val sampledSize = data.size / step
            val currentSampled = ByteArray(sampledSize)
            for (i in 0 until sampledSize) {
                currentSampled[i] = data[i * step]
            }

            val previous = previousBuffer
            if (previous != null && previous.size == currentSampled.size) {
                var changedPixels = 0

                for (i in currentSampled.indices) {
                    val diff = Math.abs((currentSampled[i].toInt() and 0xFF) - (previous[i].toInt() and 0xFF))
                    if (diff > threshold) {
                        changedPixels++
                    }
                }

                val changeRatio = changedPixels.toFloat() / sampledSize.toFloat()
                val now = System.currentTimeMillis()

                if (changeRatio > pixelRatioThreshold && (now - lastTriggerTime > 3000)) {
                    lastTriggerTime = now
                    onMotionDetected()
                }
            }

            previousBuffer = currentSampled
        } catch (_: Throwable) {}
    }
}