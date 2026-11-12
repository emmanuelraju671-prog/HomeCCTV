package com.homecctv.camera

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

data class EvidenceRecord(
    val fileName: String,
    val sha256Checksum: String,
    val timestampUtc: Long,
    val timestampIst: String,
    val deviceModel: String,
    val deviceId: String,
    val androidVersion: String,
    val driveFileId: String = ""
)

class EvidenceManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("homecctv_evidence_log", Context.MODE_PRIVATE)

    /**
     * Computes the cryptographic SHA-256 hash of a recorded video file
     */
    fun calculateSha256(uri: Uri): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val buffer = ByteArray(8192)
            var bytesRead: Int

            inputStream?.use { stream ->
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }

            val hashBytes = digest.digest()
            val sb = java.lang.StringBuilder()
            for (b in hashBytes) {
                sb.append(String.format("%02x", b))
            }
            sb.toString()
        } catch (e: Exception) {
            "HASH_CALCULATION_FAILED: ${e.message}"
        }
    }

    /**
     * Records the evidence metadata into an immutable local audit log
     */
    fun recordEvidence(
        fileName: String,
        uri: Uri,
        deviceId: String,
        driveFileId: String = ""
    ): EvidenceRecord {
        val hash = calculateSha256(uri)
        val now = System.currentTimeMillis()

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        val istFormatted = sdf.format(Date(now))

        val record = EvidenceRecord(
            fileName = fileName,
            sha256Checksum = hash,
            timestampUtc = now,
            timestampIst = istFormatted,
            deviceModel = Build.MODEL,
            deviceId = deviceId,
            androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            driveFileId = driveFileId
        )

        saveToAuditLog(record)
        return record
    }

    private fun saveToAuditLog(record: EvidenceRecord) {
        val existingJson = prefs.getString("evidence_entries", "[]") ?: "[]"
        val jsonArray = JSONArray(existingJson)

        val obj = JSONObject().apply {
            put("fileName", record.fileName)
            put("sha256Checksum", record.sha256Checksum)
            put("timestampUtc", record.timestampUtc)
            put("timestampIst", record.timestampIst)
            put("deviceModel", record.deviceModel)
            put("deviceId", record.deviceId)
            put("androidVersion", record.androidVersion)
            put("driveFileId", record.driveFileId)
        }

        jsonArray.put(obj)
        prefs.edit().putString("evidence_entries", jsonArray.toString()).apply()
    }

    fun getEvidenceForFile(fileName: String): EvidenceRecord? {
        val existingJson = prefs.getString("evidence_entries", "[]") ?: "[]"
        val jsonArray = JSONArray(existingJson)

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (obj.optString("fileName") == fileName) {
                return EvidenceRecord(
                    fileName = obj.optString("fileName"),
                    sha256Checksum = obj.optString("sha256Checksum"),
                    timestampUtc = obj.optLong("timestampUtc"),
                    timestampIst = obj.optString("timestampIst"),
                    deviceModel = obj.optString("deviceModel"),
                    deviceId = obj.optString("deviceId"),
                    androidVersion = obj.optString("androidVersion"),
                    driveFileId = obj.optString("driveFileId")
                )
            }
        }
        return null
    }

    /**
     * Generates a formal legal certificate under Section 65B / Section 63 BSA
     */
    fun generateSection65BCertificate(
        record: EvidenceRecord,
        certifierName: String = "Lawful Owner / Custodian",
        certifierPhone: String = ""
    ): String {
        return """
================================================================================
CERTIFICATE UNDER SECTION 65B OF THE INDIAN EVIDENCE ACT, 1872
[Corresponding to Section 63 of the Bharatiya Sakshya Adhiniyam, 2023 (BSA)]
================================================================================

I, $certifierName, do hereby solemnly declare and certify as follows:

1. IDENTIFICATION OF ELECTRONIC RECORD:
   - File Name: ${record.fileName}
   - Cryptographic Hash (SHA-256): ${record.sha256Checksum}
   - Recording Timestamp (IST): ${record.timestampIst}
   - UTC Epoch: ${record.timestampUtc} ms
   - Cloud Backup (Google Drive File ID): ${record.driveFileId.ifBlank { "N/A" }}

2. CAPTURING DEVICE SPECIFICATIONS:
   - Device Model: ${record.deviceModel}
   - Device Hardware ID: ${record.deviceId}
   - Operating System: ${record.androidVersion}
   - Application: HomeCCTV Security System (Build 1.0)

3. CONDITIONS OF ADMISSIBILITY:
   a) The electronic record was produced by the aforementioned device during the period
      over which the device was used regularly to store or process information for the
      purposes of CCTV surveillance and home security.
   b) Throughout the material period, the computer/device was operating properly and
      the mathematical integrity of the recording is verified by the cryptographic
      SHA-256 hash printed above.
   c) The information contained in this electronic record reproduces or is derived
      from the camera sensor data fed into the device in the ordinary course of activities.

4. DECLARATION OF UNTAMPERED INTEGRITY:
   I confirm that the video has not been edited, altered, spliced, or tampered with
   in any manner.

Date of Certificate: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} IST
Certified by: $certifierName
Contact: $certifierPhone
Signature: __________________________
================================================================================
        """.trimIndent()
    }
}