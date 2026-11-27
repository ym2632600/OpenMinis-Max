package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * B8: headless bridge that fires the system camera for the camera offload tool.
 *
 * A transparent, non-exported activity (see the manifest entry) with no UI of its own.
 * Callers use [capture]: it starts this activity with FLAG_ACTIVITY_NEW_TASK from a
 * non-UI context, the activity fires the same ACTION_IMAGE_CAPTURE + EXTRA_OUTPUT
 * intent the chat composer uses, waits for the camera app, and reports the outcome
 * through the returned [CameraCaptureResult].
 *
 * Like the composer, the result is judged from the filesystem, never from resultCode:
 * some camera apps (MIUI in particular) return RESULT_CANCELED on success and
 * RESULT_OK when the user cancelled, so a photo counts only when the staging file
 * exists and has a non-zero length.
 *
 * A capture is bounded by [OffloadPermissionManager.DECISION_TIMEOUT_MS] (5 minutes),
 * both in the activity and in [capture], so a camera session left open can never leak
 * a waiting thread. The bridge owns the B5 floating-bubble suppress flag: it is set
 * just before handing off to the system camera and cleared on every exit path
 * (success / cancel / timeout / launch failure), so a missed clear can never leave
 * the bubble hidden indefinitely. The offload handler (B8 commit 2b) must not set the
 * flag itself; it only calls [capture].
 */
class CameraCaptureActivity : ComponentActivity() {

    sealed interface CameraCaptureResult {
        data class Success(val file: File, val uri: Uri) : CameraCaptureResult
        data object Cancelled : CameraCaptureResult
        data object NoCameraApp : CameraCaptureResult
        data object Timeout : CameraCaptureResult
    }

    private var requestId: Long = -1L
    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var launched = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { finishWith(CameraCaptureResult.Timeout) }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The result code is deliberately ignored (MIUI lesson, see the class comment):
        // only the staging file tells us whether a photo was taken.
        val file = outputFile
        val uri = outputUri
        if (file != null && uri != null && file.exists() && file.length() > 0) {
            finishWith(CameraCaptureResult.Success(file, uri))
        } else {
            finishWith(CameraCaptureResult.Cancelled)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getLongExtra(EXTRA_REQUEST_ID, -1L)
        outputUri = intent.getParcelableExtra(EXTRA_OUTPUT_URI)
        outputFile = intent.getStringExtra(EXTRA_OUTPUT_PATH)?.let { File(it) }
        val pending = pendingRequest
        if (requestId == -1L || outputUri == null || outputFile == null || pending?.id != requestId) {
            finish()
            return
        }
        timeoutHandler.postDelayed(timeoutRunnable, pending.timeoutMs)
        if (savedInstanceState?.getBoolean(KEY_LAUNCHED) == true) {
            // Recreated while the camera was up (e.g. rotation): the result is
            // redelivered to the launcher; do not launch the camera a second time.
            launched = true
            return
        }
        val uri = outputUri!!
        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        SessionActivityTracker.setCameraSuppressActive(true)
        launched = true
        try {
            cameraLauncher.launch(captureIntent)
        } catch (e: Exception) {
            finishWith(CameraCaptureResult.NoCameraApp)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, launched)
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        SessionActivityTracker.setCameraSuppressActive(false)
        // If the bridge dies before the camera reports back, unblock the caller
        // instead of leaving it suspended on a capture that can never complete.
        if (requestId != -1L) completePending(CameraCaptureResult.Cancelled)
        super.onDestroy()
    }

    private fun finishWith(result: CameraCaptureResult) {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        SessionActivityTracker.setCameraSuppressActive(false)
        completePending(result)
        finish()
    }

    private fun completePending(result: CameraCaptureResult) {
        val pending = pendingRequest
        if (pending != null && pending.id == requestId) {
            pendingRequest = null
            pending.deferred.complete(result)
        }
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "com.openminis.app.sandbox.offload.CameraCaptureActivity.REQUEST_ID"
        private const val EXTRA_OUTPUT_URI = "com.openminis.app.sandbox.offload.CameraCaptureActivity.OUTPUT_URI"
        private const val EXTRA_OUTPUT_PATH = "com.openminis.app.sandbox.offload.CameraCaptureActivity.OUTPUT_PATH"
        private const val KEY_LAUNCHED = "camera_capture_launched"

        private data class PendingCapture(
            val id: Long,
            val timeoutMs: Long,
            val deferred: CompletableDeferred<CameraCaptureResult>,
        )

        private var pendingRequest: PendingCapture? = null
        private var nextRequestId = 0L

        /**
         * Fires the system camera into [output] (from [createCameraOutputUri]) and
         * suspends until the camera app reports back, the user cancels, no camera app
         * is installed, or [timeoutMs] elapses. The timeout defaults to
         * [OffloadPermissionManager.DECISION_TIMEOUT_MS] (5 minutes). Single-flight: a
         * newer capture supersedes an in-flight one, which completes as [CameraCaptureResult.Cancelled].
         */
        suspend fun capture(
            context: Context,
            output: Pair<Uri, File>,
            timeoutMs: Long = OffloadPermissionManager.DECISION_TIMEOUT_MS,
        ): CameraCaptureResult {
            val deferred = CompletableDeferred<CameraCaptureResult>()
            val id: Long
            synchronized(this) {
                pendingRequest?.deferred?.complete(CameraCaptureResult.Cancelled)
                id = ++nextRequestId
                pendingRequest = PendingCapture(id, timeoutMs, deferred)
            }
            val intent = Intent(context, CameraCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_REQUEST_ID, id)
                putExtra(EXTRA_OUTPUT_URI, output.first)
                putExtra(EXTRA_OUTPUT_PATH, output.second.absolutePath)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                synchronized(this) {
                    if (pendingRequest?.id == id) pendingRequest = null
                }
                return CameraCaptureResult.NoCameraApp
            }
            return try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                synchronized(this) {
                    if (pendingRequest?.id == id) pendingRequest = null
                }
                CameraCaptureResult.Timeout
            }
        }
    }
}
