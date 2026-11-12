package com.homecctv.camera

import android.content.Context
import android.net.Uri
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.util.Collections
import kotlin.concurrent.thread

class GoogleDriveManager(private val context: Context) {

    private var driveService: Drive? = null

    fun getSignInClient(): GoogleSignInClient {
        val webClientIdResId = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName
        )

        val webClientId = if (webClientIdResId != 0) {
            context.getString(webClientIdResId)
        } else {
            ""
        }

        val optionsBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))

        if (webClientId.isNotBlank()) {
            optionsBuilder.requestIdToken(webClientId)
        }

        return GoogleSignIn.getClient(context, optionsBuilder.build())
    }

    fun isDriveConnected(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))
    }

    fun getConnectedAccountName(): String? {
        return GoogleSignIn.getLastSignedInAccount(context)?.email
    }

    fun initFromAccount(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            Collections.singleton(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("HomeCCTV")
            .build()
    }

    fun initializeIfSignedIn() {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null && GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))) {
            initFromAccount(account)
        }
    }

    /**
     * Upload a local video clip Uri to Google Drive in the "HomeCCTV" folder
     */
    fun uploadVideoClip(
        uri: Uri,
        fileName: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        initializeIfSignedIn()
        val service = driveService
        if (service == null) {
            onComplete(false, "Google Drive account not connected")
            return
        }

        thread(start = true, name = "DriveUploadThread") {
            try {
                val folderId = getOrCreateFolder("HomeCCTV", service)

                val fileMetadata = File().apply {
                    name = fileName
                    mimeType = "video/mp4"
                    if (folderId != null) {
                        parents = Collections.singletonList(folderId)
                    }
                }

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Could not read local video file")

                val mediaContent = InputStreamContent("video/mp4", inputStream)

                val uploadedFile = service.files().create(fileMetadata, mediaContent)
                    .setFields("id, name, size")
                    .execute()

                onComplete(true, uploadedFile.id)
            } catch (e: Exception) {
                onComplete(false, e.localizedMessage ?: "Drive upload failed")
            }
        }
    }

    private fun getOrCreateFolder(folderName: String, service: Drive): String? {
        try {
            val query = "mimeType = 'application/vnd.google-apps.folder' and name = '$folderName' and trashed = false"
            val result = service.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()

            if (result.files.isNotEmpty()) {
                return result.files[0].id
            }

            val folderMetadata = File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
            }

            val folder = service.files().create(folderMetadata)
                .setFields("id")
                .execute()

            return folder.id
        } catch (_: Exception) {
            return null
        }
    }
}