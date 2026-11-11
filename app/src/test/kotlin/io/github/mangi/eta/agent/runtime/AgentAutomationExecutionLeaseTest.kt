package io.github.mangi.eta.agent.runtime

import android.os.Looper
import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentAutomationExecutionLeaseTest {
    @Test fun cancellationReleasesCpuLockWithoutTurningOnPhysicalDisplay() {
        val context = RuntimeEnvironment.getApplication()
        val power = context.getSystemService(PowerManager::class.java)
        shadowOf(power).setIsInteractive(false)
        val controller = AgentRunController()
        val lease = AgentAutomationExecutionLease(context, controller)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        assertFalse(power.isInteractive)
        controller.cancel()
        assertFalse(lock.isHeld)
        lease.close()
        assertFalse(power.isInteractive)
    }

    @Test fun watchdogCancelsAndReleasesAtResourceCeiling() {
        val controller = AgentRunController()
        AgentAutomationExecutionLease(RuntimeEnvironment.getApplication(), controller).use {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AgentAutomationExecutionLease.MAX_DURATION_MS))
            assertTrue(controller.isCancelled)
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        }
    }

    @Test fun normalCompletionRemovesWatchdogAndReleaseIsIdempotent() {
        val controller = AgentRunController()
        val lease = AgentAutomationExecutionLease(RuntimeEnvironment.getApplication(), controller)
        lease.close()
        lease.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AgentAutomationExecutionLease.MAX_DURATION_MS))
        assertFalse(controller.isCancelled)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }
}
