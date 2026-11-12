package com.homecctv.camera

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.concurrent.TimeUnit

class RetentionManager(private val context: Context) {

    companion object {
        // 14 days in milliseconds: 14 * 24 * 60 * 60 * 1000
        val RETENTION_PERIOD_MS = TimeUnit.DAYS.toMillis(14)
    }

    private val preferences = context.getSharedPreferences("homecctv_preferences", Context.MODE_PRIVATE)

    fun getProtectedUris(): Set<String> {
        return preferences.getStringSet("protected_videos", emptySet())?.toSet() ?: emptySet()
    }

    fun isProtected(uri: Uri): Boolean {
        return getProtectedUris().contains(uri.toString())
    }

    fun toggleProtection(uri: Uri): Boolean {
        val current = getProtectedUris().toMutableSet()
        val uriStr = uri.toString()
        val willBeProtected: Boolean

        if (current.contains(uriStr)) {
            current.remove(uriStr)
            willBeProtected = false
        } else {
            current.add(uriStr)
            willBeProtected = true
        }

        preferences.edit().putStringSet("protected_videos", current).apply()
        return willBeProtected
    }

    /**
     * Scans local HomeCCTV recordings and permanently deletes any file
     * older than 14 days, skipping all protected files.
     * Returns a summary report of deleted items.
     */
    fun purgeExpiredRecordings(): RetentionReport {
        val protectedList = getProtectedUris()
        val now = System.currentTimeMillis()
        val cutoffTimestampSeconds = (now - RETENTION_PERIOD_MS) / 1000

        var deletedCount = 0
        var preservedCount = 0
        var protectedSkippedCount = 0

        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("Movies/HomeCCTV/")

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateAddedSec = cursor.getLong(dateAddedColumn)
                    val itemUri = ContentUris.withAppendedId(collection, id)

                    if (protectedList.contains(itemUri.toString())) {
                        protectedSkippedCount++
                        continue
                    }

                    if (dateAddedSec < cutoffTimestampSeconds) {
                        try {
                            val rows = context.contentResolver.delete(itemUri, null, null)
                            if (rows > 0) {
                                deletedCount++
                            }
                        } catch (_: Exception) {}
                    } else {
                        preservedCount++
                    }
                }
            }
        } catch (_: Exception) {}

        return RetentionReport(
            deletedCount = deletedCount,
            preservedCount = preservedCount,
            protectedCount = protectedSkippedCount
        )
    }

    data class RetentionReport(
        val deletedCount: Int,
        val preservedCount: Int,
        val protectedCount: Int
    )
}