package io.github.mangi.eta.agent.automation

import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AgentTaskDiagnosticsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        AgentTaskDiagnostics.clearForTests(context)
    }

    @After
    fun tearDown() {
        AgentTaskDiagnostics.clearForTests(context)
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun exportProjectsOnlyWhitelistedContentAndHashesTaskAndRunIds() = runBlocking {
        val taskId = "raw-task-id-must-not-appear"
        val runId = "raw-run-id-must-not-appear"
        val sensitiveTrigger = """
            {"type":"notification_posted","filters":{"packageName":"PRIVATE_PACKAGE","titleContains":"PRIVATE_NOTIFICATION","textContains":"PRIVATE_TEXT"},"conditions":{"networkConnected":true}}
        """.trimIndent()
        val task = task(
            id = taskId,
            triggerJson = sensitiveTrigger,
            name = "PRIVATE_TASK_NAME",
            prompt = "PRIVATE_TASK_PROMPT",
        )
        val dao = EtaDatabase.get(context).agentTaskDao()
        dao.insertTask(task)
        dao.insertRun(
            AgentTaskRunEntity(
                id = runId,
                taskId = taskId,
                fireKey = "time:2000",
                status = "completed",
                eventJson = "PRIVATE_EVENT_JSON",
                queuedAt = 1_900,
                startedAt = 3_000,
                finishedAt = 4_000,
                resultPreview = "PRIVATE_MODEL_RESPONSE",
            ),
        )
        dao.insertRun(
            AgentTaskRunEntity(
                id = "clock-regression-run",
                taskId = taskId,
                fireKey = "time:5000",
                status = "failed",
                queuedAt = 2_000,
                startedAt = 4_000,
                finishedAt = 4_500,
            ),
        )

        AgentTaskDiagnostics.recordSchedulePlanned(context, taskId, sensitiveTrigger)
        AgentTaskDiagnostics.recordRunStarted(
            context = context,
            task = task,
            run = AgentTaskRunEntity(runId, taskId, "time:2000", status = "running", queuedAt = 1_900, startedAt = 3_000),
            toolToggles = mapOf("terminalTools" to true),
            rootStatus = "GRANTED",
            accessibilityAvailable = true,
            virtualDisplayEnabled = true,
            virtualDisplayActive = false,
        )
        AgentTaskDiagnostics.recordToolStarted(context, taskId, runId, "mcp_private_server_private_tool")
        AgentTaskDiagnostics.recordToolFinished(
            context,
            taskId,
            runId,
            "mcp_private_server_private_tool",
            success = false,
            resultSummary = """{"code":"PRIVATE_ERROR_CODE","message":"PRIVATE_EXCEPTION_TEXT"}""",
        )
        AgentTaskDiagnostics.recordDisplayFailure(
            context,
            taskId,
            runId,
            AgentTaskDiagnostics.DisplayFailure.VIRTUAL_DISPLAY_OCCUPIED,
        )
        AgentTaskDiagnostics.recordDisplayFailure(
            context,
            taskId,
            runId,
            AgentTaskDiagnostics.DisplayFailure.ROOT_BRIDGE_FAILED,
        )

        val archive = export(taskId)
        val allJson = archive.values.joinToString("\n")
        listOf(
            taskId, runId, "clock-regression-run", "PRIVATE_TASK_NAME", "PRIVATE_TASK_PROMPT", "PRIVATE_EVENT_JSON",
            "PRIVATE_MODEL_RESPONSE", "PRIVATE_PACKAGE", "PRIVATE_NOTIFICATION", "PRIVATE_TEXT",
            "mcp_private_server_private_tool", "PRIVATE_ERROR_CODE", "PRIVATE_EXCEPTION_TEXT",
        ).forEach { secret -> assertFalse("unexpected exported content: $secret", allJson.contains(secret)) }

        assertEquals(setOf("manifest.json", "task.json", "runs.json", "events.json"), archive.keys)
        val manifest = JSONObject(archive.getValue("manifest.json"))
        val taskProjection = JSONObject(archive.getValue("task.json"))
        val runs = JSONArray(archive.getValue("runs.json"))
        val events = JSONArray(archive.getValue("events.json"))
        assertEquals(AgentTaskDiagnostics.SCHEMA_VERSION, manifest.getInt("schemaVersion"))
        assertFalse(manifest.getBoolean("absenceMeansNoIssue"))
        assertEquals(AgentTaskDiagnostics.hashIdentifier(taskId), manifest.getString("taskKey"))
        assertEquals(AgentTaskDiagnostics.hashIdentifier(taskId), taskProjection.getString("taskKey"))
        assertFalse(taskProjection.has("name"))
        assertFalse(taskProjection.has("prompt"))
        assertFalse(taskProjection.has("nextRunAtEpochMs"))
        assertEquals(AgentTaskDiagnostics.taskLogFileName(taskId), AgentTaskDiagnostics.hashIdentifier(taskId) + ".json")
        assertFalse(AgentTaskDiagnostics.taskLogFileName(taskId).contains(taskId))
        val runProjection = runs.getJSONObject(0)
        assertEquals(AgentTaskDiagnostics.hashIdentifier(runId), runProjection.getString("runKey"))
        assertEquals("time", runProjection.getString("source"))
        assertEquals(2_000, runProjection.getLong("plannedAtEpochMs"))
        assertEquals(3_000, runProjection.getLong("actualAtEpochMs"))
        assertEquals(1_000, runProjection.getLong("delayMs"))
        assertFalse(runProjection.getBoolean("clockRegression"))
        val regressionProjection = runs.getJSONObject(1)
        assertEquals(AgentTaskDiagnostics.hashIdentifier("clock-regression-run"), regressionProjection.getString("runKey"))
        assertTrue(regressionProjection.isNull("delayMs"))
        assertTrue(regressionProjection.getBoolean("clockRegression"))
        val safeTrigger = taskProjection.getJSONObject("trigger")
        assertEquals(true, safeTrigger.getJSONObject("conditions").getBoolean("networkConnected"))
        assertFalse(safeTrigger.has("filters"))
        assertTrue((0 until events.length()).any { index ->
            val event = events.getJSONObject(index)
            event.optString("reason") == "VIRTUAL_DISPLAY_OCCUPIED" &&
                event.optString("runKey") == AgentTaskDiagnostics.hashIdentifier(runId)
        })
        assertTrue((0 until events.length()).any { index ->
            val event = events.getJSONObject(index)
            event.optString("reason") == "ROOT_BRIDGE_FAILED" &&
                event.optString("runKey") == AgentTaskDiagnostics.hashIdentifier(runId)
        })
        assertTrue((0 until events.length()).any { index ->
            events.getJSONObject(index).optString("tool") == "unknown"
        })
        assertTrue((0 until events.length()).any { index ->
            events.getJSONObject(index).optString("code") == "UNKNOWN"
        })
    }

    @Test
    fun absentLogIsExplicitlyHistoricalButNotClaimedAsRetentionTruncated() = runBlocking {
        val task = task(id = "task-with-no-events-${UUID.randomUUID()}")
        val directory = context.getDir("agent_task_diagnostics", android.content.Context.MODE_PRIVATE)
        val logFile = File(directory, AgentTaskDiagnostics.taskLogFileName(task.id))
        listOf(logFile, File(logFile.path + ".bak"), File(logFile.path + ".new")).forEach { it.delete() }
        EtaDatabase.get(context).agentTaskDao().insertTask(task)
        assertFalse("fixture begins without a safe diagnostic file", logFile.isFile)

        val archive = export(task.id)
        assertFalse("export must not create a safe log", logFile.isFile)
        val manifest = JSONObject(archive.getValue("manifest.json"))
        assertFalse(manifest.getBoolean("diagnosticLogPresent"))
        assertFalse(manifest.getBoolean("truncated"))
        assertEquals(0, manifest.getInt("retainedEventCount"))
        assertEquals("historical_unavailable_no_retained_log", manifest.getString("coverageStatus"))
        assertFalse(manifest.getBoolean("historicalEventsAvailable"))
        assertFalse(manifest.getBoolean("absenceMeansNoIssue"))
    }

    @Test
    fun perTaskEventAndGlobalFileRetentionStayBounded() = runBlocking {
        val dao = EtaDatabase.get(context).agentTaskDao()
        val oneTask = task(id = "bounded-task")
        dao.insertTask(oneTask)
        repeat(AgentTaskDiagnostics.MAX_EVENTS_PER_TASK + 1) { index ->
            AgentTaskDiagnostics.recordRunBusy(context, oneTask.id, "busy-run-$index")
        }
        val boundedArchive = export(oneTask.id)
        val boundedManifest = JSONObject(boundedArchive.getValue("manifest.json"))
        assertEquals(AgentTaskDiagnostics.MAX_EVENTS_PER_TASK, boundedManifest.getInt("retainedEventCount"))
        assertTrue(boundedManifest.getBoolean("truncated"))

        val taskIds = (0 until AgentTaskDiagnostics.MAX_TASK_FILES + 1).map { "global-task-$it" }
        taskIds.forEach { taskId ->
            dao.insertTask(task(id = taskId))
            AgentTaskDiagnostics.recordSchedulePlanned(context, taskId, """{"type":"once","at":"2026-11-12T00:00:00Z"}""")
        }
        export(taskIds.last())
        assertTrue(AgentTaskDiagnostics.retainedFilesForTests(context).size <= AgentTaskDiagnostics.MAX_TASK_FILES)

        val byteLimitTask = task("global-byte-limit-task")
        dao.insertTask(byteLimitTask)
        val directory = context.getDir("agent_task_diagnostics", android.content.Context.MODE_PRIVATE)
        repeat(3) { index ->
            directory.resolve(AgentTaskDiagnostics.taskLogFileName("large-old-task-$index"))
                .writeBytes(ByteArray(750_000) { 0x30.toByte() })
        }
        AgentTaskDiagnostics.recordRunBusy(context, byteLimitTask.id, "byte-limit-run")
        export(byteLimitTask.id)
        val retainedBytes = directory.listFiles().orEmpty().sumOf { it.length() }
        assertTrue("retained logs exceeded global byte cap: $retainedBytes", retainedBytes <= AgentTaskDiagnostics.MAX_TOTAL_LOG_BYTES)
    }

    @Test
    fun timingProjectionRejectsNegativeDelayAndMalformedOrOverflowingFireKeys() {
        assertEquals(2_000L, AgentTaskDiagnostics.plannedTimeFromFireKey("time:2000"))
        assertNull(AgentTaskDiagnostics.plannedTimeFromFireKey("time:2000:extra"))
        assertNull(AgentTaskDiagnostics.plannedTimeFromFireKey("time:999999999999999999999999"))
        val regression = AgentTaskDiagnostics.timingForFireKey("time:3000", 2_999)
        assertEquals("time", regression.source)
        assertNull(regression.delayMs)
        assertTrue(regression.clockRegression)
        val manual = AgentTaskDiagnostics.timingForFireKey("manual:private-uuid", 2_000)
        assertEquals("manual", manual.source)
        assertNull(manual.plannedAt)
        assertNull(manual.delayMs)
        val event = AgentTaskDiagnostics.timingForFireKey("notification_posted:private-data", 2_000)
        assertEquals("event", event.source)
        assertNull(event.plannedAt)
        assertNull(event.delayMs)
    }

    private suspend fun export(taskId: String): Map<String, String> {
        val bytes = ByteArrayOutputStream()
        AgentTaskDiagnostics.export(context, taskId, bytes)
        assertTrue(bytes.size() <= AgentTaskDiagnostics.MAX_EXPORT_BYTES)
        return ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    private fun task(
        id: String,
        triggerJson: String = """{"type":"interval","seconds":3600}""",
        name: String = "Task",
        prompt: String = "prompt",
    ) = AgentTaskEntity(
        id = id,
        name = name,
        prompt = prompt,
        triggerJson = triggerJson,
        enabled = true,
        nextRunAt = 123_456,
        cooldownSeconds = 60,
        maxRuns = 10,
        createdAt = 1,
        updatedAt = 2,
    )
}
