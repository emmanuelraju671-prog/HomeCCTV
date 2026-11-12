package com.homecctv.camera

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlin.concurrent.thread

class IntercomManager(private val deviceRole: String) {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase
        .getInstance("https://homecctv-3d855-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference

    private val sampleRate = 8000 // 8kHz standard telephony - ultra lightweight & works on all chips
    private var isTalking = false
    private var isListening = false

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var listenerRef: ValueEventListener? = null

    init {
        initSpeaker()
    }

    private fun initSpeaker() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
                .build().apply { play() }
        } catch (_: Exception) {}
    }

    fun startListening() {
        val uid = auth.currentUser?.uid ?: return
        if (isListening) return
        isListening = true

        val listenPath = if (deviceRole == "camera") "controller_voice" else "camera_voice"
        val ref = database.child("users").child(uid).child("intercom").child("live").child(listenPath)

        listenerRef = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val base64Data = snapshot.getValue(String::class.java) ?: return
                if (base64Data.isBlank()) return
                try {
                    val pcmBytes = Base64.decode(base64Data, Base64.NO_WRAP)
                    audioTrack?.write(pcmBytes, 0, pcmBytes.size)
                } catch (_: Exception) {}
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listenerRef!!)
    }

    @SuppressLint("MissingPermission")
    fun startTalking() {
        val uid = auth.currentUser?.uid ?: return
        if (isTalking) return
        isTalking = true

        val sendPath = if (deviceRole == "camera") "camera_voice" else "controller_voice"
        val ref = database.child("users").child(uid).child("intercom").child("live").child(sendPath)

        thread(start = true, name = "IntercomVoiceThread") {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                audioRecord = recorder

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    isTalking = false
                    return@thread
                }

                recorder.startRecording()
                val buffer = ByteArray(1024)

                while (isTalking) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val base64Chunk = Base64.encodeToString(buffer.copyOf(read), Base64.NO_WRAP)
                        ref.setValue(base64Chunk)
                    }
                    Thread.sleep(80)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                    ref.setValue("")
                } catch (_: Exception) {}
            }
        }
    }

    fun stopTalking() {
        isTalking = false
        val uid = auth.currentUser?.uid ?: return
        val sendPath = if (deviceRole == "camera") "camera_voice" else "controller_voice"
        database.child("users").child(uid).child("intercom").child("live").child(sendPath).setValue("")
    }

    fun release() {
        isTalking = false
        isListening = false
        listenerRef?.let {
            val uid = auth.currentUser?.uid
            if (uid != null) {
                val listenPath = if (deviceRole == "camera") "controller_voice" else "camera_voice"
                database.child("users").child(uid).child("intercom").child("live").child(listenPath).removeEventListener(it)
            }
        }
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}
    }
}