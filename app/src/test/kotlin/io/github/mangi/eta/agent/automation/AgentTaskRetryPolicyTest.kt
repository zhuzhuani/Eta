package io.github.mangi.eta.agent.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTaskRetryPolicyTest {
    @Test
    fun transientNetworkErrorsRetryOnlyBeforeAnyToolStarts() {
        val retry = AgentTaskRetryPolicy.classify("HTTP 503", "provider unavailable", touched = false)
        assertEquals(AgentTaskRetryPolicy.Decision.RETRY, retry.decision)
        assertEquals(30_000L, AgentTaskRetryPolicy.backoffFor(1))
        assertEquals(120_000L, AgentTaskRetryPolicy.backoffFor(2))

        val afterToolStarted = AgentTaskRetryPolicy.classify("HTTP 503", "", touched = true)
        assertEquals(AgentTaskRetryPolicy.Decision.GIVE_UP, afterToolStarted.decision)
    }

    @Test
    fun permissionContextAndUnknownErrorsFailClosed() {
        assertEquals(
            AgentTaskRetryPolicy.Decision.GIVE_UP,
            AgentTaskRetryPolicy.classify("permission denied", "", touched = false).decision,
        )
        assertEquals(
            AgentTaskRetryPolicy.Decision.GIVE_UP,
            AgentTaskRetryPolicy.classify("maximum context length", "", touched = false).decision,
        )
        assertEquals(
            AgentTaskRetryPolicy.Decision.GIVE_UP,
            AgentTaskRetryPolicy.classify("unknown failure", "", touched = false).decision,
        )
    }
}
