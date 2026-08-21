package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException

/**
 * Stable policy for one model turn in the agent loop.
 *
 * The provider and individual tools own their transport-level timeouts. This
 * policy is the outer safety net: it prevents a turn that stopped producing a
 * terminal event from keeping the foreground job alive indefinitely.
 */
object AgentLifecyclePolicy {
    /** Maximum wall-clock time allowed for one provider turn. */
    const val MODEL_TURN_TIMEOUT_MS: Long = 10 * 60 * 1000L

    /**
     * A plain CancellationException is the normal user/job cancellation path.
     * A timeout is handled separately by the caller and must not be mistaken
     * for an explicit user stop.
     */
    fun isExplicitCancellation(error: Throwable): Boolean =
        error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException && error.cause == null

    fun timeoutMessage(timeoutMs: Long = MODEL_TURN_TIMEOUT_MS): String =
        "The model turn timed out after ${timeoutMs / 60_000} minutes. You can retry or switch models."
}

class AgentModelTurnTimeoutException(
    val timeoutMs: Long,
) : Exception(AgentLifecyclePolicy.timeoutMessage(timeoutMs))
