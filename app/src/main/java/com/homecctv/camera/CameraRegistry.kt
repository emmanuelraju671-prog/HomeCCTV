
package com.homecctv.camera

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

data class CameraDevice(
    val deviceId: String = "",
    val name: String = "",
    val role: String = "camera",
    val model: String = "",
    val lastSeen: Long = 0L
)

class CameraRegistry(context: Context) {

    companion object {
        private const val DATABASE_URL =
            "https://homecctv-3d855-default-rtdb.asia-southeast1.firebasedatabase.app/"
    }

    private val appContext = context.applicationContext
    private val auth = FirebaseAuth.getInstance()

    private fun devicesReference(): DatabaseReference? {
        val uid = auth.currentUser?.uid ?: return null

        return FirebaseDatabase
            .getInstance(DATABASE_URL)
            .reference
            .child("users")
            .child(uid)
            .child("devices")
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
    }

    fun registerDevice(
        role: String,
        deviceName: String,
        callback: (Boolean, String?) -> Unit
    ) {
        if (role != "camera" && role != "controller") {
            callback(false, "Invalid device role")
            return
        }

        val reference = devicesReference()

        if (reference == null) {
            callback(false, "Please sign in first")
            return
        }

        val id = getDeviceId()

        val deviceData = hashMapOf<String, Any>(
            "deviceId" to id,
            "name" to deviceName.trim().ifBlank { Build.MODEL },
            "role" to role,
            "model" to Build.MODEL,
            "lastSeen" to ServerValue.TIMESTAMP
        )

        reference.child(id)
            .setValue(deviceData)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    callback(true, "Device registered successfully")
                } else {
                    callback(
                        false,
                        task.exception?.localizedMessage
                            ?: "Device registration failed"
                    )
                }
            }
    }

    fun observeCameras(
        onCamerasChanged: (List<CameraDevice>) -> Unit,
        onError: (String) -> Unit
    ): ValueEventListener? {

        val reference = devicesReference()

        if (reference == null) {
            onError("Please sign in first")
            return null
        }

        val listener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                val cameras = snapshot.children.mapNotNull { child ->

                    val role = child.child("role")
                        .getValue(String::class.java)

                    if (role != "camera") {
                        return@mapNotNull null
                    }

                    CameraDevice(
                        deviceId = child.child("deviceId")
                            .getValue(String::class.java)
                            ?: child.key.orEmpty(),

                        name = child.child("name")
                            .getValue(String::class.java)
                            ?: "Unnamed camera",

                        role = role,

                        model = child.child("model")
                            .getValue(String::class.java)
                            ?: "Android device",

                        lastSeen = child.child("lastSeen")
                            .getValue(Long::class.java)
                            ?: 0L
                    )
                }

                onCamerasChanged(cameras)
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.message)
            }
        }

        reference.addValueEventListener(listener)
        return listener
    }

    fun stopObserving(
        listener: ValueEventListener?
    ) {
        if (listener == null) return
        devicesReference()?.removeEventListener(listener)
    }

    fun renameCamera(
        deviceId: String,
        newName: String,
        callback: (Boolean, String?) -> Unit
    ) {
        if (newName.isBlank()) {
            callback(false, "Camera name cannot be empty")
            return
        }

        val reference = devicesReference()

        if (reference == null) {
            callback(false, "Please sign in first")
            return
        }

        reference.child(deviceId)
            .updateChildren(
                mapOf("name" to newName.trim())
            )
            .addOnCompleteListener { task ->
                callback(
                    task.isSuccessful,
                    if (task.isSuccessful) "Camera renamed"
                    else task.exception?.localizedMessage
                        ?: "Rename failed"
                )
            }
    }

    fun removeCamera(
        deviceId: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val reference = devicesReference()

        if (reference == null) {
            callback(false, "Please sign in first")
            return
        }

        reference.child(deviceId)
            .removeValue()
            .addOnCompleteListener { task ->
                callback(
                    task.isSuccessful,
                    if (task.isSuccessful) "Camera removed from account"
                    else task.exception?.localizedMessage
                        ?: "Camera removal failed"
                )
            }
    }
}
