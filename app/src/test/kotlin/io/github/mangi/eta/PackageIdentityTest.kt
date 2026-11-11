package io.github.mangi.eta

import android.content.pm.PackageManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionProtocol
import io.github.mangi.eta.core.ColorOsMemoryBridgeProtocol
import io.github.mangi.eta.core.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PackageIdentityTest {
    @Test
    fun forkApplicationIdentityIsSeparateFromSourceNamespace() {
        assertEquals("io.github.zhuzhuaini.eta", BuildConfig.APPLICATION_ID)
        assertNotEquals("io.github.mangi.eta", BuildConfig.APPLICATION_ID)
        assertEquals(BuildConfig.APPLICATION_ID, ModuleConfig.ETA_PACKAGE)
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.coloros_memory.query.v1",
            ColorOsMemoryBridgeProtocol.METHOD,
        )
        assertTrue(
            ColorOsMemoryBridgeProtocol.buildRootCommand("test")
                .contains("'${BuildConfig.APPLICATION_ID}.coloros_memory.query.v1'"),
        )

        val app = RuntimeEnvironment.getApplication() as Context
        assertEquals(BuildConfig.APPLICATION_ID, app.packageName)
    }

    @Test
    fun runtimeBindingResolvesWithinInstalledForkPackage() {
        val app = RuntimeEnvironment.getApplication() as Context
        val intent = AgentRuntimeWire.serviceIntent()
        val component = requireNotNull(intent.component)
        assertEquals(BuildConfig.APPLICATION_ID, component.packageName)
        assertEquals("io.github.mangi.eta.agent.runtime.AgentRuntimeService", component.className)
        val resolved = app.packageManager.resolveService(intent, PackageManager.MATCH_ALL)
        assertEquals(BuildConfig.APPLICATION_ID, resolved?.serviceInfo?.packageName)
        assertEquals(component.className, resolved?.serviceInfo?.name)
    }

    @Test
    fun accessibilityProtocolAndManifestIdentifiersUseForkIdentity() {
        assertTrue(AccessibilityProtectionProtocol.ACTION_SET.startsWith(BuildConfig.APPLICATION_ID))
        assertTrue(AccessibilityProtectionProtocol.SETTING_NAME.startsWith(BuildConfig.APPLICATION_ID))
        assertTrue(AccessibilityProtectionProtocol.ACTION_RECOVER.startsWith(BuildConfig.APPLICATION_ID))
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.permission.CONTROL_ACCESSIBILITY_PROTECTION",
            AccessibilityProtectionProtocol.PERMISSION,
        )
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.accessibility.health",
            AccessibilityProtectionProtocol.HEALTH_AUTHORITY,
        )

        val app = RuntimeEnvironment.getApplication() as Context
        val provider = app.packageManager.resolveContentProvider(
            AccessibilityProtectionProtocol.HEALTH_AUTHORITY,
            PackageManager.MATCH_ALL,
        )
        assertEquals(BuildConfig.APPLICATION_ID, provider?.packageName)
        assertEquals(AccessibilityProtectionProtocol.HEALTH_AUTHORITY, provider?.authority)
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.permission.CONTROL_ACCESSIBILITY_PROTECTION",
            app.packageManager.getPermissionInfo(
                "${BuildConfig.APPLICATION_ID}.permission.CONTROL_ACCESSIBILITY_PROTECTION",
                0,
            ).name,
        )
    }
}
