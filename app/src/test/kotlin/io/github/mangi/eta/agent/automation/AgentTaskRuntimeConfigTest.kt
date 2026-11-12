package io.github.mangi.eta.agent.automation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskRuntimeConfigTest {
    private val model = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test",
        systemPrompt = "saved prompt",
    )

    private fun permissions(enabled: Boolean) = AgentRuntimePolicy.Permissions(
        terminalTools = enabled, browserTools = enabled, deviceDirectTools = enabled,
        deviceSensitiveReadTools = enabled, deviceSensitiveActionTools = enabled,
        thinking = false,
    )

    @Test fun enabledToolsAreNotLostToModelDefaults() {
        assertFalse(model.terminalTools)
        val actual = AgentTaskRuntimeConfig.withToolPermissions(model, permissions(true))
        assertTrue(actual.terminalTools)
        assertTrue(actual.browserTools)
        assertTrue(actual.deviceDirectTools)
        assertTrue(actual.deviceSensitiveReadTools)
        assertTrue(actual.deviceSensitiveActionTools)
        assertEquals(model, actual.copy(
            terminalTools = model.terminalTools, browserTools = model.browserTools,
            deviceDirectTools = model.deviceDirectTools,
            deviceSensitiveReadTools = model.deviceSensitiveReadTools,
            deviceSensitiveActionTools = model.deviceSensitiveActionTools,
        ))
    }

    @Test fun disabledOrUnavailablePermissionsNeverEnableTools() {
        for (permissions in listOf(permissions(false), AgentRuntimePolicy.permissions(null))) {
            val actual = AgentTaskRuntimeConfig.withToolPermissions(model, permissions)
            assertFalse(actual.terminalTools)
            assertFalse(actual.browserTools)
            assertFalse(actual.deviceDirectTools)
            assertFalse(actual.deviceSensitiveReadTools)
            assertFalse(actual.deviceSensitiveActionTools)
        }
    }

    @Test fun runtimeCanStillRevokeToolsAfterRequestCreation() {
        val request = AgentTaskRuntimeConfig.withToolPermissions(model, permissions(true))
        val actual = AgentRuntimePolicy.constrain(request, permissions(false))
        assertFalse(actual.terminalTools)
        assertFalse(actual.browserTools)
        assertFalse(actual.deviceDirectTools)
        assertFalse(actual.deviceSensitiveReadTools)
        assertFalse(actual.deviceSensitiveActionTools)
    }
}
