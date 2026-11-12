package com.homecctv.camera

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

class FirebaseManager(context: Context) {

    companion object {
        private const val DATABASE_URL =
            "https://homecctv-3d855-default-rtdb.asia-southeast1.firebasedatabase.app/"
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase
        .getInstance(DATABASE_URL)
        .reference

    private val appContext = context.applicationContext

    val deviceId: String
        get() = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"

    fun isSignedIn(): Boolean {
        return auth.currentUser != null
    }

    fun register(
        email: String,
        password: String,
        role: String,
        callback: (Boolean, String?) -> Unit
    ) {
        auth.createUserWithEmailAndPassword(
            email.trim(),
            password
        ).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                saveDevice(role, callback)
            } else {
                callback(
                    false,
                    task.exception?.localizedMessage
                        ?: "Account registration failed"
                )
            }
        }
    }

    fun signIn(
        email: String,
        password: String,
        role: String,
        callback: (Boolean, String?) -> Unit
    ) {
        auth.signInWithEmailAndPassword(
            email.trim(),
            password
        ).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                saveDevice(role, callback)
            } else {
                callback(
                    false,
                    task.exception?.localizedMessage
                        ?: "Login failed"
                )
            }
        }
    }

    fun saveDevice(
        role: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val user = auth.currentUser

        if (user == null) {
            callback(false, "No signed-in Firebase user")
            return
        }

        if (role != "camera" && role != "controller") {
            callback(false, "Invalid device role")
            return
        }

        // Use updateChildren so existing fields (like name or settings) are not wiped
        val deviceUpdates = hashMapOf<String, Any>(
            "deviceId" to deviceId,
            "role" to role,
            "model" to Build.MODEL,
            "platform" to "Android",
            "lastSeen" to ServerValue.TIMESTAMP,
            "online" to true
        )

        database
            .child("users")
            .child(user.uid)
            .child("devices")
            .child(deviceId)
            .updateChildren(deviceUpdates)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    callback(true, "Firebase connected successfully")
                } else {
                    callback(
                        false,
                        task.exception?.localizedMessage
                            ?: "Device registration failed"
                    )
                }
            }
    }

    fun setDeviceOnlineStatus(online: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val updates = hashMapOf<String, Any>(
            "online" to online,
            "lastSeen" to ServerValue.TIMESTAMP
        )
        database
            .child("users")
            .child(uid)
            .child("devices")
            .child(deviceId)
            .updateChildren(updates)
    }

    fun signOut() {
        setDeviceOnlineStatus(false)
        auth.signOut()
    }

    fun getUserId(): String? {
        return auth.currentUser?.uid
    }
}