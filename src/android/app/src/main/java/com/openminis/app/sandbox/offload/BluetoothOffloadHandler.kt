package com.openminis.app.sandbox.offload

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * android-bluetooth — adapter state and paired-device list.
 *
 *   android-bluetooth status   Adapter present/enabled (+ JSON)
 *   android-bluetooth paired   Bonded devices: name, address, type
 *
 * Needs BLUETOOTH_CONNECT on Android 12+; the handler runs the same
 * system-dialog → settings-gate flow as contacts when it is missing.
 */
class BluetoothOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        OffloadGate.enforce("bluetooth", "android-bluetooth", args, request)?.let { return it }
        ensurePermission(args)?.let { return it }

        return try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return NativeOffloadResult(1, err("unavailable", "Bluetooth service not found on this device."))
            val adapter = manager.adapter
                ?: return NativeOffloadResult(1, err("unavailable", "This device has no Bluetooth adapter."))
            when (val sub = args.positional.firstOrNull() ?: "status") {
                "status" -> NativeOffloadResult(0, JSONObject()
                    .put("adapter_present", true)
                    .put("enabled", adapter.isEnabled)
                    .toString() + "\n")
                "paired" -> {
                    if (!adapter.isEnabled) {
                        return NativeOffloadResult(1, err("adapter_off", "Bluetooth is off. Ask the user to enable it, then retry."))
                    }
                    val devices = JSONArray()
                    for (d in adapter.bondedDevices) {
                        devices.put(JSONObject()
                            .put("name", d.name ?: JSONObject.NULL)
                            .put("address", d.address)
                            .put("type", d.type)
                            .put("bond_state", d.bondState))
                    }
                    NativeOffloadResult(0, JSONObject().put("devices", devices).toString() + "\n")
                }
                else -> NativeOffloadResult(2, "android-bluetooth: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: SecurityException) {
            NativeOffloadResult(77, err("permission_denied", "BLUETOOTH_CONNECT revoked mid-call: ${e.message}"))
        } catch (e: Throwable) {
            NativeOffloadResult(1, err("internal", e.message ?: "unknown"))
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun ensurePermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasPermission()) return null
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(listOf(Manifest.permission.BLUETOOTH_CONNECT))
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasPermission() })
            ) {
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = Manifest.permission.BLUETOOTH_CONNECT,
                        title = "Bluetooth permission needed",
                        message = "Minis needs the Nearby devices (Bluetooth) permission to list paired devices. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { hasPermission() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED,
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT,
            -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject()
                        .put("error", "permission_denied")
                        .put("message", "Bluetooth (Nearby devices) permission not granted. Ask the user to allow it in Settings, then retry.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun err(code: String, message: String): String =
        JSONObject().put("error", code).put("message", message).toString() + "\n"

    companion object {
        private const val HELP = """android-bluetooth — adapter state and paired devices (JSON)

Usage:
  android-bluetooth status    Adapter present + enabled
  android-bluetooth paired    Bonded devices (name, address, type, bond state)

Needs the Nearby devices permission on Android 12+; the first call asks.
"""
    }
}
