package com.openminis.app.ui.errors

import com.openminis.app.R

/**
 * [A7] One error, phrased for a person: a short title, what they can do
 * about it, and whether retrying is likely to help. The raw technical
 * message stays available behind "Details" in the UI.
 */
data class UserFacingError(
    val titleRes: Int,
    val adviceRes: Int,
    val retryable: Boolean,
    val technical: String,
)

/**
 * Classifies the raw error strings the chat persists on a failed assistant
 * turn (LLMError.message shapes) into a [UserFacingError]. Unknown shapes
 * fall through to the generic entry rather than guessing.
 */
object UserFacingErrors {

    fun classify(raw: String): UserFacingError {
        val t = raw.trim()
        val head = t.take(160)
        val server5xx = Regex("""(\[5\d{2}\]|HTTP 5\d{2})""").containsMatchIn(head)
        return when {
            t.startsWith("Invalid API key") ->
                UserFacingError(R.string.err_title_api_key, R.string.err_advice_api_key, retryable = false, technical = raw)
            t.startsWith("Network error") ->
                UserFacingError(R.string.err_title_network, R.string.err_advice_network, retryable = true, technical = raw)
            t.startsWith("Rate limited") ->
                UserFacingError(R.string.err_title_ratelimit, R.string.err_advice_ratelimit, retryable = true, technical = raw)
            t.startsWith("Transient error") || (t.startsWith("Provider error") && server5xx) ->
                UserFacingError(R.string.err_title_provider_down, R.string.err_advice_provider_down, retryable = true, technical = raw)
            t.startsWith("Provider error") ->
                UserFacingError(R.string.err_title_provider_rejected, R.string.err_advice_provider_rejected, retryable = true, technical = raw)
            t.startsWith("Couldn't verify the security certificate") ||
                t.startsWith("The certificate of") ||
                head.contains("answered with a certificate") ->
                UserFacingError(R.string.err_title_tls, R.string.err_advice_tls, retryable = false, technical = raw)
            t.startsWith("Decoding error") ->
                UserFacingError(R.string.err_title_decoding, R.string.err_advice_decoding, retryable = true, technical = raw)
            else ->
                UserFacingError(R.string.err_title_generic, R.string.err_advice_generic, retryable = true, technical = raw)
        }
    }
}
