package io.github.mangi.eta.agent.automation

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.PowerManager
import android.util.AtomicFile
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootAccessStatus
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.virtualdisplay.AutomationDisplayFailureDetail
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Best-effort scheduled-task diagnostics. Safe records stay content-free; full capture is separately opt-in/export-gated. */
internal object AgentTaskDiagnostics {
    internal const val SCHEMA_VERSION = 1
    internal const val MAX_EVENTS_PER_TASK = 120
    internal const val MAX_TASK_LOG_BYTES = 128 * 1024
    internal const val MAX_TASK_FILES = 32
    internal const val MAX_TOTAL_LOG_BYTES = 2 * 1024 * 1024
    internal const val MAX_EXPORTED_RUNS = 200
    internal const val MAX_EXPORT_BYTES = 1024 * 1024
    private const val DIRECTORY = "agent_task_diagnostics"
    private const val FILE_SUFFIX = ".json"
    private const val WRITE_QUEUE_SIZE = 256
    private val stateLock = Any()

    private val writes = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WRITE_QUEUE_SIZE),
        { task -> Thread(task, "eta-task-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private enum class Kind(val wire: String) {
        SCHEDULE_PLANNED("schedule_planned"),
        SCHEDULE_OBSERVED("schedule_observed"),
        CONDITION_CHECK("condition_check"),
        RUN_STARTED("run_started"),
        RUN_BUSY("runtime_busy"),
        RUN_COMPLETED("run_completed"),
        RUN_CANCELLED("run_cancelled"),
        CANCEL_REQUESTED("cancel_requested"),
        RUN_FAILED("run_failed"),
        TOOL_STARTED("tool_started"),
        TOOL_FINISHED("tool_finished"),
        CAPABILITY_SNAPSHOT("capability_snapshot"),
        DISPLAY_FAILURE("display_failure"),
    }

    private val safeErrorCodes = setOf(
        "UNKNOWN",
        "CONFIGURATION_REQUIRED",
        "AUTOMATION_RUNTIME_BUSY",
        "AUTOMATION_CANCELLED",
        "AUTOMATION_TASK_TOOL_UNAVAILABLE",
        "AUTOMATION_FOREGROUND_UNAVAILABLE",
        "AUTOMATION_VIRTUAL_DISPLAY_DISABLED",
        "AUTOMATION_ROOT_REQUIRED",
        "AUTOMATION_DISPLAY_UNSUPPORTED",
        "AUTOMATION_DISPLAY_LOST",
        "AUTOMATION_VIRTUAL_DISPLAY_UNAVAILABLE",
        "AUTOMATION_VIRTUAL_LAUNCH_FAILED",
        "AUTOMATION_KEY_FAILED",
        "ROOT_REQUIRED",
        "ACCESSIBILITY_UNAVAILABLE",
        "TOOL_DISABLED",
        "TERMINAL_TOOLS_DISABLED",
        "BROWSER_TOOLS_DISABLED",
        "NETWORK_UNAVAILABLE",
        "REQUEST_TIMEOUT",
        "CANCELLED",
    )

    private val safeRunStatuses = setOf(
        "ready", "queued", "running", "completed", "failed", "cancelled", "interrupted",
        "configuration_required",
    )

    private val safeConditionNames = setOf("charging", "unlocked", "networkConnected")
    private val SAFE_TOGGLE_NAMES = listOf(
        "terminalTools", "browserTools", "deviceDirectTools", "deviceSensitiveReadTools",
        "deviceSensitiveActionTools",
    )

    /** Bounded writes run off the automation thread; rejection is safe because coverage is best-effort. */
    private fun record(context: Context, taskId: String, event: JSONObject) {
        runCatching {
            if (taskId.isBlank()) return@runCatching
            val appContext = context.applicationContext
            val key = hashIdentifier(taskId)
            try {
                writes.execute {
                    runCatching {
                        synchronized(stateLock) {
                            persist(appContext, key, event)
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                // This event is intentionally dropped rather than delaying the task. Export always states
                // that records are best-effort and that missing events do not imply no event occurred.
            } catch (_: RuntimeException) {
                // A broken diagnostics executor must never affect task execution.
            }
        }
    }

    private fun recordForRun(context: Context, runId: String, event: JSONObject) {
        runCatching {
            if (runId.isBlank()) return@runCatching
            val appContext = context.applicationContext
            try {
                writes.execute {
                    runCatching {
                        val taskId = runBlocking(Dispatchers.IO) {
                            EtaDatabase.get(appContext).agentTaskDao().run(runId)?.taskId
                        } ?: return@execute
                        synchronized(stateLock) {
                            persist(appContext, hashIdentifier(taskId), event)
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Diagnostics are best-effort; a full queue drops this record instead of delaying a run.
            } catch (_: RuntimeException) {
                // Lookup and persistence failures are intentionally isolated from runtime execution.
            }
        }
    }

    fun recordSchedulePlanned(context: Context, taskId: String, triggerJson: String) {
        val projected = projectTrigger(triggerJson)
        val source = sourceForTrigger(projected.optString("type"))
        record(context, taskId, event(Kind.SCHEDULE_PLANNED)
            .put("source", source)
            .put("trigger", projected))
    }

    fun recordScheduleObserved(
        context: Context,
        taskId: String,
        runId: String,
        fireKey: String,
        actualAt: Long,
        charging: Boolean?,
        unlocked: Boolean?,
        networkConnected: Boolean,
        conditionsSatisfied: Boolean,
        phase: String = "queued",
    ) {
        val timing = timingForFireKey(fireKey, actualAt)
        val item = event(Kind.SCHEDULE_OBSERVED)
            .put("runKey", hashIdentifier(runId))
            .put("source", timing.source)
            .put("phase", phase.takeIf { it in setOf("queued", "started", "dispatch_failed") } ?: "queued")
            .put("plannedAtEpochMs", timing.plannedAt ?: JSONObject.NULL)
            .put("actualAtEpochMs", actualAt)
            .put("delayMs", timing.delayMs ?: JSONObject.NULL)
            .put("clockRegression", timing.clockRegression)
            .put("conditionsSatisfied", conditionsSatisfied)
            .put("networkConnected", networkConnected)
        if (charging != null) item.put("charging", charging)
        if (unlocked != null) item.put("unlocked", unlocked)
        record(context, taskId, item)
    }

    fun recordConditionCheck(
        context: Context,
        taskId: String,
        state: Map<String, Boolean>,
        satisfied: Boolean,
        runId: String? = null,
    ) {
        val item = event(Kind.CONDITION_CHECK).put("satisfied", satisfied)
        runId?.let { item.put("runKey", hashIdentifier(it)) }
        safeConditionNames.sorted().forEach { name -> state[name]?.let { item.put(name, it) } }
        record(context, taskId, item)
    }

    fun recordRunStarted(
        context: Context,
        task: AgentTaskEntity,
        run: AgentTaskRunEntity,
        toolToggles: Map<String, Boolean>,
        rootStatus: String,
        accessibilityAvailable: Boolean,
        virtualDisplayEnabled: Boolean,
        virtualDisplayActive: Boolean,
    ) {
        val now = run.startedAt ?: System.currentTimeMillis()
        val timing = timingForFireKey(run.fireKey, now)
        val item = event(Kind.RUN_STARTED)
            .put("runKey", hashIdentifier(run.id))
            .put("source", timing.source)
            .put("plannedAtEpochMs", timing.plannedAt ?: JSONObject.NULL)
            .put("actualAtEpochMs", now)
            .put("delayMs", timing.delayMs ?: JSONObject.NULL)
            .put("clockRegression", timing.clockRegression)
            .put("rootStatus", safeRootStatus(rootStatus))
            .put("accessibilityAvailable", accessibilityAvailable)
            .put("virtualDisplayEnabled", virtualDisplayEnabled)
            .put("virtualDisplayActive", virtualDisplayActive)
            .put("conditions", projectConditions(task.triggerJson))
        SAFE_TOGGLE_NAMES.forEach { name -> toolToggles[name]?.let { item.put(name, it) } }
        record(context, task.id, item)
    }

    fun recordRunBusy(context: Context, taskId: String, runId: String) =
        record(context, taskId, event(Kind.RUN_BUSY)
            .put("runKey", hashIdentifier(runId))
            .put("code", "AUTOMATION_RUNTIME_BUSY"))

    fun recordCancelRequested(context: Context, taskId: String, runIds: List<String>) {
        if (runIds.isEmpty()) {
            record(context, taskId, event(Kind.CANCEL_REQUESTED).put("code", "CANCELLED"))
        } else {
            runIds.take(MAX_EVENTS_PER_TASK).forEach { runId ->
                record(context, taskId, event(Kind.CANCEL_REQUESTED)
                    .put("runKey", hashIdentifier(runId))
                    .put("code", "CANCELLED"))
            }
        }
    }

    fun recordRunFinished(context: Context, taskId: String, runId: String, status: String, errorCode: String? = null) {
        val safeStatus = status.takeIf { it in safeRunStatuses } ?: "unknown"
        val kind = when (safeStatus) {
            "completed" -> Kind.RUN_COMPLETED
            "cancelled" -> Kind.RUN_CANCELLED
            else -> Kind.RUN_FAILED
        }
        val item = event(kind).put("runKey", hashIdentifier(runId)).put("status", safeStatus)
        if (safeStatus != "completed") item.put("code", canonicalErrorCode(errorCode))
        record(context, taskId, item)
    }

    fun recordToolStarted(context: Context, taskId: String, runId: String, toolName: String) {
        record(context, taskId, event(Kind.TOOL_STARTED)
            .put("runKey", hashIdentifier(runId))
            .put("tool", canonicalToolName(toolName)))
    }

    fun recordToolFinished(
        context: Context,
        taskId: String,
        runId: String,
        toolName: String,
        success: Boolean?,
        resultSummary: String,
    ) {
        val item = event(Kind.TOOL_FINISHED)
            .put("runKey", hashIdentifier(runId))
            .put("tool", canonicalToolName(toolName))
        success?.let { item.put("success", it) }
        val code = canonicalErrorCode(extractErrorCode(resultSummary))
        if (success == false || code != "UNKNOWN") item.put("code", code)
        record(context, taskId, item)
    }

    fun isFullCaptureEnabled(context: Context, taskId: String): Boolean =
        AgentTaskFullCapture.isEnabled(context, taskId)

    fun setFullCaptureEnabled(context: Context, taskId: String, enabled: Boolean): Boolean =
        AgentTaskFullCapture.setEnabled(context, taskId, enabled)

    fun registerFullCaptureRun(context: Context, taskId: String, runId: String) {
        runCatching { AgentTaskFullCapture.registerRun(context, taskId, runId) }
    }

    fun unregisterFullCaptureRun(runId: String) {
        runCatching { AgentTaskFullCapture.unregisterRun(runId) }
    }

    fun recordFullRunContext(
        context: Context,
        taskId: String,
        runId: String,
        taskName: String,
        taskPrompt: String,
        runtimePrompt: String,
        model: String,
        modelParameters: JSONObject,
    ) = AgentTaskFullCapture.recordRunContext(
        context, taskId, runId, taskName, taskPrompt, runtimePrompt, model, modelParameters,
    )

    fun recordFullToolCall(
        context: Context, runId: String, callId: String, toolName: String, round: Int, argumentsJson: String,
    ) = AgentTaskFullCapture.recordToolCallForRun(context, runId, callId, toolName, round, argumentsJson)

    fun recordFullToolResult(
        context: Context,
        runId: String,
        callId: String,
        toolName: String,
        result: String,
        sensitive: Boolean,
        command: String?,
        images: JSONArray,
    ) = AgentTaskFullCapture.recordToolResultForRun(
        context, runId, callId, toolName, result, sensitive, command, images,
    )

    fun recordFullToolException(
        context: Context, runId: String, callId: String, toolName: String, error: Throwable,
    ) = AgentTaskFullCapture.recordToolExceptionForRun(context, runId, callId, toolName, error)

    fun recordFullResponse(context: Context, runId: String, response: String) =
        AgentTaskFullCapture.recordRunResponseForRun(context, runId, response)

    fun recordFullFailure(context: Context, runId: String, error: Throwable, cancelled: Boolean) =
        AgentTaskFullCapture.recordRunFailureForRun(context, runId, error, cancelled)

    fun recordDeviceState(context: Context, taskId: String, runId: String) {
        runCatching {
            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val state = JSONObject()
                .put("isInteractive", runCatching { power?.isInteractive }.getOrNull() ?: JSONObject.NULL)
                .put("isDeviceIdleMode", runCatching { power?.isDeviceIdleMode }.getOrNull() ?: JSONObject.NULL)
                .put("isPowerSaveMode", runCatching { power?.isPowerSaveMode }.getOrNull() ?: JSONObject.NULL)
                .put("keyguardLocked", runCatching { keyguard?.isKeyguardLocked }.getOrNull() ?: JSONObject.NULL)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                runCatching { power?.isLowPowerStandbyEnabled }.getOrNull()?.let { state.put("isLowPowerStandbyEnabled", it) }
            }
            runCatching {
                val powerManager = context.getSystemService(PowerManager::class.java)
                if (powerManager != null) state.put(
                    "isIgnoringBatteryOptimizations",
                    powerManager.isIgnoringBatteryOptimizations(context.packageName),
                )
            }
            runCatching {
                val manager = context.getSystemService(android.app.ActivityManager::class.java)
                if (manager != null) state.put("backgroundRestricted", manager.isBackgroundRestricted)
            }
            val safe = event(Kind.CAPABILITY_SNAPSHOT)
                .put("runKey", hashIdentifier(runId))
                .put("rootStatus", safeRootStatus(RootAccess.state.value.status.name))
                .put("accessibilityAvailable", AgentAccessibilityService.isAvailable())
                .put("virtualDisplayActive", VirtualDisplayController.snapshots.value.active)
            for (name in listOf(
                "isInteractive", "isDeviceIdleMode", "isPowerSaveMode", "keyguardLocked",
                "isLowPowerStandbyEnabled", "isIgnoringBatteryOptimizations", "backgroundRestricted",
            )) {
                state.opt(name)?.takeIf { it is Boolean }?.let { safe.put(name, it) }
            }
            record(context, taskId, safe)
            AgentTaskFullCapture.recordDeviceState(context, taskId, runId, state)
        }
    }

    fun recordCapabilities(
        context: Context,
        taskId: String,
        toolToggles: Map<String, Boolean>,
        virtualDisplayEnabled: Boolean,
        runId: String? = null,
    ) {
        val item = event(Kind.CAPABILITY_SNAPSHOT)
            .put("rootStatus", safeRootStatus(RootAccess.state.value.status.name))
            .put("accessibilityAvailable", AgentAccessibilityService.isAvailable())
            .put("virtualDisplayEnabled", virtualDisplayEnabled)
            .put("virtualDisplayActive", VirtualDisplayController.snapshots.value.active)
        runId?.let { item.put("runKey", hashIdentifier(it)) }
        SAFE_TOGGLE_NAMES.forEach { name -> toolToggles[name]?.let { item.put(name, it) } }
        record(context, taskId, item)
    }

    fun recordDisplayFailure(context: Context, taskId: String, runId: String, failure: DisplayFailure) {
        record(context, taskId, event(Kind.DISPLAY_FAILURE)
            .put("runKey", hashIdentifier(runId))
            .put("reason", failure.name))
    }

    fun recordDisplayFailureForRun(context: Context, runId: String, failure: DisplayFailure) {
        runCatching {
            recordSafeDisplayFailureForRun(context, runId, failure)
            AgentTaskFullCapture.recordDisplayFailureForRun(
                context, runId, JSONObject().put("reason", failure.name),
            )
        }
    }

    fun recordDisplayFailureDetailForRun(
        context: Context,
        runId: String,
        detail: AutomationDisplayFailureDetail,
    ) {
        runCatching {
            val safeFailure = DisplayFailure.valueOf(detail.reason.name)
            recordSafeDisplayFailureForRun(context, runId, safeFailure, detail)
            val fullDetail = JSONObject()
                .put("reason", detail.reason.name)
                .put("stage", detail.stage.name)
                .put("code", detail.code.name)
                .put("exceptionKind", detail.exceptionKind?.name ?: JSONObject.NULL)
                .put("causeKind", detail.causeKind?.name ?: JSONObject.NULL)
                .put("sdkInt", detail.sdkInt ?: JSONObject.NULL)
                .put("processUid", detail.processUid ?: JSONObject.NULL)
                .put("branch", detail.branch?.name ?: JSONObject.NULL)
            AgentTaskFullCapture.recordDisplayFailureForRun(context, runId, fullDetail)
        }
    }

    private fun recordSafeDisplayFailureForRun(
        context: Context,
        runId: String,
        failure: DisplayFailure,
        detail: AutomationDisplayFailureDetail? = null,
    ) {
        val item = event(Kind.DISPLAY_FAILURE)
            .put("runKey", hashIdentifier(runId))
            .put("reason", failure.name)
        detail?.let {
            item.put("stage", it.stage.name)
                .put("code", it.code.name)
                .put("exceptionKind", it.exceptionKind?.name ?: JSONObject.NULL)
                .put("causeKind", it.causeKind?.name ?: JSONObject.NULL)
                .put("sdkInt", it.sdkInt ?: JSONObject.NULL)
                .put("processUid", it.processUid ?: JSONObject.NULL)
                .put("branch", it.branch?.name ?: JSONObject.NULL)
        }
        recordForRun(context, runId, item)
    }

    enum class DisplayFailure {
        VIRTUAL_DISPLAY_OCCUPIED,
        ROOT_BRIDGE_FAILED,
        ROOT_UNAVAILABLE,
        VIRTUAL_DISPLAY_DISABLED,
        DISPLAY_UNSUPPORTED,
        DISPLAY_UNAVAILABLE,
        DISPLAY_LOST,
    }

    internal data class Timing(
        val source: String,
        val plannedAt: Long?,
        val delayMs: Long?,
        val clockRegression: Boolean,
    )

    internal fun timingForFireKey(fireKey: String, actualAt: Long): Timing {
        val planned = plannedTimeFromFireKey(fireKey)
        val source = when {
            planned != null -> "time"
            fireKey == "manual" || fireKey.startsWith("manual:") -> "manual"
            AgentTaskRules.eventTypes.any { fireKey.startsWith("$it:") } -> "event"
            else -> "unknown"
        }
        val regression = planned != null && actualAt < planned
        val delay = if (planned != null && !regression) actualAt - planned else null
        return Timing(source, planned, delay, regression)
    }

    internal fun plannedTimeFromFireKey(fireKey: String): Long? {
        val match = Regex("^time:([0-9]{1,16})$").matchEntire(fireKey) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it >= 0 }
    }

    internal fun hashIdentifier(identifier: String): String = MessageDigest.getInstance("SHA-256")
        .digest(identifier.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

    internal fun canonicalToolName(name: String): String =
        name.takeIf { it in AgentToolRequirements.toolNames } ?: "unknown"

    internal fun canonicalErrorCode(code: String?): String =
        code?.takeIf { it in safeErrorCodes } ?: "UNKNOWN"

    internal fun projectTrigger(triggerJson: String): JSONObject {
        val raw = runCatching { JSONObject(triggerJson) }.getOrNull() ?: return JSONObject().put("type", "unknown")
        val type = raw.optString("type").takeIf { it in AgentTaskRules.timeTypes || it in AgentTaskRules.eventTypes }
            ?: "unknown"
        val projected = JSONObject().put("type", type)
        if (type == "unknown") return projected
        runCatching { AgentTaskRules.validate(raw) }.onFailure { return projected.put("type", "unknown") }
        when (type) {
            "once" -> runCatching { parseInstant(raw.getString("at")) }
                .getOrNull()?.let { projected.put("atEpochMs", it) }
            "interval" -> {
                runCatching { AgentTaskRules.integer(raw, "seconds", 900, 31_536_000) }
                    .getOrNull()?.let { projected.put("seconds", it) }
                raw.optString("startAt").takeIf { it.isNotBlank() }
                    ?.let { value -> runCatching { parseInstant(value) }.getOrNull() }
                    ?.let { projected.put("startAtEpochMs", it) }
            }
            "daily" -> {
                raw.optString("time").takeIf { Regex("[0-2][0-9]:[0-5][0-9]").matches(it) }
                    ?.let { time -> runCatching { LocalTime.parse(time) }.getOrNull()?.let { projected.put("time", it.toString()) } }
                raw.optString("timeZone").takeIf { it in ZoneId.getAvailableZoneIds() || it == "UTC" }
                    ?.let { projected.put("timeZone", it) }
                raw.optJSONArray("weekdays")?.let { days ->
                    val safeDays = JSONArray()
                    for (index in 0 until days.length()) {
                        val day = days.optInt(index, -1)
                        if (day in 1..7) safeDays.put(day)
                    }
                    if (safeDays.length() > 0) projected.put("weekdays", safeDays)
                }
            }
        }
        projected.put("conditions", projectConditions(triggerJson))
        return projected
    }

    internal fun projectConditions(triggerJson: String): JSONObject {
        val conditions = runCatching { JSONObject(triggerJson).optJSONObject("conditions") }.getOrNull()
            ?: return JSONObject()
        return JSONObject().also { safe ->
            safeConditionNames.sorted().forEach { name ->
                if (conditions.has(name) && conditions.opt(name) is Boolean) safe.put(name, conditions.getBoolean(name))
            }
        }
    }

    internal fun sourceForTrigger(type: String): String = when {
        type in AgentTaskRules.timeTypes -> "time"
        type in AgentTaskRules.eventTypes -> "event"
        else -> "unknown"
    }

    private fun parseInstant(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()

    private fun event(kind: Kind): JSONObject = JSONObject()
        .put("type", kind.wire)
        .put("atEpochMs", System.currentTimeMillis())

    private fun extractErrorCode(rawResult: String): String? {
        if (rawResult.length > 4096) return null
        return runCatching {
            val json = JSONObject(rawResult)
            json.optString("code").takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun safeRootStatus(value: String): String = when (value) {
        RootAccessStatus.UNKNOWN.name,
        RootAccessStatus.UNAVAILABLE.name,
        RootAccessStatus.NOT_GRANTED.name,
        RootAccessStatus.GRANTED.name,
        RootAccessStatus.DENIED.name,
        RootAccessStatus.TIMED_OUT.name -> value
        else -> "UNKNOWN"
    }

    private fun persist(context: Context, taskKey: String, event: JSONObject) {
        val directory = context.getDir(DIRECTORY, Context.MODE_PRIVATE)
        if (!directory.exists() && !directory.mkdirs()) return
        val file = File(directory, taskKey + FILE_SUFFIX)
        val atomic = AtomicFile(file)
        val root = readRoot(atomic, file)
        val events = root.optJSONArray("events") ?: JSONArray()
        events.put(event)
        var truncated = root.optBoolean("truncated", false)
        while (events.length() > MAX_EVENTS_PER_TASK || encodedLogSize(events, truncated) > MAX_TASK_LOG_BYTES) {
            if (events.length() == 0) break
            events.remove(0)
            truncated = true
        }
        val data = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("truncated", truncated)
            .put("events", events)
            .toString()
            .toByteArray(Charsets.UTF_8)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(data)
            atomic.finishWrite(stream)
            stream = null
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            throw error
        }
        enforceGlobalLimits(directory, file)
    }

    private fun readRoot(atomic: AtomicFile, baseFile: File): JSONObject {
        if (!baseFile.exists() && !File(baseFile.path + ".bak").exists()) return JSONObject()
        return try {
            atomic.openRead().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_TASK_LOG_BYTES) return JSONObject().put("truncated", true)
                    bytes.write(buffer, 0, read)
                }
                JSONObject(String(bytes.toByteArray(), Charsets.UTF_8))
            }
        } catch (_: Exception) {
            JSONObject().put("truncated", true)
        }
    }

    private fun encodedLogSize(events: JSONArray, truncated: Boolean): Int =
        JSONObject().put("schemaVersion", SCHEMA_VERSION).put("truncated", truncated).put("events", events)
            .toString().toByteArray(Charsets.UTF_8).size

    private fun enforceGlobalLimits(directory: File, current: File) {
        fun storageFiles(): List<File> = directory.listFiles { candidate ->
            candidate.isFile && (
                candidate.name.endsWith(FILE_SUFFIX) ||
                    candidate.name.endsWith("$FILE_SUFFIX.bak") ||
                    candidate.name.endsWith("$FILE_SUFFIX.new")
                )
        }.orEmpty().toList()
        fun storageKey(file: File): String = file.name
            .removeSuffix(".bak")
            .removeSuffix(".new")
            .removeSuffix(FILE_SUFFIX)
        val currentKey = storageKey(current)
        while (true) {
            val all = storageFiles()
            val groups = all.groupBy(::storageKey)
            if (groups.size <= MAX_TASK_FILES && all.sumOf { it.length() } <= MAX_TOTAL_LOG_BYTES) return
            val oldest = groups.entries
                .filter { it.key != currentKey }
                .minByOrNull { entry -> entry.value.maxOfOrNull(File::lastModified) ?: Long.MIN_VALUE }
                ?: groups.entries.minByOrNull { entry -> entry.value.maxOfOrNull(File::lastModified) ?: Long.MIN_VALUE }
                ?: return
            val deleted = oldest.value.fold(false) { anyDeleted, file -> file.delete() || anyDeleted }
            if (!deleted) return
        }
    }

    /** Export is intentionally suspendable: DB/file/ZIP work never runs on the UI thread. */
    suspend fun export(
        context: Context,
        taskId: String,
        output: OutputStream,
        includeSensitive: Boolean = false,
    ) {
        awaitPendingWrites()
        withContext(Dispatchers.IO) {
            val fullSnapshot = AgentTaskFullCapture.snapshotForExport(context, taskId, includeSensitive)
            val appContext = context.applicationContext
            val dao = EtaDatabase.get(appContext).agentTaskDao()
            val task = dao.task(taskId) ?: throw IllegalArgumentException("Scheduled task not found")
            val queriedRuns = dao.runs(taskId, MAX_EXPORTED_RUNS + 1)
            val hasMoreRuns = queriedRuns.size > MAX_EXPORTED_RUNS
            val runs = queriedRuns.take(MAX_EXPORTED_RUNS)
            val taskKey = hashIdentifier(taskId)
            val (events, truncated, hasLog) = synchronized(stateLock) {
                val directory = appContext.getDir(DIRECTORY, Context.MODE_PRIVATE)
                val logFile = File(directory, taskKey + FILE_SUFFIX)
                // Do not construct AtomicFile for a missing log: some implementations create
                // the base file even when the caller only intends to read it.
                if (!logFile.exists() && !File(logFile.path + ".bak").exists()) {
                    Triple(JSONArray(), false, false)
                } else {
                    val root = readRoot(AtomicFile(logFile), logFile)
                    Triple(root.optJSONArray("events") ?: JSONArray(), root.optBoolean("truncated", false), logFile.isFile)
                }
            }
            val projectedEvents = projectStoredEvents(events)
            val manifest = manifest(
                appContext, taskKey, hasLog, projectedEvents.json.length(),
                truncated || projectedEvents.omitted, hasMoreRuns,
                includeSensitive, fullSnapshot,
            )
            val taskProjection = projectTask(task, taskKey)
            val runProjection = JSONArray(runs.asReversed().mapIndexed { index, run -> projectRun(run, index) })
            val entries = mutableListOf(
                "manifest.json" to manifest.toString(),
                "task.json" to taskProjection.toString(),
                "runs.json" to runProjection.toString(),
                "events.json" to projectedEvents.json.toString(),
            )
            fullSnapshot?.takeIf { it.status in setOf("included", "no_retained_log") }
                ?.let { entries += "full_diagnostics.json" to it.json }
            writeZip(
                output,
                entries,
                if (fullSnapshot?.status == "included") AgentTaskFullCapture.MAX_EXPORT_BYTES else MAX_EXPORT_BYTES,
            )
        }
    }

    private fun projectTask(task: AgentTaskEntity, taskKey: String): JSONObject = JSONObject()
        .put("taskKey", taskKey)
        .put("enabled", task.enabled)
        .put("trigger", projectTrigger(task.triggerJson))
        .put("cooldownSeconds", task.cooldownSeconds.coerceIn(0, 86_400))
        .put("maxRuns", task.maxRuns.coerceIn(0, 10_000))
        .put("runCount", task.runCount.coerceIn(0, 10_000))
        .put("lastStatus", task.lastStatus.takeIf { it in safeRunStatuses } ?: "unknown")
        .put("createdAtEpochMs", task.createdAt)
        .put("updatedAtEpochMs", task.updatedAt)

    private fun projectRun(run: AgentTaskRunEntity, sequence: Int): JSONObject {
        val plannedAt = plannedTimeFromFireKey(run.fireKey)
        val actualAt = run.startedAt
        val timing = timingForFireKey(run.fireKey, actualAt ?: plannedAt ?: 0L)
        return JSONObject()
            .put("runKey", hashIdentifier(run.id))
            .put("sequence", sequence)
            .put("source", timing.source)
            .put("plannedAtEpochMs", timing.plannedAt ?: JSONObject.NULL)
            .put("actualAtEpochMs", actualAt ?: JSONObject.NULL)
            .put("delayMs", if (actualAt != null) timing.delayMs ?: JSONObject.NULL else JSONObject.NULL)
            .put("clockRegression", actualAt != null && timing.clockRegression)
            .put("status", run.status.takeIf { it in safeRunStatuses } ?: "unknown")
            .put("queuedAtEpochMs", run.queuedAt)
            .put("startedAtEpochMs", actualAt ?: JSONObject.NULL)
            .put("finishedAtEpochMs", run.finishedAt ?: JSONObject.NULL)
    }

    private data class ProjectedEventLog(val json: JSONArray, val omitted: Boolean)

    private fun projectStoredEvents(rawEvents: JSONArray): ProjectedEventLog {
        val output = JSONArray()
        var omitted = rawEvents.length() > MAX_EVENTS_PER_TASK
        val start = (rawEvents.length() - MAX_EVENTS_PER_TASK).coerceAtLeast(0)
        for (index in start until rawEvents.length()) {
            val raw = rawEvents.optJSONObject(index)
            if (raw == null) {
                omitted = true
                continue
            }
            val type = raw.optString("type")
            val projected = JSONObject()
            raw.optLong("atEpochMs", -1).takeIf { it >= 0 }?.let { projected.put("atEpochMs", it) }
            raw.optString("runKey").takeIf { HASH_PATTERN.matches(it) }?.let { projected.put("runKey", it) }
            when (type) {
                Kind.SCHEDULE_PLANNED.wire -> {
                    projected.put("type", type)
                    projected.put("source", safeSource(raw.optString("source")))
                    safeTimestamp(raw, "plannedAtEpochMs")?.let { projected.put("plannedAtEpochMs", it) }
                    raw.optJSONObject("trigger")?.let { projected.put("trigger", sanitizeTriggerProjection(it)) }
                }
                Kind.SCHEDULE_OBSERVED.wire -> {
                    projected.put("type", type)
                    val planned = safeTimestamp(raw, "plannedAtEpochMs")
                    val actual = safeTimestamp(raw, "actualAtEpochMs")
                    val source = safeSource(raw.optString("source"))
                    projected.put("source", source)
                    raw.optString("phase").takeIf { it in setOf("queued", "started", "dispatch_failed") }
                        ?.let { projected.put("phase", it) }
                    if (source == "time") planned?.let { projected.put("plannedAtEpochMs", it) }
                    actual?.let { projected.put("actualAtEpochMs", it) }
                    val regression = source == "time" && planned != null && actual != null && actual < planned
                    projected.put("clockRegression", regression)
                    if (source == "time" && planned != null && actual != null && !regression) {
                        projected.put("delayMs", actual - planned)
                    }
                    raw.optBoolean("conditionsSatisfied").let { projected.put("conditionsSatisfied", it) }
                    raw.opt("networkConnected")?.takeIf { it is Boolean }?.let { projected.put("networkConnected", it) }
                    raw.opt("charging")?.takeIf { it is Boolean }?.let { projected.put("charging", it) }
                    raw.opt("unlocked")?.takeIf { it is Boolean }?.let { projected.put("unlocked", it) }
                }
                Kind.CONDITION_CHECK.wire -> {
                    projected.put("type", type)
                    raw.opt("satisfied")?.takeIf { it is Boolean }?.let { projected.put("satisfied", it) }
                    addSafeConditions(projected, raw.optJSONObject("state") ?: raw)
                }
                Kind.RUN_STARTED.wire -> {
                    projected.put("type", type)
                    val source = safeSource(raw.optString("source"))
                    projected.put("source", source)
                    val planned = safeTimestamp(raw, "plannedAtEpochMs")
                    val actual = safeTimestamp(raw, "actualAtEpochMs")
                    if (source == "time") planned?.let { projected.put("plannedAtEpochMs", it) }
                    actual?.let { projected.put("actualAtEpochMs", it) }
                    val regression = source == "time" && planned != null && actual != null && actual < planned
                    projected.put("clockRegression", regression)
                    if (source == "time" && planned != null && actual != null && !regression) {
                        projected.put("delayMs", actual - planned)
                    }
                    addRunCapabilities(projected, raw)
                }
                Kind.CAPABILITY_SNAPSHOT.wire -> {
                    projected.put("type", type)
                    addRunCapabilities(projected, raw)
                }
                Kind.RUN_BUSY.wire, Kind.RUN_COMPLETED.wire, Kind.RUN_CANCELLED.wire,
                Kind.CANCEL_REQUESTED.wire, Kind.RUN_FAILED.wire -> {
                    projected.put("type", type)
                    raw.optString("status").takeIf { it in safeRunStatuses }?.let { projected.put("status", it) }
                    projected.put("code", canonicalErrorCode(raw.optString("code")))
                }
                Kind.TOOL_STARTED.wire, Kind.TOOL_FINISHED.wire -> {
                    projected.put("type", type)
                    projected.put("tool", canonicalToolName(raw.optString("tool")))
                    raw.opt("success")?.takeIf { it is Boolean }?.let { projected.put("success", it) }
                    if (raw.has("code")) projected.put("code", canonicalErrorCode(raw.optString("code")))
                }
                Kind.DISPLAY_FAILURE.wire -> {
                    val reason = raw.optString("reason")
                    if (reason !in DisplayFailure.entries.map { it.name }) {
                        omitted = true
                        continue
                    }
                    projected.put("type", type).put("reason", reason)
                    omitted = addDisplayFailureMetadata(projected, raw) || omitted
                }
                else -> {
                    omitted = true
                    continue
                }
            }
            output.put(projected)
        }
        return ProjectedEventLog(output, omitted)
    }

    private fun addDisplayFailureMetadata(projected: JSONObject, raw: JSONObject): Boolean {
        var omitted = false
        // Derive this closed allowlist from the diagnostic enums so additive IME branches remain safe.
        val enumFields = listOf(
            "stage" to AutomationDisplayFailureDetail.Stage.entries.map { it.name }.toSet(),
            "code" to AutomationDisplayFailureDetail.Code.entries.map { it.name }.toSet(),
            "exceptionKind" to AutomationDisplayFailureDetail.ExceptionKind.entries.map { it.name }.toSet(),
            "causeKind" to AutomationDisplayFailureDetail.ExceptionKind.entries.map { it.name }.toSet(),
            "branch" to AutomationDisplayFailureDetail.Branch.entries.map { it.name }.toSet(),
        )
        enumFields.forEach { (name, allowed) ->
            if (!raw.has(name) || raw.isNull(name)) return@forEach
            val value = raw.optString(name)
            if (value in allowed) projected.put(name, value) else omitted = true
        }
        listOf("sdkInt" to (1..100), "processUid" to (0..2_000_000)).forEach { (name, range) ->
            if (!raw.has(name) || raw.isNull(name)) return@forEach
            val value = raw.optInt(name, Int.MIN_VALUE)
            if (value in range) projected.put(name, value) else omitted = true
        }
        return omitted
    }

    private fun addRunCapabilities(projected: JSONObject, raw: JSONObject) {
        raw.optString("rootStatus").let { projected.put("rootStatus", safeRootStatus(it)) }
        raw.opt("accessibilityAvailable")?.takeIf { it is Boolean }
            ?.let { projected.put("accessibilityAvailable", it) }
        raw.opt("virtualDisplayEnabled")?.takeIf { it is Boolean }
            ?.let { projected.put("virtualDisplayEnabled", it) }
        raw.opt("virtualDisplayActive")?.takeIf { it is Boolean }
            ?.let { projected.put("virtualDisplayActive", it) }
        addSafeConditions(projected, raw.optJSONObject("conditions") ?: JSONObject())
        SAFE_TOGGLE_NAMES.forEach { name ->
            raw.opt(name)?.takeIf { it is Boolean }?.let { projected.put(name, it) }
        }
    }

    private fun sanitizeTriggerProjection(raw: JSONObject): JSONObject {
        val type = raw.optString("type").takeIf { it in AgentTaskRules.timeTypes || it in AgentTaskRules.eventTypes }
            ?: return JSONObject().put("type", "unknown")
        val projected = JSONObject().put("type", type)
        when (type) {
            "once" -> safeTimestamp(raw, "atEpochMs")?.let { projected.put("atEpochMs", it) }
            "interval" -> {
                raw.optInt("seconds", -1).takeIf { it in 900..31_536_000 }
                    ?.let { projected.put("seconds", it) }
                safeTimestamp(raw, "startAtEpochMs")?.let { projected.put("startAtEpochMs", it) }
            }
            "daily" -> {
                raw.optString("time").takeIf { Regex("[0-2][0-9]:[0-5][0-9]").matches(it) }
                    ?.let { time -> runCatching { LocalTime.parse(time) }.getOrNull()?.let { projected.put("time", it.toString()) } }
                raw.optString("timeZone").takeIf { it in ZoneId.getAvailableZoneIds() || it == "UTC" }
                    ?.let { projected.put("timeZone", it) }
                raw.optJSONArray("weekdays")?.let { days ->
                    val safeDays = JSONArray()
                    for (index in 0 until days.length()) {
                        val day = days.optInt(index, -1)
                        if (day in 1..7) safeDays.put(day)
                    }
                    if (safeDays.length() > 0) projected.put("weekdays", safeDays)
                }
            }
        }
        val conditions = raw.optJSONObject("conditions") ?: JSONObject()
        addSafeConditions(projected, conditions)
        return projected
    }

    private fun addSafeConditions(target: JSONObject, source: JSONObject) {
        val conditions = JSONObject()
        safeConditionNames.sorted().forEach { name ->
            source.opt(name)?.takeIf { it is Boolean }?.let { conditions.put(name, it) }
        }
        if (conditions.length() > 0) target.put("conditions", conditions)
    }

    private fun safeTimestamp(source: JSONObject, key: String): Long? =
        source.optLong(key, -1).takeIf { it >= 0 }

    private fun safeSource(value: String): String =
        value.takeIf { it in setOf("time", "manual", "event", "unknown") } ?: "unknown"

    private val HASH_PATTERN = Regex("[0-9a-f]{64}")

    private fun manifest(
        context: Context,
        taskKey: String,
        hasLog: Boolean,
        eventCount: Int,
        truncated: Boolean,
        hasMoreRuns: Boolean,
        fullCaptureRequested: Boolean,
        fullSnapshot: AgentTaskFullCapture.ExportSnapshot?,
    ): JSONObject {
        val zone = runCatching { ZoneId.systemDefault().id }
            .getOrNull()?.takeIf { it in ZoneId.getAvailableZoneIds() || it == "UTC" } ?: "unknown"
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("appVersionName", BuildConfig.VERSION_NAME.take(64))
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("exportedAtEpochMs", System.currentTimeMillis())
            .put("timeZone", zone)
            .put("taskKey", taskKey)
            .put("diagnosticLogPresent", hasLog)
            .put("retainedEventCount", eventCount)
            .put("truncated", truncated)
            .put("retentionTruncated", truncated)
            .put("historicalEventsAvailable", false)
            .put("coverageStatus", when {
                !hasLog -> "historical_unavailable_no_retained_log"
                truncated -> "bounded_truncated"
                else -> "retained_log_present_best_effort"
            })
            .put("eventCoverage", "best_effort_bounded; pre-installation events and evicted/missing logs are unavailable")
            .put("runHistoryTruncated", hasMoreRuns)
            .put("fullCaptureRequested", fullCaptureRequested)
            .put("fullCaptureStatus", fullSnapshot?.status ?: if (fullCaptureRequested) "unavailable" else "not_requested")
            .put("fullCaptureIncluded", fullSnapshot?.status == "included" || fullSnapshot?.status == "no_retained_log")
            .put("fullCaptureGeneration", fullSnapshot?.generation ?: JSONObject.NULL)
            .put("fullCaptureSnapshotCannotBeRevoked", fullSnapshot?.status == "included")
            .put("fullCaptureLimitation", "A capture toggle after the snapshot cannot retract an export already being written.")
            .put("omissionsPossible", true)
            .put("absenceMeansNoIssue", false)
            .put("limitations", JSONArray(listOf(
                "Detailed events exist only for the retained period after diagnostics became available.",
                "A missing event does not prove that the event or failure did not occur.",
                "Retention is bounded per task and globally; older records may have been removed.",
                "No Doze or sleep cause is inferred from an absent event or heartbeat.",
                "Task names, prompts, model responses, tool arguments/results, notifications, package names, credentials, screenshots and arbitrary exception messages are excluded.",
            )))
    }

    private suspend fun awaitPendingWrites() = withContext(Dispatchers.IO) {
        val completed = java.util.concurrent.CountDownLatch(1)
        try {
            writes.execute { completed.countDown() }
            completed.await(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Export still carries omissionsPossible=true if a best-effort barrier cannot complete.
        }
    }

    private fun writeZip(
        output: OutputStream,
        entries: List<Pair<String, String>>,
        maxBytes: Int = MAX_EXPORT_BYTES,
    ) {
        var written = 0L
        val bounded = object : FilterOutputStream(output) {
            override fun write(value: Int) {
                if (++written > maxBytes) throw IOException("Diagnostic export exceeds size limit")
                out.write(value)
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (length < 0 || written + length > maxBytes) {
                    throw IOException("Diagnostic export exceeds size limit")
                }
                written += length
                out.write(buffer, offset, length)
            }

            override fun close() { flush() }
        }
        ZipOutputStream(BufferedOutputStream(bounded)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.finish()
        }
        output.flush()
    }

    internal fun taskLogFileName(taskId: String): String = hashIdentifier(taskId) + FILE_SUFFIX

    internal fun clearForTests(context: Context) {
        writes.submit {}.get(5, TimeUnit.SECONDS)
        synchronized(stateLock) {
            context.applicationContext.getDir(DIRECTORY, Context.MODE_PRIVATE).listFiles().orEmpty()
                .filter {
                    it.isFile && (
                        it.name.endsWith(FILE_SUFFIX) || it.name.endsWith("$FILE_SUFFIX.bak") ||
                            it.name.endsWith("$FILE_SUFFIX.new")
                        )
                }
                .forEach { it.delete() }
        }
    }

    internal fun retainedFilesForTests(context: Context): List<java.io.File> =
        context.applicationContext.getDir(DIRECTORY, Context.MODE_PRIVATE)
            .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(FILE_SUFFIX) }
            .orEmpty().toList()

}
