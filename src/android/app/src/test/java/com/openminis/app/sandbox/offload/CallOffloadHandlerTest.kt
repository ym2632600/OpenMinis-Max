package com.openminis.app.sandbox.offload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.consent.ConsentManager
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for [CallOffloadHandler]: argument parsing and the
 * error paths (gate, permission, consent, contact lookup, dialer).
 * The Android-only seams (permission, consent, contacts, intents) are
 * faked through [CallSystem]; the handler's own decisions are what's
 * under test. Mirrors [CameraOffloadHandlerTest].
 */
@RunWith(RobolectricTestRunner::class)
class CallOffloadHandlerTest {

    private lateinit var context: Context
    private lateinit var system: FakeCallSystem
    private lateinit var handler: CallOffloadHandler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OffloadPermissionManager.init(context)
        // The "call" tool is ASK_ONCE in the registry; the gate would
        // otherwise wait on a permission dialog that never appears
        // under Robolectric. Bypass it here; the gate-denied path has
        // its own test below.
        OffloadPermissionManager.setLevel("call", OffloadPermissionManager.PermissionLevel.BYPASS)
        system = FakeCallSystem()
        handler = CallOffloadHandler(context, system)
    }

    @After
    fun tearDown() {
        OffloadPermissionManager.resetAll()
    }

    @Test
    fun helpText() {
        val result = handler.handle(request("--help"))
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("android-call call"))
        assertTrue(result.output.contains("android-call dial"))
    }

    @Test
    fun noArgsReturnsHelp() {
        val result = handler.handle(request())
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("android-call call"))
    }

    @Test
    fun unknownSubcommandReturnsUsage() {
        val result = handler.handle(request("ring", "+201234567890"))
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("usage"))
    }

    @Test
    fun missingTargetReturnsUsage() {
        assertEquals(2, handler.handle(request("call")).exitCode)
        assertEquals(2, handler.handle(request("dial")).exitCode)
    }

    @Test
    fun unknownFlagReturnsUsage() {
        val result = handler.handle(request("call", "--nope", "+201234567890"))
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("usage"))
    }

    @Test
    fun gateDeniedReturnsPermissionDenied() {
        OffloadPermissionManager.setLevel("call", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
        val result = handler.handle(request("call", "+201234567890"))
        assertEquals(77, result.exitCode)
        assertEquals("permission_denied", errorOf(result))
    }

    @Test
    fun callPermissionDeniedReturns77() {
        system.permissionResult = NativeOffloadResult(77, "{\"error\":\"call_permission_denied\"}\n")
        val result = handler.handle(request("call", "+201234567890"))
        assertEquals(77, result.exitCode)
        assertEquals("call_permission_denied", errorOf(result))
        assertNull(system.placedNumber)
    }

    @Test
    fun consentDeniedReturnsConsentDenied() {
        system.consentOutcome = ConsentManager.ConsentOutcome.Denied
        val result = handler.handle(request("call", "+201234567890"))
        assertEquals(1, result.exitCode)
        assertEquals("consent_denied", errorOf(result))
        assertNull(system.placedNumber)
    }

    @Test
    fun consentTimeoutReturnsConsentTimeout() {
        system.consentOutcome = ConsentManager.ConsentOutcome.TimedOut
        val result = handler.handle(request("call", "+201234567890"))
        assertEquals(1, result.exitCode)
        assertEquals("consent_timeout", errorOf(result))
        assertNull(system.placedNumber)
    }

    @Test
    fun callSuccessReturnsCalledJson() {
        val result = handler.handle(request("call", "+201234567890"))
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output)
        assertEquals("called", json.getString("status"))
        assertEquals("+201234567890", json.getString("number"))
        assertEquals("+201234567890", system.placedNumber)
        assertTrue(system.permissionRequested)
        assertTrue(system.consentRequested)
    }

    @Test
    fun callNormalizesNumber() {
        val result = handler.handle(request("call", "+20 123-456-7890"))
        assertEquals(0, result.exitCode)
        assertEquals("+201234567890", system.placedNumber)
    }

    @Test
    fun callByContactNameResolvesNumber() {
        system.lookupResult = ContactLookup.Resolved("+201111111111", "Mona")
        val result = handler.handle(request("call", "Mona"))
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output)
        assertEquals("called", json.getString("status"))
        assertEquals("+201111111111", json.getString("number"))
        assertEquals("Mona", json.getString("name"))
        assertEquals("+201111111111", system.placedNumber)
    }

    @Test
    fun contactNotFoundReturnsError() {
        system.lookupResult = ContactLookup.NotFound
        val result = handler.handle(request("call", "Nobody Here"))
        assertEquals(1, result.exitCode)
        assertEquals("contact_not_found", errorOf(result))
        assertNull(system.placedNumber)
    }

    @Test
    fun ambiguousContactReturnsCandidates() {
        system.lookupResult = ContactLookup.Ambiguous(
            listOf("Mona A (+201111111111)", "Mona B (+202222222222)"),
        )
        val result = handler.handle(request("call", "Mona"))
        assertEquals(1, result.exitCode)
        assertEquals("contact_ambiguous", errorOf(result))
        assertEquals(2, JSONObject(result.output).getJSONArray("candidates").length())
        assertNull(system.placedNumber)
    }

    @Test
    fun contactPermissionDeniedReturns77() {
        system.lookupResult = ContactLookup.PermissionDenied
        val result = handler.handle(request("call", "Mona"))
        assertEquals(77, result.exitCode)
        assertEquals("contact_permission_denied", errorOf(result))
        assertNull(system.placedNumber)
    }

    @Test
    fun dialSuccessReturnsDialedJson() {
        val result = handler.handle(request("dial", "+201234567890"))
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output)
        assertEquals("dialed", json.getString("status"))
        assertEquals("+201234567890", json.getString("number"))
        assertEquals("+201234567890", system.dialedNumber)
        assertNull(system.placedNumber)
    }

    @Test
    fun dialDoesNotAskPermissionOrConsent() {
        system.consentOutcome = ConsentManager.ConsentOutcome.Denied
        val result = handler.handle(request("dial", "+201234567890"))
        assertEquals(0, result.exitCode)
        assertFalse(system.permissionRequested)
        assertFalse(system.consentRequested)
    }

    @Test
    fun dialWithoutDialerReturnsNoDialer() {
        system.launchResult = CallLaunch.NoHandler
        val result = handler.handle(request("dial", "+201234567890"))
        assertEquals(1, result.exitCode)
        assertEquals("no_dialer", errorOf(result))
    }

    @Test
    fun callWithoutPhoneAppReturnsNoDialer() {
        system.launchResult = CallLaunch.NoHandler
        val result = handler.handle(request("call", "+201234567890"))
        assertEquals(1, result.exitCode)
        assertEquals("no_dialer", errorOf(result))
    }

    @Test
    fun securityExceptionReturns77() {
        system.throwOnPlace = SecurityException("boom")
        val result = handler.handle(request("call", "+201234567890"))
        assertEquals(77, result.exitCode)
        assertEquals("call_permission_refused", errorOf(result))
    }

    @Test
    fun uncaughtExceptionReturnsCallError() {
        system.throwOnPlace = RuntimeException("boom")
        val result = handler.handle(request("call", "+201234567890"))
        assertEquals(1, result.exitCode)
        assertEquals("call_error", errorOf(result))
    }

    private fun request(vararg args: String): NativeOffloadRequest =
        NativeOffloadRequest(
            pid = 1,
            argv = listOf("android-call") + args,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )

    private fun errorOf(result: NativeOffloadResult): String =
        JSONObject(result.output).getString("error")

    private class FakeCallSystem : CallSystem {
        var permissionResult: NativeOffloadResult? = null
        var permissionRequested = false
        var consentOutcome: ConsentManager.ConsentOutcome = ConsentManager.ConsentOutcome.Approved
        var consentRequested = false
        var lookupResult: ContactLookup = ContactLookup.NotFound
        var launchResult: CallLaunch = CallLaunch.Launched
        var placedNumber: String? = null
        var dialedNumber: String? = null
        var throwOnPlace: Throwable? = null

        override fun ensureCallPermission(args: OffloadArgs): NativeOffloadResult? {
            permissionRequested = true
            return permissionResult
        }

        override fun requestConsent(number: String): ConsentManager.ConsentOutcome {
            consentRequested = true
            return consentOutcome
        }

        override fun resolveContact(name: String): ContactLookup = lookupResult

        override fun placeCall(number: String): CallLaunch {
            throwOnPlace?.let { throw it }
            placedNumber = number
            return launchResult
        }

        override fun dial(number: String): CallLaunch {
            dialedNumber = number
            return launchResult
        }
    }
}
