package com.homecctv.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sin

class SirenManager(private val context: Context) {

    private var isSirenPlaying = false
    private var sirenThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    init {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.US
                isTtsReady = true
            }
        }
    }

    /**
     * Start playing a pulsing police/security siren sound on the device speaker
     */
    fun startSiren() {
        if (isSirenPlaying) return
        isSirenPlaying = true

        sirenThread = thread(start = true, name = "SirenThread") {
            val sampleRate = 44100
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            track.play()

            val buffer = ShortArray(1024)
            var currentFreq = 700.0
            var direction = 1
            var angle = 0.0

            try {
                while (isSirenPlaying) {
                    // Warble pitch between 700Hz and 1600Hz
                    currentFreq += direction * 8.0
                    if (currentFreq >= 1600.0) direction = -1
                    if (currentFreq <= 700.0) direction = 1

                    val deltaAngle = 2.0 * Math.PI * currentFreq / sampleRate

                    for (i in buffer.indices) {
                        buffer[i] = (sin(angle) * Short.MAX_VALUE * 0.9).toInt().toShort()
                        angle += deltaAngle
                        if (angle > 2.0 * Math.PI) angle -= 2.0 * Math.PI
                    }

                    track.write(buffer, 0, buffer.size)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Stop the siren immediately
     */
    fun stopSiren() {
        isSirenPlaying = false
        sirenThread?.interrupt()
        sirenThread = null
    }

    fun isSirenActive(): Boolean = isSirenPlaying

    /**
     * Voice warning played through phone speaker
     */
    fun speakWarning(text: String = "Warning! Motion detected. You are being recorded.") {
        if (isTtsReady) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "WarningUtterance")
        }
    }

    fun release() {
        stopSiren()
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
    }
}