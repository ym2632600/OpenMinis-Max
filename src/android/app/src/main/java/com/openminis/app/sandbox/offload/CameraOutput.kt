package com.openminis.app.sandbox.offload

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B8: shared camera-output staging for the chat composer and the camera offload bridge.
 *
 * Moved verbatim from `ChatScreenHelpers.kt` (ui/chat) so the offload layer can stage a
 * capture without depending on the chat UI. Same staging directory
 * (filesDir/camera-photos/), same timestamped JPEG name and the same FileProvider
 * authority ("${applicationId}.fileprovider"). There is exactly one copy of this
 * function; the composer calls this one.
 *
 * Creates the staging file for a camera capture and returns its [Uri] (to hand to the
 * camera app) together with the [File] the photo lands in. The file is created eagerly
 * so the camera app always has a real file to write into.
 */
fun createCameraOutputUri(context: Context): Pair<Uri, File>? {
    val dir = File(context.filesDir, "camera-photos").apply { mkdirs() }
    val name = "photo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
    val photoFile = File(dir, name)
    photoFile.createNewFile()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile) to photoFile
}
