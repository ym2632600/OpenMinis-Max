package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLifecyclePolicyTest {
    @Test
    fun `plain cancellation is explicit cancellation`() {
        assertTrue(AgentLifecyclePolicy.isExplicitCancellation(CancellationException()))
    }

    @Test
    fun `timeout cancellation is not explicit cancellation`() = runBlocking {
        try {
            withTimeout(1) { delay(100) }
            throw AssertionError("withTimeout should have cancelled the block")
        } catch (error: TimeoutCancellationException) {
            assertFalse(AgentLifecyclePolicy.isExplicitCancellation(error))
        }
    }

    @Test
    fun `wrapped cancellation is not treated as user stop`() {
        assertFalse(
            AgentLifecyclePolicy.isExplicitCancellation(
                CancellationException("provider cancellation", IllegalStateException("transport")),
            ),
        )
    }

    @Test
    fun `timeout message reports configured duration`() {
        assertTrue(AgentLifecyclePolicy.timeoutMessage(120_000).contains("2 minutes"))
    }
}
