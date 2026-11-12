package com.homecctv.camera

import android.content.Context
import android.os.Build
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class EmailAlertManager(context: Context) {

    private val prefs = context.getSharedPreferences("homecctv_preferences", Context.MODE_PRIVATE)

    companion object {
        const val PREF_ALERT_EMAIL = "alert_recipient_email"
        // Paste your Google Apps Script Web App URL below:
        private const val EMAIL_RELAY_URL = "https://script.google.com/macros/s/AKfycbzRnUJlKiA5ghGRB8SBAjC6x-Xo-2nLoGbL9ak_xRWtTj2r0gNZubR8QqJmASHSfiytag/exec"
    }

    fun getRecipientEmail(): String {
        return prefs.getString(PREF_ALERT_EMAIL, "") ?: ""
    }

    fun setAlertEmail(email: String) {
        prefs.edit().putString(PREF_ALERT_EMAIL, email.trim()).apply()
    }

    fun isConfigured(): Boolean {
        return getRecipientEmail().isNotBlank()
    }

    fun sendMotionAlert(
        cameraName: String = Build.MODEL,
        clipFileName: String,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        val recipient = getRecipientEmail()
        if (recipient.isBlank()) {
            onResult(false, "No recipient email set")
            return
        }

        thread(start = true, name = "EmailAlertThread") {
            try {
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val jsonPayload = """
                    {
                        "recipient": "$recipient",
                        "cameraName": "$cameraName",
                        "clipFileName": "$clipFileName",
                        "timestamp": "$timeStr"
                    }
                """.trimIndent()

                val url = URL(EMAIL_RELAY_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(jsonPayload)
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode
                if (responseCode == 200 || responseCode == 302) {
                    onResult(true, "Alert email sent to $recipient")
                } else {
                    onResult(false, "Relay returned code: $responseCode")
                }
                conn.disconnect()
            } catch (e: Exception) {
                onResult(false, e.localizedMessage ?: "Failed to post email alert")
            }
        }
    }
}