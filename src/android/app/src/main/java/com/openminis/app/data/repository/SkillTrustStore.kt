package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger

/**
 * A15: remembers which non-bundled skills the user has explicitly approved
 * after seeing the source-risk warning. Bundled skills ship with the app and
 * need no approval; URL / file / session imports ask once, then run freely.
 */
object SkillTrustStore {

    private const val TAG = "SkillTrustStore"
    private const val PREFS_NAME = "skill_trust"
    private const val KEY_APPROVED = "approved_skill_ids"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun isApproved(skillId: String): Boolean =
        prefs?.getStringSet(KEY_APPROVED, emptySet())?.contains(skillId) == true

    fun approve(skillId: String) {
        val current = prefs?.getStringSet(KEY_APPROVED, emptySet()) ?: emptySet()
        prefs?.edit()?.putStringSet(KEY_APPROVED, current + skillId)?.apply()
        AppLogger.info(TAG, "skill approved: $skillId")
    }

    fun revoke(skillId: String) {
        val current = prefs?.getStringSet(KEY_APPROVED, emptySet()) ?: emptySet()
        prefs?.edit()?.putStringSet(KEY_APPROVED, current - skillId)?.apply()
        AppLogger.info(TAG, "skill approval revoked: $skillId")
    }
}
