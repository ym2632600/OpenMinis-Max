package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.SessionMounts
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * Host-side implementation of `android-camera` — takes a photo with the
 * system camera app and hands the file to the agent.
 *
 * Usage:
 *   android-camera
 *   android-camera --help
 *
 * The photo is staged by the capture bridge (commit 2a) under
 * filesDir/camera-photos/ and then published into the calling session's
 * attachments directory (filesDir/minis-sessions/<id>/attachments/), which
 * the sandbox already bind-mounts as /var/minis/attachments — so the agent
 * can open the returned path and feed it to vision with no second
 * attachment pipeline. Outside a chat session there is no mount, so the
 * host file path is returned instead.
 *
 * Success output is JSON: {"path", "host_path", "uri", "bytes", "mime"}.
 * Failures are structured JSON too: camera_permission_denied (exit 77),
 * camera_cancelled, camera_no_camera_app and camera_timeout (exit 1).
 *
 * The handler owns the CAMERA permission flow (same system-dialog → poll →
 * settings-gate flow as the SMS handler, using the camera strings landed
 * in 2e66a1f/d2fc6f3 for the settings gate) and the B5 bubble-suppress flag
 * around the handoff; the capture itself is delegated to
 * [CameraCaptureActivity].
 */
class CameraOffloadHandler internal constructor(
    private val context: Context,
    private val system: CameraSystem,
) : NativeOffloadHandler {

    constructor(context: Context) : this(context, RealCameraSystem(context))

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val rawArgs = request.argv.drop(1)
        if (rawArgs.size == 1 && (rawArgs[0] == "-h" || rawArgs[0] == "--help")) {
            return NativeOffloadResult(0, HELP_TEXT)
        }
        val parsedArgs = OffloadArgs(rawArgs)
        if (parsedArgs.positional.isNotEmpty() || hasUnknownOption(rawArgs)) {
            return NativeOffloadResult(2, "android-camera: usage: android-camera [--help]\n")
        }

        OffloadGate.enforce("camera", "android-camera capture", parsedArgs, request)?.let { return it }

        system.ensurePermission(parsedArgs)?.let { return it }

        val output = try {
            system.createOutput()
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "createOutput failed: ${e.message}")
            return errorResult(1, "camera_error", "Could not stage a file for the photo: ${e.message}", parsedArgs)
        }

        // B5: hide the floating bubble while the system camera is in front.
        // The bridge activity sets the same flag just before launching the
        // camera and clears it in finishWith/onDestroy; setting it here too
        // covers the handoff itself. Both writes are idempotent plain-boolean
        // sets, and this finally clears the flag on EVERY exit path — success,
        // cancel, timeout, launch failure or an exception — so a missed clear
        // can never hide the bubble indefinitely.
        SessionActivityTracker.setCameraSuppressActive(true)
        val result = try {
            runBlocking { system.capture(output) }
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "SecurityException: ${e.message}")
            return errorResult(
                77,
                "camera_permission_refused",
                "The camera permission is granted but the system refused the capture. " +
                    "Some OEMs gate the camera behind a separate per-app privacy switch. " +
                    "Underlying: ${e.message}",
                parsedArgs,
            )
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            return errorResult(1, "camera_error", e.message ?: "unknown camera error", parsedArgs)
        } finally {
            SessionActivityTracker.setCameraSuppressActive(false)
        }

        return when (result) {
            is CameraCaptureActivity.CameraCaptureResult.Success -> publishSuccess(result, parsedArgs, request)
            CameraCaptureActivity.CameraCaptureResult.Cancelled ->
                errorResult(1, "camera_cancelled", "The user cancelled the camera capture; no photo was taken.", parsedArgs)
            CameraCaptureActivity.CameraCaptureResult.NoCameraApp ->
                errorResult(1, "camera_no_camera_app", "No camera app is available on this device, or the camera could not be launched.", parsedArgs)
            CameraCaptureActivity.CameraCaptureResult.Timeout ->
                errorResult(1, "camera_timeout", "Timed out waiting for the user to take a photo.", parsedArgs)
        }
    }

    // ---------------------------------------------------------------- publish

    private fun publishSuccess(
        result: CameraCaptureActivity.CameraCaptureResult.Success,
        parsedArgs: OffloadArgs,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        val file = result.file
        // Trust the filesystem, never resultCode (MIUI quirk): the bridge only
        // reports Success when the file exists and is non-empty, and the same
        // check runs here before the file is published to the agent.
        if (!file.exists() || file.length() <= 0) {
            AppLogger.warning(TAG, "capture reported success but ${file.absolutePath} is missing or empty")
            return errorResult(1, "camera_cancelled", "The camera reported a photo but no image file was produced.", parsedArgs)
        }

        // Resolve the caller once (helper -> parent chat) and use it everywhere.
        val sessionId = OffloadCallerSession.resolve(request.sessionId).sid
        var path = file.absolutePath
        if (sessionId != null) {
            val dest = File(SessionMounts.sessionDir(context.filesDir, sessionId, "attachments"), file.name)
            try {
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
                if (dest.exists() && dest.length() == file.length()) {
                    // Host minis-sessions/<id>/attachments is bind-mounted as
                    // /var/minis/attachments, so this is the path the agent can open.
                    path = "/var/minis/attachments/${file.name}"
                } else {
                    AppLogger.warning(TAG, "session copy of ${file.name} is incomplete; reporting the host path")
                }
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "could not publish ${file.name} to session $sessionId: ${e.message}")
            }
        }

        val body = JSONObject()
            .put("path", path)
            .put("host_path", file.absolutePath)
            .put("uri", result.uri.toString())
            .put("bytes", file.length())
            .put("mime", "image/jpeg")
            .toString()
        AppLogger.info(TAG, "photo captured: ${file.length()} bytes -> $path")
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, parsedArgs) + "\n")
    }

    private fun errorResult(exitCode: Int, code: String, message: String, args: OffloadArgs): NativeOffloadResult =
        NativeOffloadResult(
            exitCode,
            OffloadOutput.formatBody(
                JSONObject().put("error", code).put("message", message).toString(),
                args,
            ) + "\n",
        )

    private fun hasUnknownOption(rawArgs: List<String>): Boolean =
        rawArgs.any { it.startsWith("-") && it !in OUTPUT_OPTIONS }

    companion object {
        private const val TAG = "CameraOffloadHandler"

        /** Flags the output formatter understands; everything else is a usage error. */
        private val OUTPUT_OPTIONS = setOf("--compact", "--quiet", "-q")

        private val HELP_TEXT = """
            android-camera — take a photo with the system camera.
            Usage:
              android-camera           Take a photo; the user sees the system camera.
              android-camera --help    Show this help.
            Output: JSON object {path, host_path, uri, bytes, mime}.
              path is the path the agent can open: /var/minis/attachments/<name> for a
              chat session, or the host file path when run outside a chat.
            Requires the CAMERA permission.
            Errors: camera_permission_denied (77), camera_cancelled, camera_no_camera_app,
            camera_timeout (exit 1 unless noted).
        """.trimIndent()
    }
}

