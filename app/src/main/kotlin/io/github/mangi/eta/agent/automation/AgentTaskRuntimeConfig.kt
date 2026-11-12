package io.github.mangi.eta.agent.automation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy

/** Model repository values are not entry tool permissions. Runtime still rechecks authorization. */
internal object AgentTaskRuntimeConfig {
    fun withToolPermissions(
        model: AgentModelClient.ModelConfig,
        permissions: AgentRuntimePolicy.Permissions,
    ): AgentModelClient.ModelConfig = model.copy(
        terminalTools = permissions.terminalTools,
        browserTools = permissions.browserTools,
        deviceDirectTools = permissions.deviceDirectTools,
        deviceSensitiveReadTools = permissions.deviceSensitiveReadTools,
        deviceSensitiveActionTools = permissions.deviceSensitiveActionTools,
    )
}
