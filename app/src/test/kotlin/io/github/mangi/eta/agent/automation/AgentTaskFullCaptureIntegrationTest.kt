package io.github.mangi.eta.agent.automation

import android.content.Context
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTaskFullCaptureIntegrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val registeredRunIds = mutableListOf<String>()

    @Before
    fun setUp() = runBlocking {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        AgentTaskDiagnostics.clearForTests(context)
        resetFullCaptureStore()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        registeredRunIds.forEach(AgentTaskFullCapture::unregisterRun)
        registeredRunIds.clear()
        AgentTaskDiagnostics.clearForTests(context)
        resetFullCaptureStore()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun fullCaptureDefaultsOnButSafeZipNeverContainsItsFullBody() = runBlocking {
        val taskId = unique("safe-export-task")
        val runId = unique("safe-export-run")
        EtaDatabase.get(context).agentTaskDao().insertTask(task(taskId, "DATABASE_PROMPT_SHOULD_NOT_EXPORT"))
        assertTrue(isEnabled(taskId))
        register(taskId, runId)
        AgentTaskFullCapture.recordRunContext(
            context = context,
            taskId = taskId,
            runId = runId,
            taskName = "FULL_TASK_NAME_MARKER",
            taskPrompt = "FULL_TASK_PROMPT_MARKER",
            runtimePrompt = "FULL_RUNTIME_PROMPT_MARKER",
            model = "test-model",
            modelParameters = JSONObject().put("temperature", 0.2),
        )

        val safeArchive = export(taskId, includeSensitive = false)
        val safeContents = safeArchive.values.joinToString("\n")
        assertFalse(safeArchive.containsKey("full_diagnostics.json"))
        listOf(
            "DATABASE_PROMPT_SHOULD_NOT_EXPORT",
            "FULL_TASK_NAME_MARKER",
            "FULL_TASK_PROMPT_MARKER",
            "FULL_RUNTIME_PROMPT_MARKER",
        ).forEach { assertFalse("safe export leaked $it", safeContents.contains(it)) }
        assertNull(AgentTaskFullCapture.snapshotForExport(context, taskId, includeSensitive = false))

        val fullArchive = export(taskId, includeSensitive = true)
        assertTrue(fullArchive.containsKey("full_diagnostics.json"))
        val fullJson = fullArchive.getValue("full_diagnostics.json")
        assertTrue(fullJson.contains("FULL_TASK_PROMPT_MARKER"))
        assertTrue(fullJson.contains("FULL_RUNTIME_PROMPT_MARKER"))
    }

    @Test
    fun fullExportPreservesUsefulTextAndNestedArgumentsButFiltersKnownSecrets() = runBlocking {
        val taskId = unique("full-content-task")
        val runId = unique("full-content-run")
        EtaDatabase.get(context).agentTaskDao().insertTask(task(taskId))
        assertTrue(isEnabled(taskId))
        register(taskId, runId)

        val nestedArgumentJson = """{ "Api Key" : "NESTED_API_SECRET with spaces", "Auth Header" : "Bearer NESTED_BEARER_SECRET", "note" : "keep nested spaces and escaped \"quotes\"" }"""
        val argumentsJson = JSONObject()
            .put("query", "VISIBLE_ARGUMENT text with spaces")
            .put("metadata", nestedArgumentJson)
            .toString()
        val resultJson = JSONObject()
            .put("status", "ok")
            .put("message", "VISIBLE_TOOL_RESULT text with spaces")
            .put("password", "TOOL_PASSWORD_SECRET")
            .toString()

        AgentTaskFullCapture.recordRunContext(
            context = context,
            taskId = taskId,
            runId = runId,
            taskName = "VISIBLE_TASK_NAME",
            taskPrompt = "VISIBLE_PROMPT with spaces and api_key=RUN_PROMPT_SECRET",
            runtimePrompt = "VISIBLE_RUNTIME_PROMPT",
            model = "visible-model-name",
            modelParameters = JSONObject()
                .put("temperature", 0.7)
                .put("apiKey", "MODEL_API_SECRET"),
        )
        AgentTaskFullCapture.recordToolCallForRun(
            context = context,
            runId = runId,
            callId = "call-1",
            toolName = "tasks_list",
            round = 1,
            argumentsJson = argumentsJson,
        )
        AgentTaskFullCapture.recordToolResultForRun(
            context = context,
            runId = runId,
            callId = "call-1",
            toolName = "tasks_list",
            result = resultJson,
            sensitive = false,
            command = null,
            images = JSONArray(),
        )
        AgentTaskFullCapture.recordRunResponseForRun(
            context,
            runId,
            "VISIBLE_FINAL_RESPONSE with spaces; access_token=FINAL_RESPONSE_SECRET",
        )

        val archive = export(taskId, includeSensitive = true)
        val fullJson = requireNotNull(archive["full_diagnostics.json"])
        val snapshot = JSONObject(fullJson)
        val events = snapshot.getJSONArray("events")
        val runContext = eventOfType(events, "run_context")
        val toolCall = eventOfType(events, "tool_call")
        val toolResult = eventOfType(events, "tool_result")
        val completed = eventOfType(events, "run_completed")

        assertTrue(runContext.getString("taskPrompt").contains("VISIBLE_PROMPT"))
        assertTrue(runContext.getString("taskPrompt").contains("with spaces"))
        assertEquals("[REDACTED]", runContext.getJSONObject("modelParameters").getString("apiKey"))
        assertEquals("VISIBLE_ARGUMENT text with spaces", toolCall.getJSONObject("arguments").getString("query"))
        val metadata = toolCall.getJSONObject("arguments").getJSONObject("metadata")
        assertEquals("[REDACTED]", metadata.getString("Api Key"))
        assertEquals("[REDACTED]", metadata.getString("Auth Header"))
        assertEquals("keep nested spaces and escaped \"quotes\"", metadata.getString("note"))
        assertEquals("VISIBLE_TOOL_RESULT text with spaces", toolResult.getJSONObject("result").getString("message"))
        assertEquals("[REDACTED]", toolResult.getJSONObject("result").getString("password"))
        assertTrue(completed.getString("response").contains("VISIBLE_FINAL_RESPONSE"))

        listOf(
            "NESTED_API_SECRET",
            "NESTED_BEARER_SECRET",
            "RUN_PROMPT_SECRET",
            "MODEL_API_SECRET",
            "TOOL_PASSWORD_SECRET",
            "FINAL_RESPONSE_SECRET",
        ).forEach { secret -> assertFalse("full export leaked $secret", fullJson.contains(secret)) }
    }

    @Test
    fun disablingAndReenablingDoesNotReviveStaleRunContext() = runBlocking {
        val taskId = unique("stale-context-task")
        val oldRunId = unique("stale-context-run")
        val newRunId = unique("fresh-context-run")
        register(taskId, oldRunId)
        AgentTaskFullCapture.recordRunContext(
            context, taskId, oldRunId, "old task", "PRE_DISABLE_CONTEXT_MARKER", "", "model", JSONObject(),
        )
        val beforeDisable = AgentTaskFullCapture.snapshotForExport(context, taskId, includeSensitive = true)
        assertNotNull(beforeDisable)
        assertTrue(requireNotNull(beforeDisable).json.contains("PRE_DISABLE_CONTEXT_MARKER"))

        assertTrue(setEnabled(taskId, enabled = false))
        assertFalse(isEnabled(taskId))
        assertEquals(
            "disabled_at_snapshot",
            AgentTaskFullCapture.snapshotForExport(context, taskId, includeSensitive = true)?.status,
        )
        assertTrue(setEnabled(taskId, enabled = true))
        assertTrue(isEnabled(taskId))

        AgentTaskFullCapture.recordRunContext(
            context, taskId, oldRunId, "old task", "STALE_CONTEXT_MUST_NOT_RETURN", "", "model", JSONObject(),
        )
        register(taskId, newRunId)
        AgentTaskFullCapture.recordRunContext(
            context, taskId, newRunId, "new task", "FRESH_CONTEXT_MARKER", "", "model", JSONObject(),
        )

        val afterReenable = AgentTaskFullCapture.snapshotForExport(context, taskId, includeSensitive = true)
        assertNotNull(afterReenable)
        val json = requireNotNull(afterReenable).json
        assertTrue(json.contains("FRESH_CONTEXT_MARKER"))
        assertFalse(json.contains("PRE_DISABLE_CONTEXT_MARKER"))
        assertFalse(json.contains("STALE_CONTEXT_MUST_NOT_RETURN"))
    }

    @Test
    fun disablingAndReenablingDoesNotReviveStaleToolResultForRunPath() = runBlocking {
        val taskId = unique("stale-tool-task")
        val oldRunId = unique("stale-tool-run")
        val newRunId = unique("fresh-tool-run")
        register(taskId, oldRunId)
        AgentTaskFullCapture.recordToolResultForRun(
            context, oldRunId, "old-call", "tasks_list", "PRE_DISABLE_TOOL_RESULT", false, null, JSONArray(),
        )
        assertTrue(requireNotNull(AgentTaskFullCapture.snapshotForExport(context, taskId, true)).json
            .contains("PRE_DISABLE_TOOL_RESULT"))

        assertTrue(setEnabled(taskId, enabled = false))
        assertTrue(setEnabled(taskId, enabled = true))
        AgentTaskFullCapture.recordToolResultForRun(
            context, oldRunId, "old-call", "tasks_list", "STALE_TOOL_RESULT_MUST_NOT_RETURN", false, null, JSONArray(),
        )
        register(taskId, newRunId)
        AgentTaskFullCapture.recordToolResultForRun(
            context, newRunId, "new-call", "tasks_list", "FRESH_TOOL_RESULT_MARKER", false, null, JSONArray(),
        )

        val afterReenable = requireNotNull(AgentTaskFullCapture.snapshotForExport(context, taskId, true))
        assertTrue(afterReenable.json.contains("FRESH_TOOL_RESULT_MARKER"))
        assertFalse(afterReenable.json.contains("PRE_DISABLE_TOOL_RESULT"))
        assertFalse(afterReenable.json.contains("STALE_TOOL_RESULT_MUST_NOT_RETURN"))
    }

    @Test
    fun shortDataUriIsOmittedFromFullCapture() = runBlocking {
        val taskId = unique("short-data-uri-task")
        val runId = unique("short-data-uri-run")
        register(taskId, runId)
        val dataUri = "data:image/png;base64,AA=="
        AgentTaskFullCapture.recordToolResultForRun(
            context,
            runId,
            "image-call",
            "tasks_list",
            JSONObject().put("preview", dataUri).put("message", "VISIBLE_IMAGE_RESULT").toString(),
            sensitive = false,
            command = null,
            images = JSONArray(),
        )

        val snapshot = requireNotNull(AgentTaskFullCapture.snapshotForExport(context, taskId, true))
        assertFalse(snapshot.json.contains(dataUri))
        val result = eventOfType(JSONObject(snapshot.json).getJSONArray("events"), "tool_result")
            .getJSONObject("result")
        assertTrue(result.getJSONObject("preview").getBoolean("omitted"))
        assertEquals("binary_payload", result.getJSONObject("preview").getString("reason"))
        assertEquals("VISIBLE_IMAGE_RESULT", result.getString("message"))
    }

    @Test
    fun mimeWrappedBase64IsOmittedRatherThanStoredAsText() = runBlocking {
        val taskId = unique("wrapped-binary-task")
        val runId = unique("wrapped-binary-run")
        register(taskId, runId)
        val binary = java.util.Base64.getMimeEncoder().encodeToString(ByteArray(3000) { 97 })
        assertTrue(binary.contains("\r\n"))
        AgentTaskFullCapture.recordToolResultForRun(
            context, runId, "binary-call", "terminal", binary, false, null, JSONArray(),
        )
        val snapshot = requireNotNull(AgentTaskFullCapture.snapshotForExport(context, taskId, true))
        val result = eventOfType(JSONObject(snapshot.json).getJSONArray("events"), "tool_result")
            .getJSONObject("result")
        assertTrue(result.getBoolean("omitted"))
        assertEquals("binary_payload", result.getString("reason"))
        assertFalse(snapshot.json.contains(binary.take(64)))
    }

    @Test
    fun oversizedAndDeepStructuredInputsStayBoundedWithoutCrashing() = runBlocking {
        val taskId = unique("bounded-input-task")
        val runId = unique("bounded-input-run")
        register(taskId, runId)
        val longStructuredJson = JSONObject()
            .put("payload", "L".repeat(AgentTaskFullCapture.MAX_EVENT_BYTES + 8_192))
            .toString()
        var deepStructuredJson = "\"leaf\""
        repeat(20) {
            deepStructuredJson = "{\"next\":$deepStructuredJson}"
        }
        AgentTaskFullCapture.recordToolCallForRun(
            context, runId, "long-call", "tasks_list", 1, longStructuredJson,
        )
        AgentTaskFullCapture.recordToolCallForRun(
            context, runId, "deep-call", "tasks_list", 2, deepStructuredJson,
        )
        AgentTaskFullCapture.recordRunResponseForRun(
            context, runId, "LONG_TEXT_MARKER " + "R".repeat(AgentTaskFullCapture.MAX_EVENT_BYTES * 2),
        )

        val snapshot = requireNotNull(AgentTaskFullCapture.snapshotForExport(context, taskId, true))
        val events = JSONObject(snapshot.json).getJSONArray("events")
        assertEquals(3, events.length())
        for (index in 0 until events.length()) {
            val encodedBytes = events.getJSONObject(index).toString().toByteArray(Charsets.UTF_8).size
            val event = events.getJSONObject(index)
            assertTrue("event $index exceeded the byte cap: $encodedBytes", encodedBytes <= AgentTaskFullCapture.MAX_EVENT_BYTES)
            if (event.optString("type") == "run_completed") assertTrue(event.getBoolean("truncated"))
        }
        val arguments = (0 until events.length())
            .map { events.getJSONObject(it) }
            .filter { it.optString("type") == "tool_call" }
            .map { it.getJSONObject("arguments") }
        assertTrue(arguments.all { it.optBoolean("truncated") })
        assertTrue(arguments.any { it.optString("reason") == "structured_payload_too_large" })
        assertTrue(arguments.any { it.optString("reason") == "structured_payload_too_deep" })
        assertTrue(snapshot.json.length < AgentTaskFullCapture.MAX_EVENT_BYTES * 4)
    }

    private fun register(taskId: String, runId: String) {
        AgentTaskFullCapture.registerRun(context, taskId, runId)
        registeredRunIds += runId
    }

    private suspend fun isEnabled(taskId: String): Boolean =
        withContext(Dispatchers.IO) { AgentTaskFullCapture.isEnabled(context, taskId) }

    private suspend fun setEnabled(taskId: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) { AgentTaskFullCapture.setEnabled(context, taskId, enabled) }

    private suspend fun export(taskId: String, includeSensitive: Boolean): Map<String, String> {
        val output = ByteArrayOutputStream()
        AgentTaskDiagnostics.export(context, taskId, output, includeSensitive = includeSensitive)
        return ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    private suspend fun resetFullCaptureStore() {
        withContext(Dispatchers.IO) {
            AgentTaskFullCapture.snapshotForExport(context, unique("queue-drain"), includeSensitive = true)
            context.getSharedPreferences("agent_task_full_capture", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            context.getDir("agent_task_diagnostics_full", Context.MODE_PRIVATE).deleteRecursively()
        }
    }

    private fun task(id: String, prompt: String = "DATABASE_TASK_PROMPT") = AgentTaskEntity(
        id = id,
        name = "Diagnostic test task",
        prompt = prompt,
        triggerJson = """{"type":"manual"}""",
        enabled = true,
        nextRunAt = null,
        cooldownSeconds = 60,
        maxRuns = 10,
        createdAt = 1,
        updatedAt = 2,
    )

    private fun eventOfType(events: JSONArray, type: String): JSONObject =
        (0 until events.length())
            .map { events.getJSONObject(it) }
            .first { it.optString("type") == type }

    private fun unique(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
