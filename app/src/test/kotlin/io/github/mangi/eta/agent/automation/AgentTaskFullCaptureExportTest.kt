package io.github.mangi.eta.agent.automation

import android.content.Context
import android.content.ContextWrapper
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.virtualdisplay.AutomationDisplayFailure
import io.github.mangi.eta.agent.virtualdisplay.AutomationDisplayFailureDetail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTaskFullCaptureExportTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        AgentTaskDiagnostics.clearForTests(context)
        AgentTaskFullCapture.clearForTests(context)
    }

    @After
    fun tearDown() {
        AgentTaskDiagnostics.clearForTests(context)
        AgentTaskFullCapture.clearForTests(context)
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun fullDiagnosticsRequireExplicitExportRequestAndDisabledGenerationIsHidden() = runBlocking {
        val taskId = "full-export-${UUID.randomUUID()}"
        val runId = "full-export-run-${UUID.randomUUID()}"
        EtaDatabase.get(context).agentTaskDao().insertTask(task(taskId))

        AgentTaskFullCapture.registerRun(context, taskId, runId)
        AgentTaskDiagnostics.recordFullRunContext(
            context = context,
            taskId = taskId,
            runId = runId,
            taskName = "private task title",
            taskPrompt = "private task prompt",
            runtimePrompt = "private runtime prompt",
            model = "model-name",
            modelParameters = JSONObject().put("contextWindow", 4096),
        )
        AgentTaskFullCapture.awaitPendingWritesForTests()

        val defaultOutput = TrackingOutputStream()
        AgentTaskDiagnostics.export(context, taskId, defaultOutput)
        assertFalse(defaultOutput.closed)
        val defaultEntries = unzip(defaultOutput.toByteArray())
        assertFalse(defaultEntries.containsKey("full_diagnostics.json"))
        assertEquals("not_requested", JSONObject(defaultEntries.getValue("manifest.json")).getString("fullCaptureStatus"))
        assertFalse(defaultEntries.values.any { it.contains("private task prompt") })

        val requestedOutput = TrackingOutputStream()
        AgentTaskDiagnostics.export(context, taskId, requestedOutput, includeSensitive = true)
        assertFalse(requestedOutput.closed)
        val requestedEntries = unzip(requestedOutput.toByteArray())
        assertTrue(requestedEntries.containsKey("full_diagnostics.json"))
        assertTrue(requestedEntries.getValue("full_diagnostics.json").contains("private task prompt"))
        assertEquals("included", JSONObject(requestedEntries.getValue("manifest.json")).getString("fullCaptureStatus"))

        val disabledResult = withContext(Dispatchers.IO) {
            AgentTaskDiagnostics.setFullCaptureEnabled(context, taskId, enabled = false)
        }
        assertTrue(disabledResult)
        assertFalse(AgentTaskDiagnostics.isFullCaptureEnabled(context, taskId))
        val disabledOutput = ByteArrayOutputStream()
        AgentTaskDiagnostics.export(context, taskId, disabledOutput, includeSensitive = true)
        val disabledEntries = unzip(disabledOutput.toByteArray())
        assertFalse(disabledEntries.containsKey("full_diagnostics.json"))
        assertEquals(
            "disabled_at_snapshot",
            JSONObject(disabledEntries.getValue("manifest.json")).getString("fullCaptureStatus"),
        )
    }

    @Test
    fun quotedAndPrefixTruncatedCredentialsAreRedacted() = runBlocking {
        val taskId = "redaction-export-${UUID.randomUUID()}"
        val runId = "redaction-run-${UUID.randomUUID()}"
        AgentTaskFullCapture.registerRun(context, taskId, runId)
        AgentTaskFullCapture.recordToolCallForRun(
            context, runId, "plain", "shell", 1, "password=\"SECRET WITH SPACES\"",
        )
        AgentTaskFullCapture.recordToolCallForRun(
            context, runId, "partial-quote", "shell", 2,
            "password=\"PARTIAL_QUOTED_SECRET ${"x".repeat(AgentTaskFullCapture.MAX_EVENT_BYTES)}",
        )
        AgentTaskFullCapture.recordToolCallForRun(
            context, runId, "partial-pem", "shell", 3,
            "-----BEGIN RSA PRIVATE KEY-----${"p".repeat(AgentTaskFullCapture.MAX_EVENT_BYTES)}",
        )
        AgentTaskFullCapture.awaitPendingWritesForTests()

        val snapshot = AgentTaskFullCapture.snapshotForExport(context, taskId, includeSensitive = true)
        assertEquals("included", snapshot?.status)
        val exported = snapshot?.json.orEmpty()
        assertFalse(exported.contains("SECRET WITH SPACES"))
        assertFalse(exported.contains("PARTIAL_QUOTED_SECRET"))
        assertFalse(exported.contains("-----BEGIN RSA PRIVATE KEY-----"))
        assertTrue(exported.contains("[REDACTED]"))
    }

    @Test
    fun safeExportRetainsTypedDisplayDiagnosticsWhenFullCaptureIsDisabled() = runBlocking {
        val taskId = "safe-display-export-${UUID.randomUUID()}"
        val runId = "safe-display-run-${UUID.randomUUID()}"
        val dao = EtaDatabase.get(context).agentTaskDao()
        dao.insertTask(task(taskId))
        dao.insertRun(
            AgentTaskRunEntity(
                id = runId,
                taskId = taskId,
                fireKey = "manual:test",
                status = "completed",
                queuedAt = 1_000,
                startedAt = 1_000,
                finishedAt = 1_001,
            ),
        )
        assertTrue(withContext(Dispatchers.IO) {
            AgentTaskDiagnostics.setFullCaptureEnabled(context, taskId, enabled = false)
        })

        AgentTaskDiagnostics.recordDisplayFailureDetailForRun(
            context,
            runId,
            AutomationDisplayFailureDetail(
                reason = AutomationDisplayFailure.ROOT_BRIDGE_FAILED,
                stage = AutomationDisplayFailureDetail.Stage.PROCESS_START,
                code = AutomationDisplayFailureDetail.Code.PROCESS_START_FAILED,
                exceptionKind = AutomationDisplayFailureDetail.ExceptionKind.IO_EXCEPTION,
                causeKind = null,
                sdkInt = 36,
                processUid = 12_345,
                branch = AutomationDisplayFailureDetail.Branch.DISPLAY_MANAGER,
            ),
        )

        val output = ByteArrayOutputStream()
        AgentTaskDiagnostics.export(context, taskId, output, includeSensitive = true)
        val entries = unzip(output.toByteArray())
        assertFalse(entries.containsKey("full_diagnostics.json"))
        val manifest = JSONObject(entries.getValue("manifest.json"))
        assertEquals("disabled_at_snapshot", manifest.getString("fullCaptureStatus"))
        val events = JSONArray(entries.getValue("events.json"))
        val failure = (0 until events.length())
            .map(events::getJSONObject)
            .single { it.optString("type") == "display_failure" }
        assertEquals("ROOT_BRIDGE_FAILED", failure.getString("reason"))
        assertEquals("PROCESS_START", failure.getString("stage"))
        assertEquals("PROCESS_START_FAILED", failure.getString("code"))
        assertEquals("IO_EXCEPTION", failure.getString("exceptionKind"))
        assertEquals(36, failure.getInt("sdkInt"))
        assertEquals(12_345, failure.getInt("processUid"))
        assertEquals("DISPLAY_MANAGER", failure.getString("branch"))
        assertFalse(entries.values.joinToString("\n").contains("private task prompt"))
    }

    @Test
    fun recorderAndWriterFailuresNeverEscapeOrKillTheWriter() {
        val brokenContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = throw IllegalStateException("context unavailable")
        }
        AgentTaskDiagnostics.registerFullCaptureRun(brokenContext, "task", "broken-registration-run")
        AgentTaskDiagnostics.recordFullRunContext(
            brokenContext, "task", "broken-registration-run", "name", "prompt", "runtime", "model", JSONObject(),
        )
        AgentTaskDiagnostics.recordDeviceState(brokenContext, "task", "broken-registration-run")
        AgentTaskDiagnostics.recordDisplayFailureForRun(
            brokenContext, "broken-registration-run", AgentTaskDiagnostics.DisplayFailure.ROOT_BRIDGE_FAILED,
        )

        val failingDirectoryContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDir(name: String, mode: Int): File = throw IOException("disk unavailable")
        }
        val taskId = "writer-recovery-task-${UUID.randomUUID()}"
        val runId = "writer-recovery-run-${UUID.randomUUID()}"
        AgentTaskFullCapture.registerRun(failingDirectoryContext, taskId, runId)
        AgentTaskFullCapture.recordRunContext(
            failingDirectoryContext, taskId, runId, "name", "prompt", "runtime", "model", JSONObject(),
        )
        AgentTaskFullCapture.awaitPendingWritesForTests()
        AgentTaskFullCapture.recordRunResponseForRun(context, runId, "writer continued after IO error")
        AgentTaskFullCapture.awaitPendingWritesForTests()

        val snapshot = runBlocking {
            AgentTaskFullCapture.snapshotForExport(context, taskId, includeSensitive = true)
        }
        assertTrue(snapshot?.json.orEmpty().contains("writer continued after IO error"))
    }

    @Test
    fun safeProjectionKeepsImeFailureBranchEnumsAndDropsUnknownBranchText() = runBlocking {
        val taskId = "safe-ime-export-${UUID.randomUUID()}"
        val runId = "safe-ime-run-${UUID.randomUUID()}"
        val dao = EtaDatabase.get(context).agentTaskDao()
        dao.insertTask(task(taskId))
        dao.insertRun(
            AgentTaskRunEntity(
                id = runId,
                taskId = taskId,
                fireKey = "manual:ime",
                status = "completed",
                queuedAt = 1_000,
                startedAt = 1_000,
                finishedAt = 1_001,
            ),
        )
        assertTrue(withContext(Dispatchers.IO) {
            AgentTaskDiagnostics.setFullCaptureEnabled(context, taskId, enabled = false)
        })

        val expectedBranches = setOf(
            "IME_POLICY",
            "IME_SERVICE_LOOKUP",
            "IME_SET_METHOD_LOOKUP",
            "IME_SET_METHOD_INVOCATION",
            "IME_GET_METHOD_LOOKUP",
            "IME_GET_METHOD_INVOCATION",
            "IME_READBACK_MISMATCH",
        )
        expectedBranches.forEach { name ->
            AgentTaskDiagnostics.recordDisplayFailureDetailForRun(
                context,
                runId,
                AutomationDisplayFailureDetail(
                    reason = AutomationDisplayFailure.ROOT_BRIDGE_FAILED,
                    stage = AutomationDisplayFailureDetail.Stage.DISPLAY_ISOLATION,
                    code = AutomationDisplayFailureDetail.Code.DISPLAY_ISOLATION_UNAVAILABLE,
                    branch = AutomationDisplayFailureDetail.Branch.valueOf(name),
                ),
            )
        }

        val baselineOutput = ByteArrayOutputStream()
        AgentTaskDiagnostics.export(context, taskId, baselineOutput, includeSensitive = true)
        val baseline = unzip(baselineOutput.toByteArray())
        assertFalse(baseline.containsKey("full_diagnostics.json"))
        val safeEvents = JSONArray(baseline.getValue("events.json"))
        val projectedBranches = (0 until safeEvents.length())
            .map { safeEvents.getJSONObject(it).optString("branch") }
            .filter { it.isNotBlank() }
            .toSet()
        assertEquals(expectedBranches, projectedBranches)

        val rawFile = AgentTaskDiagnostics.retainedFilesForTests(context).single()
        val rawRoot = JSONObject(rawFile.readText())
        rawRoot.getJSONArray("events").put(
            JSONObject()
                .put("type", "display_failure")
                .put("runKey", AgentTaskDiagnostics.hashIdentifier(runId))
                .put("reason", AgentTaskDiagnostics.DisplayFailure.ROOT_BRIDGE_FAILED.name)
                .put("branch", "IME_UNTRUSTED_TEXT_SHOULD_NOT_EXPORT"),
        )
        rawFile.writeText(rawRoot.toString())

        val filteredOutput = ByteArrayOutputStream()
        AgentTaskDiagnostics.export(context, taskId, filteredOutput, includeSensitive = true)
        val filtered = unzip(filteredOutput.toByteArray())
        val filteredText = filtered.values.joinToString("\n")
        assertFalse(filteredText.contains("IME_UNTRUSTED_TEXT_SHOULD_NOT_EXPORT"))
        val filteredEvents = JSONArray(filtered.getValue("events.json"))
        val unknownBranchEvent = (0 until filteredEvents.length())
            .map { filteredEvents.getJSONObject(it) }
            .single { it.optString("reason") == "ROOT_BRIDGE_FAILED" && !it.has("branch") }
        assertEquals("display_failure", unknownBranchEvent.getString("type"))
    }

    @Test
    fun fullCaptureRetentionBoundsTaskCountAndUsesHashedPaths() {
        for (index in 0..AgentTaskFullCapture.MAX_TASKS) {
            val taskId = "retention-private-task-$index"
            val runId = "retention-private-run-$index"
            AgentTaskFullCapture.registerRun(context, taskId, runId)
            AgentTaskFullCapture.recordRunContext(
                context, taskId, runId, "title", "prompt", "runtime", "model",
                JSONObject().put("contextWindow", 2048),
            )
        }
        AgentTaskFullCapture.awaitPendingWritesForTests()

        val retained = AgentTaskFullCapture.retainedFilesForTests(context)
        assertTrue("retained ${retained.size} task files", retained.size <= AgentTaskFullCapture.MAX_TASKS)
        assertTrue(retained.sumOf { it.length() } <= AgentTaskFullCapture.MAX_TOTAL_BYTES)
        assertTrue(retained.all { Regex("[0-9a-f]{64}\\.[0-9]+\\.full\\.json").matches(it.name) })
        assertTrue(retained.none { it.name.contains("retention-private-task") })
    }

    private fun task(id: String) = AgentTaskEntity(
        id = id,
        name = "private task name",
        prompt = "private task prompt",
        triggerJson = """{"type":"daily","time":"08:00","timeZone":"UTC"}""",
        enabled = true,
        nextRunAt = null,
        cooldownSeconds = 0,
        maxRuns = 10,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return result
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