/**
 * Test seam for [CameraOffloadHandler], following the SpeechOffloadHandler /
 * SpeechSystem pattern: the production implementation talks to Android, and
 * the unit test drives the handler through a fake.
 */
internal interface CameraSystem {

    /**
     * Same system-dialog → poll → settings-gate flow as contacts/location/
     * calendar/SMS. Returns null if the CAMERA permission ends up granted,
     * otherwise a ready-to-return error result.
     */
    fun ensurePermission(args: OffloadArgs): NativeOffloadResult?

    /** Stages an empty JPEG under filesDir/camera-photos/ (see [createCameraOutputUri]). */
    fun createOutput(): Pair<Uri, File>

    /** Fires the system camera into [output] and suspends for the outcome. */
    suspend fun capture(output: Pair<Uri, File>): CameraCaptureActivity.CameraCaptureResult
}

private class RealCameraSystem(private val context: Context) : CameraSystem {

    override fun ensurePermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasPermission()) return null
        AppLogger.warning(TAG, "CAMERA not granted — routing through permission flow")
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(listOf(Manifest.permission.CAMERA))
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasPermission() })
            ) {
                AppLogger.info(TAG, "Camera permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = Manifest.permission.CAMERA,
                        title = context.getString(R.string.camera_permission_title),
                        message = context.getString(R.string.camera_permission_message),
                        settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = context.getString(R.string.camera_permission_open_settings),
                    ),
                    check = { hasPermission() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "camera_permission_denied")
                        .put("message", "The user declined the camera permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "camera_permission_timeout")
                        .put("message", "Timed out waiting for the user to grant the camera permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun createOutput(): Pair<Uri, File> = createCameraOutputUri(context)

    override suspend fun capture(output: Pair<Uri, File>): CameraCaptureActivity.CameraCaptureResult =
        CameraCaptureActivity.capture(context, output)

    companion object {
        private const val TAG = "CameraOffloadHandler"
    }
}
