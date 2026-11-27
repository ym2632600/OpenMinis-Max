package com.openminis.app.sandbox.offload

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * B8: shared camera-output staging for the chat composer and the camera offload bridge.
 *
 * Moved verbatim from `ChatScreenHelpers.kt` (ui/chat) so the offload layer can stage a
 * capture without depending on the chat UI. Same staging directory
 * (filesDir/camera-photos/), same timestamped JPEG name and the same FileProvider
 * authority ("${applicationId}.fileprovider"). There is exactly one copy of this
 * function; the composer calls this one.
 *
 * Create an empty JPEG file under filesDir/camera-photos/ and return a
 * FileProvider content URI for it, plus the underlying File. The camera
 * app writes the captured image into this URI synchronously.
 *
 * Bug 1 (MIUI): the previous version put the staging file under cacheDir,
 * which MIUI may clear while the camera Activity is in flight. Moving to
 * filesDir keeps the URI valid until we explicitly delete it.
 */
fun createCameraOutputUri(context: Context): Pair<Uri, File> {
    val dir = File(context.filesDir, "camera-photos").also { it.mkdirs() }
    val ts = System.currentTimeMillis()
    val file = File(dir, "photo-$ts.jpg")
    file.createNewFile()
    val authority = context.packageName + ".fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, file)
    return uri to file
}
