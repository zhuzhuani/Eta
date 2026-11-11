package io.github.mangi.eta.agent.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDisplayLeaseTest {
    @Test
    fun closeIsIdempotentAndOnlyReleasesItsOwnedGeneration() {
        var generation = 1
        var releasedGeneration = 0
        val lease = AutomationDisplayLease(
            displayId = 8,
            isCurrent = { generation == 1 },
            release = { if (generation == 1) releasedGeneration = generation },
        )
        assertTrue(lease.isValid())
        generation = 2 // A replacement session owns the display now.
        assertFalse(lease.isValid())
        lease.close()
        lease.close()
        assertEquals(0, releasedGeneration)
    }

    @Test
    fun closeInvalidatesLeaseBeforeReleasing() {
        var current = true
        val lease = AutomationDisplayLease(9, { current }) { current = false }
        assertTrue(lease.isValid())
        lease.close()
        assertFalse(lease.isValid())
    }
}
