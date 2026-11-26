package com.openminis.app.consent

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * B13: central consent layer for sensitive agent actions.
 *
 * Before any sensitive action (sending SMS/messages, purchases, posting,
 * deleting, accessibility taps in other apps) the agent calls
 * [requireConsent] with exactly what it is about to do. The user sees a
 * root-level dialog ([com.openminis.app.ui.settings.ConsentDialogHost]) and
 * can allow once, always allow the scope, or deny; requests time out after
 * 120 s and are then treated as denied-by-absence.
 *
 * Always-allow grants persist in SharedPreferences and are revocable from
 * Settings > Consent & approvals ([com.openminis.app.ui.settings.ConsentSettingsScreen]).
 *
 * Queuing and UI binding mirror the minis-config confirmation flow
 * ([com.openminis.app.config.confirm.ConfigConfirmationGate]): one request is
 * front-of-queue at a time, the rest wait their turn.
 */
object ConsentManager {

    private const val TAG = "ConsentManager"
    private const val PREFS_NAME = "consent_grants"
    private const val KEY_ALWAYS_ALLOW = "always_allow_scopes"
    private const val TIMEOUT_MS = 120_000L

    data class ConsentRequest(
        val id: String,
        val scope: SensitiveScope,
        /** Pre-localized one-line statement of exactly what will happen. */
        val title: String,
        /** Pre-localized detail lines (targets, content, amounts). */
        val details: List<String>,
        val createdAtMs: Long,
    )

    sealed interface ConsentOutcome {
        data object Approved : ConsentOutcome
        data object ApprovedAlways : ConsentOutcome
        data object Denied : ConsentOutcome
        data object TimedOut : ConsentOutcome
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Called once from MinisApp.onCreate. Safe to call again (no-op). */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _grantedScopes.value = loadGrants()
    }

    // ---------------------------------------------------------------- grants

    private val _grantedScopes = MutableStateFlow<Set<SensitiveScope>>(emptySet())
    val grantedScopes: StateFlow<Set<SensitiveScope>> = _grantedScopes.asStateFlow()

    fun isAlwaysAllowed(scope: SensitiveScope): Boolean = _grantedScopes.value.contains(scope)

    fun grantAlways(scope: SensitiveScope) = updateGrants(_grantedScopes.value + scope)

    fun revoke(scope: SensitiveScope) = updateGrants(_grantedScopes.value - scope)

    private fun loadGrants(): Set<SensitiveScope> {
        val stored = prefs?.getStringSet(KEY_ALWAYS_ALLOW, emptySet()) ?: emptySet()
        return stored.mapNotNull { SensitiveScope.fromName(it) }.toSet()
    }

    private fun updateGrants(newValue: Set<SensitiveScope>) {
        _grantedScopes.value = newValue
        prefs?.edit()?.putStringSet(KEY_ALWAYS_ALLOW, newValue.map { it.name }.toSet())?.apply()
        AppLogger.info(TAG, "always-allow scopes now: ${newValue.joinToString { it.name }}")
    }

    // -------------------------------------------------------------- requests

    private val _pending = MutableStateFlow<ConsentRequest?>(null)
    val pending: StateFlow<ConsentRequest?> = _pending.asStateFlow()

    private val queue = ArrayDeque<QueuedRequest>()
    private val awaiters = HashMap<String, Continuation<ConsentOutcome>>()
    private val timeouts = HashMap<String, Job>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class QueuedRequest(
        val request: ConsentRequest,
        val cont: Continuation<ConsentOutcome>,
    )

    /**
     * Suspend until the user resolves the request or the timeout fires.
     * Returns immediately with [ConsentOutcome.Approved] when the scope
     * carries an always-allow grant.
     */
    suspend fun requireConsent(
        actionScope: SensitiveScope,
        title: String,
        details: List<String> = emptyList(),
    ): ConsentOutcome {
        if (isAlwaysAllowed(actionScope)) {
            AppLogger.info(TAG, "auto-approved ${actionScope.name}: $title")
            return ConsentOutcome.Approved
        }
        val request = ConsentRequest(
            id = UUID.randomUUID().toString(),
            scope = actionScope,
            title = title,
            details = details,
            createdAtMs = System.currentTimeMillis(),
        )
        AppLogger.info(TAG, "awaiting consent ${actionScope.name}: $title")
        return suspendCoroutine { cont ->
            scope.launch {
                mutex.withLock {
                    awaiters[request.id] = cont
                    timeouts[request.id] = scope.launch {
                        delay(TIMEOUT_MS)
                        timeout(request.id)
                    }
                    if (_pending.value == null) {
                        _pending.value = request
                    } else {
                        queue.addLast(QueuedRequest(request, cont))
                    }
                }
            }
        }
    }

    /** UI callback: user tapped "Allow once". */
    fun userApproveOnce() = resolvePending(ConsentOutcome.Approved)

    /** UI callback: user tapped "Always allow". */
    fun userApproveAlways() {
        val current = _pending.value ?: return
        grantAlways(current.scope)
        resolvePending(ConsentOutcome.ApprovedAlways)
    }

    /** UI callback: user tapped "Deny". */
    fun userDeny() = resolvePending(ConsentOutcome.Denied)

    private fun resolvePending(outcome: ConsentOutcome) {
        val current = _pending.value ?: return
        scope.launch { resolve(current.id, outcome) }
    }

    private suspend fun timeout(id: String) {
        if (awaiters[id] == null) return
        AppLogger.info(TAG, "consent timeout for request $id")
        resolve(id, ConsentOutcome.TimedOut)
    }

    private suspend fun resolve(id: String, outcome: ConsentOutcome) {
        val cont: Continuation<ConsentOutcome>?
        mutex.withLock {
            cont = awaiters.remove(id)
            timeouts.remove(id)?.cancel()
            if (_pending.value?.id == id) {
                val next = if (queue.isEmpty()) null else queue.removeFirst()
                _pending.value = next?.request
            } else {
                queue.removeAll { it.request.id == id }
            }
        }
        cont?.resume(outcome)
    }
}
