package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAutomationDiagnosticToolExecutorTest {
    private val call = AgentModelClient.ToolCall("call-1", "terminal", "{\"command\":\"pwd\"}", 2)

    @Test
    fun recordsActualCallAndSensitiveResultWithoutChangingThem() {
        val expected = AgentModelClient.ToolResult("actual private text", sensitive = true, command = "pwd")
        val sequence = mutableListOf<String>()
        val executor = AgentAutomationDiagnosticToolExecutor(
            delegate = { received -> assertSame(call, received); sequence += "execute"; expected },
            onCall = { received -> assertSame(call, received); sequence += "call" },
            onResult = { received, result ->
                assertSame(call, received)
                assertSame(expected, result)
                assertEquals("actual private text", result.content)
                assertTrue(result.sensitive)
                sequence += "result"
            },
            onFailure = { _, _ -> throw AssertionError("Unexpected failure") },
        )
        assertSame(expected, executor.execute(call))
        assertEquals(listOf("call", "execute", "result"), sequence)
    }

    @Test
    fun recorderFailuresDoNotRetryOrFailSuccessfulTool() {
        val expected = AgentModelClient.ToolResult("ok")
        var executions = 0
        var failures = 0
        val executor = AgentAutomationDiagnosticToolExecutor(
            delegate = { executions++; expected },
            onCall = { error("broken recorder") },
            onResult = { _, _ -> error("broken recorder") },
            onFailure = { _, _ -> failures++ },
        )
        assertSame(expected, executor.execute(call))
        assertEquals(1, executions)
        assertEquals(0, failures)
    }

    @Test
    fun toolFailureIsRethrownUnchangedEvenIfFailureRecorderThrows() {
        val expected = java.util.concurrent.CancellationException("cancelled")
        var observed: Throwable? = null
        var executions = 0
        val executor = AgentAutomationDiagnosticToolExecutor(
            delegate = { executions++; throw expected },
            onCall = {},
            onResult = { _, _ -> throw AssertionError("No result expected") },
            onFailure = { received, error ->
                assertSame(call, received)
                observed = error
                throw IllegalStateException("broken recorder")
            },
        )
        val thrown = runCatching { executor.execute(call) }.exceptionOrNull()
        assertSame(expected, thrown)
        assertSame(expected, observed)
        assertEquals(1, executions)
    }

    @Test
    fun imageMetadataIsBoundedAndNeverIncludesReferenceOrSource() {
        val image = AgentModelClient.ModelImage(
            reference = "data:image/png;base64,PRIVATE_REFERENCE",
            mimeType = "image/png",
            bytes = 500,
            width = 32,
            height = 16,
            source = "PRIVATE_SOURCE",
        )
        val result = AgentAutomationDiagnosticToolExecutor.imageMetadata(List(100) { image })
        assertEquals(65, result.length())
        assertEquals(36, result.getJSONObject(64).getInt("additionalImages"))
        val first = result.getJSONObject(0)
        assertEquals(500, first.getInt("byteLength"))
        assertEquals(32, first.getInt("width"))
        assertEquals(16, first.getInt("height"))
        assertTrue(first.getBoolean("omitted"))
        assertFalse(result.toString().contains("PRIVATE_REFERENCE"))
        assertFalse(result.toString().contains("PRIVATE_SOURCE"))
        assertFalse(first.has("reference"))
        assertFalse(first.has("source"))
    }
}
