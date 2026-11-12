package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject

/** Observes the actual invocation, not the shortened UI event. Recording must never change execution. */
internal class AgentAutomationDiagnosticToolExecutor(
    private val delegate: AgentModelClient.ToolExecutor,
    private val onCall: (AgentModelClient.ToolCall) -> Unit,
    private val onResult: (AgentModelClient.ToolCall, AgentModelClient.ToolResult) -> Unit,
    private val onFailure: (AgentModelClient.ToolCall, Throwable) -> Unit,
) : AgentModelClient.ToolExecutor {
    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        runCatching { onCall(toolCall) }
        return try {
            delegate.execute(toolCall).also { result -> runCatching { onResult(toolCall, result) } }
        } catch (error: Throwable) {
            runCatching { onFailure(toolCall, error) }
            throw error
        }
    }

    companion object {
        /** Never reads reference/source: either may contain a path, URL credential or encoded media. */
        fun imageMetadata(images: List<AgentModelClient.ModelImage>): JSONArray = JSONArray().apply {
            images.take(64).forEach { image ->
                put(JSONObject()
                    .put("mimeType", image.mimeType.take(128))
                    .put("byteLength", image.bytes.coerceAtLeast(0))
                    .put("width", image.width?.coerceIn(0, 100_000) ?: JSONObject.NULL)
                    .put("height", image.height?.coerceIn(0, 100_000) ?: JSONObject.NULL)
                    .put("omitted", true)
                    .put("reason", "binary_payload"))
            }
            if (images.size > 64) put(JSONObject()
                .put("omitted", true)
                .put("additionalImages", images.size - 64))
        }
    }
}
