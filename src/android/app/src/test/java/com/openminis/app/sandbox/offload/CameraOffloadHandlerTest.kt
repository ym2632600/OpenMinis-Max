package com.openminis.app.sandbox.offload

import android.content.Context
import android.net.Uri
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.SessionMounts
import com.openminis.app.service.SessionActivityTracker
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * B8: the camera handler's envelope and exit codes, driven through a fake
 * camera system (same seam pattern as SpeechOffloadHandlerTest). The fake
 * stands in for the Android permission flow and the system camera app, so
 * the arg parsing, error paths, session publish and the B5 bubble-suppress
 * flag can run on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class CameraOffloadHandlerTest {

    /** Fake [CameraSystem]: canned permission outcome and capture outcome. */
    internal class FakeCameraSystem : CameraSystem {
        var permission: NativeOffloadResult? = null
        var captureResult: CameraCaptureActivity.CameraCaptureResult =
            CameraCaptureActivity.CameraCaptureResult.Cancelled
        var captureCalls = 0
        var suppressActiveDuringCapture: Boolean? = null
        var photo = File.createTempFile("photo-", ".jpg")
        var photoUri: Uri = Uri.parse("content://com.openminis.app.fileprovider/camera-photos/photo-1.jpg")

        override fun ensurePermission(args: OffloadArgs): NativeOffloadResult? = permission

        override fun createOutput(): Pair<Uri, File> {
            photo.writeBytes(ByteArray(PHOTO_BYTES) { 0x42 })
            return photoUri to photo
        }

        override suspend fun capture(output: Pair<Uri, File>): CameraCaptureActivity.CameraCaptureResult {
            captureCalls++
            suppressActiveDuringCapture = SessionActivityTracker.cameraSuppressActive.value
            return captureResult
        }

        companion object {
            const val PHOTO_BYTES = 1234
        }
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        OffloadPermissionManager.init(context)
    }

    @After
    fun tearDown() {
        OffloadPermissionManager.resetAll()
        SessionActivityTracker.setCameraSuppressActive(false)
    }

    private fun run(system: CameraSystem, sessionId: String? = null, vararg argv: String): NativeOffloadResult =
        CameraOffloadHandler(context, system).handle(
            NativeOffloadRequest(
                pid = 1,
                argv = listOf("android-camera") + argv,
                env = emptyMap(),
                cwd = "/",
                sessionId = sessionId,
            ),
        )

    private fun permissionError(code: String) = NativeOffloadResult(
        77,
        OffloadOutput.formatBody(
            JSONObject().put("error", code).put("message", "test").toString(),
            OffloadArgs(emptyList()),
        ) + "\n",
    )

    private fun json(result: NativeOffloadResult) = JSONObject(result.output.trim())

    private fun assertSuppressCleared() =
        assertFalse(SessionActivityTracker.cameraSuppressActive.value)

    @Test
    fun `--help exits 0 with usage`() {
        val system = FakeCameraSystem()
        val result = run(system, "--help")
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("android-camera"))
        assertEquals(0, system.captureCalls)
    }

    @Test
    fun `-h exits 0 with usage`() {
        assertEquals(0, run(FakeCameraSystem(), "-h").exitCode)
    }

    @Test
    fun `an unknown flag exits 2 without capturing`() {
        val system = FakeCameraSystem()
        val result = run(system, "--nope")
        assertEquals(2, result.exitCode)
        assertEquals(0, system.captureCalls)
    }

    @Test
    fun `a positional argument exits 2 without capturing`() {
        val system = FakeCameraSystem()
        val result = run(system, "selfie")
        assertEquals(2, result.exitCode)
        assertEquals(0, system.captureCalls)
    }

    @Test
    fun `a denied camera permission exits 77 without capturing`() {
        val system = FakeCameraSystem().apply { permission = permissionError("camera_permission_denied") }
        val result = run(system)
        assertEquals(77, result.exitCode)
        assertEquals("camera_permission_denied", json(result).getString("error"))
        assertEquals(0, system.captureCalls)
    }

    @Test
    fun `a camera permission timeout exits 77 without capturing`() {
        val system = FakeCameraSystem().apply { permission = permissionError("camera_permission_timeout") }
        val result = run(system)
        assertEquals(77, result.exitCode)
        assertEquals("camera_permission_timeout", json(result).getString("error"))
        assertEquals(0, system.captureCalls)
    }

    @Test
    fun `a cancelled capture exits 1 and clears the suppress flag`() {
        val system = FakeCameraSystem()
        val result = run(system)
        assertEquals(1, result.exitCode)
        assertEquals("camera_cancelled", json(result).getString("error"))
        assertEquals(1, system.captureCalls)
        assertTrue(system.suppressActiveDuringCapture == true)
        assertSuppressCleared()
    }

    @Test
    fun `no camera app exits 1`() {
        val system = FakeCameraSystem().apply {
            captureResult = CameraCaptureActivity.CameraCaptureResult.NoCameraApp
        }
        val result = run(system)
        assertEquals(1, result.exitCode)
        assertEquals("camera_no_camera_app", json(result).getString("error"))
        assertSuppressCleared()
    }

    @Test
    fun `a capture timeout exits 1`() {
        val system = FakeCameraSystem().apply {
            captureResult = CameraCaptureActivity.CameraCaptureResult.Timeout
        }
        val result = run(system)
        assertEquals(1, result.exitCode)
        assertEquals("camera_timeout", json(result).getString("error"))
        assertSuppressCleared()
    }

    @Test
    fun `a photo is published to the caller session attachments`() {
        val system = FakeCameraSystem().apply {
            captureResult = CameraCaptureActivity.CameraCaptureResult.Success(photo, photoUri)
        }
        val result = run(system, sessionId = "test-session")
        assertEquals(0, result.exitCode)
        val body = json(result)
        assertEquals("/var/minis/attachments/${system.photo.name}", body.getString("path"))
        assertEquals(system.photo.absolutePath, body.getString("host_path"))
        assertEquals(system.photoUri.toString(), body.getString("uri"))
        assertEquals(FakeCameraSystem.PHOTO_BYTES.toLong(), body.getLong("bytes"))
        assertEquals("image/jpeg", body.getString("mime"))
        val copy = File(
            SessionMounts.sessionDir(context.filesDir, "test-session", "attachments"),
            system.photo.name,
        )
        assertTrue(copy.exists())
        assertEquals(FakeCameraSystem.PHOTO_BYTES.toLong(), copy.length())
        assertTrue(system.suppressActiveDuringCapture == true)
        assertSuppressCleared()
    }

    @Test
    fun `a photo without a session reports the host path`() {
        val system = FakeCameraSystem().apply {
            captureResult = CameraCaptureActivity.CameraCaptureResult.Success(photo, photoUri)
        }
        val result = run(system, sessionId = null)
        assertEquals(0, result.exitCode)
        val body = json(result)
        assertEquals(system.photo.absolutePath, body.getString("path"))
        assertEquals(FakeCameraSystem.PHOTO_BYTES.toLong(), body.getLong("bytes"))
        assertSuppressCleared()
    }
}
