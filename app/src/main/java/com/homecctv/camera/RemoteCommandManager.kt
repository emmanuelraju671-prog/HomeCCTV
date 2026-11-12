package com.homecctv.camera

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase

class RemoteCommandManager(private val targetDeviceId: String) {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase
        .getInstance("https://homecctv-3d855-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference

    private var childListener: ChildEventListener? = null

    /**
     * Sent by Samsung Controller -> pushes a guaranteed new command
     */
    fun sendCommand(action: String, value: Any = true) {
        val uid = auth.currentUser?.uid ?: return
        val cmd = hashMapOf<String, Any>(
            "action" to action,
            "value" to value,
            "timestamp" to System.currentTimeMillis()
        )
        database.child("users").child(uid).child("commands").child(targetDeviceId).push().setValue(cmd)
    }

    /**
     * Listened by Redmi Camera -> executes every new command immediately
     */
    fun listenForCommands(onCommandReceived: (action: String, value: Any?) -> Unit) {
        val uid = auth.currentUser?.uid ?: return
        val ref = database.child("users").child(uid).child("commands").child(targetDeviceId)

        childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val action = snapshot.child("action").getValue(String::class.java) ?: return
                val value = snapshot.child("value").value
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                // Process commands sent in the last 30 seconds
                if (System.currentTimeMillis() - timestamp < 30000) {
                    onCommandReceived(action, value)
                    // Clean up command after execution
                    snapshot.ref.removeValue()
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addChildEventListener(childListener!!)
    }

    fun stopListening() {
        val uid = auth.currentUser?.uid ?: return
        childListener?.let {
            database.child("users").child(uid).child("commands").child(targetDeviceId).removeEventListener(it)
        }
    }
}