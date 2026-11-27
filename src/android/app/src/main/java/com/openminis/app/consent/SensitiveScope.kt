package com.openminis.app.consent

import androidx.annotation.StringRes
import com.openminis.app.R

/**
 * B13: the sensitive-action scopes the central consent layer ([ConsentManager])
 * guards. Each scope can be in "ask every time" mode (default) or carry an
 * always-allow grant the user can revoke from Settings > Consent & approvals.
 */
enum class SensitiveScope(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    SEND_SMS(R.string.consent_scope_send_sms, R.string.consent_scope_send_sms_desc),
    PLACE_CALL(R.string.consent_scope_place_call, R.string.consent_scope_place_call_desc),
    SEND_MESSAGE(R.string.consent_scope_send_message, R.string.consent_scope_send_message_desc),
    MAKE_PURCHASE(R.string.consent_scope_make_purchase, R.string.consent_scope_make_purchase_desc),
    POST_PUBLIC(R.string.consent_scope_post_public, R.string.consent_scope_post_public_desc),
    DELETE_DATA(R.string.consent_scope_delete_data, R.string.consent_scope_delete_data_desc),
    ACCESSIBILITY_ACTION(R.string.consent_scope_accessibility_action, R.string.consent_scope_accessibility_action_desc),
    GUARD_MONITOR(R.string.consent_scope_guard_monitor, R.string.consent_scope_guard_monitor_desc),
    FOCUS_MODE(R.string.consent_scope_focus_mode, R.string.consent_scope_focus_mode_desc),
    FINANCE_TRACK(R.string.consent_scope_finance_track, R.string.consent_scope_finance_track_desc);

    companion object {
        fun fromName(name: String?): SensitiveScope? = values().firstOrNull { it.name == name }
    }
}
