package io.github.mangi.eta.ui.screens.tasks

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.automation.AgentTaskDiagnostics
import io.github.mangi.eta.agent.automation.AgentTaskScheduler
import io.github.mangi.eta.agent.automation.AgentTaskTools
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import java.time.DayOfWeek
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val taskDiagnosticsExportMutex = Mutex()
private val taskDiagnosticsCaptureSettingsMutex = Mutex()

@Composable
private fun TaskPreferenceGroup(content: @Composable () -> Unit) {
    EtaPreferenceGroupItem(isFirst = true, isLast = true, content = content)
}

@Composable
internal fun AgentTasksScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val dao = remember { EtaDatabase.get(context).agentTaskDao() }
    val tasks by dao.observeTasks().collectAsState(initial = emptyList())
    val runs by dao.observeRuns().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var triggers by remember { mutableStateOf<List<TaskTriggerUi>>(emptyList()) }
    var selectedTrigger by remember { mutableStateOf<TaskTriggerUi?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notice by remember { mutableStateOf<String?>(null) }
    var openedTaskId by remember { mutableStateOf<String?>(null) }
    var pendingExportTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportIncludeSensitive by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var pendingExportUri by rememberSaveable { mutableStateOf<String?>(null) }
    var exportStatusId by rememberSaveable { mutableStateOf<Int?>(null) }
    var captureSettingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullCaptureEnabled by rememberSaveable { mutableStateOf(true) }
    var fullCaptureSettingLoaded by rememberSaveable { mutableStateOf(false) }
    var fullCaptureSettingSaving by rememberSaveable { mutableStateOf(false) }
    var fullCaptureSettingLoadFailed by rememberSaveable { mutableStateOf(false) }
    var fullCaptureSettingSaveFailed by rememberSaveable { mutableStateOf(false) }
    var fullCaptureCleanupPending by rememberSaveable { mutableStateOf(false) }
    var captureSettingOperationId by remember { mutableStateOf(0) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null || pendingExportTaskId == null || pendingExportIncludeSensitive == null) {
            pendingExportTaskId = null
            pendingExportIncludeSensitive = null
            pendingExportUri = null
            exportStatusId = R.string.automation_export_cancelled
        } else {
            pendingExportUri = uri.toString()
            exportStatusId = R.string.automation_export_running
        }
    }
    LaunchedEffect(pendingExportTaskId, pendingExportIncludeSensitive, pendingExportUri) {
        val taskId = pendingExportTaskId
        val includeSensitive = pendingExportIncludeSensitive
        val uriText = pendingExportUri
        if (taskId != null && includeSensitive != null && uriText != null) {
            val exported = try {
                taskDiagnosticsExportMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        val output = context.contentResolver.openOutputStream(Uri.parse(uriText), "wt")
                            ?: error("Unable to open destination")
                        output.use {
                            AgentTaskDiagnostics.export(context, taskId, it, includeSensitive = includeSensitive)
                        }
                    }
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            exportStatusId = if (exported) {
                R.string.automation_export_succeeded
            } else {
                R.string.automation_export_failed
            }
            pendingExportTaskId = null
            pendingExportIncludeSensitive = null
            pendingExportUri = null
        }
    }
    fun requestExport(taskId: String, includeSensitive: Boolean) {
        if (pendingExportTaskId != null) return
        val selection = snapshotTaskDiagnosticsExportSelection(taskId, includeSensitive)
        pendingExportTaskId = selection.taskId
        pendingExportIncludeSensitive = selection.includeSensitive
        exportStatusId = R.string.automation_export_picking
        runCatching { exportLauncher.launch("eta-task-diagnostics.zip") }
            .onFailure {
                pendingExportTaskId = null
                pendingExportIncludeSensitive = null
                pendingExportUri = null
                exportStatusId = R.string.automation_export_failed
            }
    }
    LaunchedEffect(openedTaskId) {
        val taskId = openedTaskId ?: return@LaunchedEffect
        val operationId = captureSettingOperationId + 1
        captureSettingOperationId = operationId
        captureSettingTaskId = taskId
        fullCaptureSettingLoaded = false
        fullCaptureSettingLoadFailed = false
        fullCaptureEnabled = false
        fullCaptureSettingSaving = false
        fullCaptureSettingSaveFailed = false
        fullCaptureCleanupPending = false
        val storedSetting = try {
            withContext(Dispatchers.IO) {
                taskDiagnosticsCaptureSettingsMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    AgentTaskDiagnostics.isFullCaptureEnabled(context, taskId)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (openedTaskId == taskId && captureSettingOperationId == operationId) {
            if (storedSetting == null) {
                fullCaptureSettingLoadFailed = true
            } else {
                fullCaptureEnabled = storedSetting
                fullCaptureSettingLoaded = true
            }
        }
    }
    fun updateFullCapture(taskId: String, enabled: Boolean) {
        if (captureSettingTaskId != taskId || !fullCaptureSettingLoaded || fullCaptureSettingSaving) return
        val operationId = captureSettingOperationId + 1
        captureSettingOperationId = operationId
        fullCaptureSettingSaving = true
        fullCaptureSettingLoadFailed = false
        fullCaptureSettingSaveFailed = false
        fullCaptureCleanupPending = false
        scope.launch {
            val update = try {
                Result.success(
                    withContext(Dispatchers.IO) {
                        taskDiagnosticsCaptureSettingsMutex.withLock {
                            currentCoroutineContext().ensureActive()
                            val cleanupComplete = AgentTaskDiagnostics.setFullCaptureEnabled(context, taskId, enabled)
                            val storedSetting = AgentTaskDiagnostics.isFullCaptureEnabled(context, taskId)
                            cleanupComplete to storedSetting
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            if (openedTaskId != taskId || captureSettingOperationId != operationId) return@launch
            fullCaptureSettingSaving = false
            update.fold(
                onSuccess = { (cleanupComplete, storedSetting) ->
                    fullCaptureEnabled = storedSetting
                    fullCaptureCleanupPending = !cleanupComplete && !storedSetting
                    fullCaptureSettingSaveFailed = storedSetting != enabled || (!cleanupComplete && storedSetting)
                },
                onFailure = { fullCaptureSettingSaveFailed = true },
            )
        }
    }
    fun execute(tool: String, args: JSONObject, onSuccess: (() -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val result = JSONObject(AgentTaskTools(context).execute(tool, args))
            withContext(Dispatchers.Main) {
                if (result.optBoolean("ok")) {
                    notice = null
                    onSuccess?.invoke()
                } else {
                    notice = result.optString("message")
                }
            }
        }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            triggers = withContext(Dispatchers.IO) {
                AgentTaskScheduler.refresh(context)
                loadTaskTriggers(context)
            }
            awaitCancellation()
        }
    }
    val openedTask = openedTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
    if (openedTask != null) {
        AgentTaskDetailPage(
            context = context,
            task = openedTask,
            recentRuns = runs.filter { it.taskId == openedTask.id }.take(3),
            notice = notice,
            fullCaptureEnabled = fullCaptureEnabled,
            fullCaptureSettingReady = captureSettingTaskId == openedTask.id && fullCaptureSettingLoaded,
            fullCaptureSettingLoadFailed = fullCaptureSettingLoadFailed,
            fullCaptureSettingSaving = fullCaptureSettingSaving,
            fullCaptureSettingSaveFailed = fullCaptureSettingSaveFailed,
            fullCaptureCleanupPending = fullCaptureCleanupPending,
            onFullCaptureChange = { enabled -> updateFullCapture(openedTask.id, enabled) },
            exportStatusId = exportStatusId,
            pendingExportIncludeSensitive = pendingExportIncludeSensitive,
            isExportPending = pendingExportTaskId != null,
            onExport = { requestExport(openedTask.id, fullCaptureEnabled) },
            onBack = {
                captureSettingOperationId += 1
                openedTaskId = null
            },
            onEnabledChange = { enabled ->
                execute(
                    "tasks_update",
                    JSONObject().put("taskId", openedTask.id)
                        .put("expectedUpdatedAt", openedTask.updatedAt).put("enabled", enabled),
                )
            },
            onRun = { execute("tasks_run", JSONObject().put("taskId", openedTask.id)) },
            onCancel = { execute("tasks_cancel", JSONObject().put("taskId", openedTask.id)) },
            onDelete = {
                execute("tasks_delete", JSONObject().put("taskId", openedTask.id)) {
                    captureSettingOperationId += 1
                    openedTaskId = null
                }
            },
        )
    } else {
        MiuixScaffoldPage(
            title = stringResource(R.string.automation_title),
            onBack = onBack,
        ) {
            item {
                TaskPreferenceGroup {
                    ArrowPreference(
                        title = stringResource(R.string.automation_create_hint),
                        summary = stringResource(R.string.automation_create_summary),
                    )
                }
            }
            item(key = "trigger-catalog-heading") {
                TriggerCatalogHeading(triggers)
            }
            TaskTriggerGroup.entries.forEach { group ->
                val groupTriggers = triggers.filter { it.group == group }
                if (groupTriggers.isNotEmpty()) item(key = "trigger-group-" + group.name) {
                    TriggerCatalogGroup(group, groupTriggers) { selectedTrigger = it }
                }
            }
            notice?.let { message ->
                item(key = "tasks-notice") {
                    Text(
                        text = message,
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 16.dp),
                    )
                }
            }
            items(tasks, key = { it.id }) { task ->
                val rule = remember(task.triggerJson) { JSONObject(task.triggerJson) }
                TaskPreferenceGroup {
                    ArrowPreference(
                        title = task.name,
                        summary = triggerSummary(context, rule),
                        onClick = { openedTaskId = task.id },
                        endActions = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = task.enabled,
                                    onCheckedChange = { enabled ->
                                        execute(
                                            "tasks_update",
                                            JSONObject().put("taskId", task.id)
                                                .put("expectedUpdatedAt", task.updatedAt)
                                                .put("enabled", enabled),
                                        )
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = MiuixIcons.Basic.ArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(8.dp, 14.dp),
                                    tint = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                )
                            }
                        },
                    )
                }
            }
        }
        selectedTrigger?.let { trigger -> TriggerDetails(trigger) { selectedTrigger = null } }
    }
}

@Composable
private fun AgentTaskDetailPage(
    context: Context,
    task: AgentTaskEntity,
    recentRuns: List<AgentTaskRunEntity>,
    notice: String?,
    fullCaptureEnabled: Boolean,
    fullCaptureSettingReady: Boolean,
    fullCaptureSettingLoadFailed: Boolean,
    fullCaptureSettingSaving: Boolean,
    fullCaptureSettingSaveFailed: Boolean,
    fullCaptureCleanupPending: Boolean,
    onFullCaptureChange: (Boolean) -> Unit,
    exportStatusId: Int?,
    pendingExportIncludeSensitive: Boolean?,
    isExportPending: Boolean,
    onExport: () -> Unit,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val rule = remember(task.triggerJson) { JSONObject(task.triggerJson) }
    val colors = MiuixTheme.colorScheme
    val statusColor = when (task.lastStatus) {
        "completed" -> MiuixTheme.colorScheme.primary
        "failed", "interrupted" -> colors.error
        "queued", "running" -> colors.primary
        "configuration_required" -> MiuixTheme.colorScheme.primary
        else -> colors.onSurfaceVariantSummary
    }
    MiuixScaffoldPage(title = task.name, onBack = onBack, compactTitle = true) {
        item(key = "task-switch") {
            TaskPreferenceGroup {
                SwitchPreference(
                    title = stringResource(R.string.automation_task_enabled),
                    summary = stringResource(R.string.automation_task_enabled_summary),
                    checked = task.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
        }
        notice?.let { message ->
            item(key = "task-notice") {
                Text(
                    text = message,
                    color = colors.error,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 16.dp),
                )
            }
        }
        item(key = "task-status") {
            TaskPreferenceGroup {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = statusLabel(context, task.lastStatus),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                        color = statusColor,
                        modifier = Modifier.weight(1f, fill = false)
                            .background(statusColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Text(
                        text = stringResource(R.string.automation_run_count, task.runCount, task.maxRuns),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        item(key = "task-prompt") {
            TaskPreferenceGroup {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.automation_task_prompt_label),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                    Text(text = task.prompt, style = MiuixTheme.textStyles.body2)
                }
            }
        }
        item(key = "task-trigger") {
            TaskPreferenceGroup {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                        .background(colors.onBackground.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TaskDetail(stringResource(R.string.automation_trigger_label), triggerSummary(context, rule))
                    triggerFilters(context, rule).takeIf { it.isNotBlank() }?.let {
                        TaskDetail(stringResource(R.string.automation_filters_label), it)
                    }
                    triggerConditions(context, rule).takeIf { it.isNotBlank() }?.let {
                        TaskDetail(stringResource(R.string.automation_conditions_label), it)
                    }
                    task.nextRunAt?.let {
                        TaskDetail(stringResource(R.string.automation_next_run_label), formatTime(context, it))
                    }
                }
            }
        }
        if (recentRuns.isNotEmpty()) {
            item(key = "task-runs") {
                TaskPreferenceGroup {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.automation_recent_runs_label),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                        )
                        recentRuns.forEach { run ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = statusLabel(context, run.status) + " · " +
                                        formatTime(context, run.startedAt ?: run.queuedAt),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = colors.onSurfaceVariantSummary,
                                )
                                if (run.resultPreview.isNotBlank()) {
                                    Text(
                                        text = run.resultPreview.take(300),
                                        style = MiuixTheme.textStyles.body2,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "task-full-capture") {
            TaskPreferenceGroup {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.automation_export_full_capture_enabled),
                        summary = stringResource(R.string.automation_export_full_capture_summary),
                        checked = fullCaptureEnabled,
                        enabled = fullCaptureSettingReady && !fullCaptureSettingSaving && !isExportPending,
                        onCheckedChange = onFullCaptureChange,
                    )
                    if (fullCaptureSettingLoadFailed) {
                        Text(
                            text = stringResource(R.string.automation_export_full_capture_load_failed),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.error,
                        )
                    } else {
                        Text(
                            text = stringResource(
                                when {
                                    !fullCaptureSettingReady -> R.string.automation_export_full_capture_loading
                                    fullCaptureEnabled -> R.string.automation_export_full_capture_warning
                                    else -> R.string.automation_export_privacy
                                },
                            ),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    if (fullCaptureSettingSaveFailed) {
                        Text(
                            text = stringResource(R.string.automation_export_full_capture_save_failed),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.error,
                        )
                    }
                    if (fullCaptureCleanupPending) {
                        Text(
                            text = stringResource(R.string.automation_export_full_capture_cleanup_pending),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.error,
                        )
                    }
                }
            }
        }
        item(key = "task-export") {
            TaskPreferenceGroup {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = stringResource(
                            when {
                                isExportPending && pendingExportIncludeSensitive == true ->
                                    R.string.automation_export_diagnostics_full
                                isExportPending && pendingExportIncludeSensitive == false ->
                                    R.string.automation_export_diagnostics_redacted
                                fullCaptureEnabled -> R.string.automation_export_diagnostics_full
                                else -> R.string.automation_export_diagnostics_redacted
                            },
                        ),
                        onClick = onExport,
                        enabled = !isExportPending && fullCaptureSettingReady && !fullCaptureSettingSaving,
                        modifier = Modifier.fillMaxWidth(),
                        minHeight = 48.dp,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        textStyle = MiuixTheme.textStyles.body2,
                    )
                    exportStatusId?.let { statusId ->
                        Text(
                            text = stringResource(statusId),
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (statusId == R.string.automation_export_failed) colors.error
                                else colors.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        item(key = "task-actions") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            ) {
                TaskActions(onRun = onRun, onCancel = onCancel, onDelete = onDelete)
            }
        }
    }
}

@Composable
private fun TaskDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(text = value, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun TaskActions(onRun: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            text = stringResource(R.string.automation_run),
            onClick = onRun,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 48.dp,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            textStyle = MiuixTheme.textStyles.body2,
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.automation_cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                minWidth = 0.dp,
                minHeight = 48.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                textStyle = MiuixTheme.textStyles.body2,
            )
            TextButton(
                text = stringResource(R.string.automation_delete),
                onClick = onDelete,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                minWidth = 0.dp,
                minHeight = 48.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error.copy(alpha = 0.1f),
                    textColor = MiuixTheme.colorScheme.error,
                ),
                textStyle = MiuixTheme.textStyles.body2,
            )
        }
    }
}

private fun triggerSummary(context: Context, rule: JSONObject): String =
    when (rule.optString("type")) {
        "once" -> runCatching {
            formatTime(context, OffsetDateTime.parse(rule.optString("at")).toInstant().toEpochMilli())
        }.getOrDefault(rule.optString("at"))
        "daily" -> {
            val weekdays = rule.optJSONArray("weekdays")
            val days = if (weekdays == null) emptyList() else {
                (0 until weekdays.length()).mapNotNull { index ->
                    weekdays.optInt(index).takeIf { it in 1..7 }?.let {
                        DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, context.resources.configuration.locales[0])
                    }
                }
            }
            context.getString(R.string.automation_daily, rule.optString("time"), rule.optString("timeZone")) +
                if (days.isEmpty()) "" else " · ${days.joinToString(", ")}"
        }
        "interval" -> context.getString(R.string.automation_interval, rule.optLong("seconds") / 60)
        else -> eventLabel(context, rule.optString("type"))
    }

internal fun eventLabel(context: Context, type: String): String {
    val label = when (type) {
        "notification_posted" -> R.string.automation_event_notification_posted
        "notification_removed" -> R.string.automation_event_notification_removed
        "app_foreground" -> R.string.automation_event_app_foreground
        "device_unlocked" -> R.string.automation_event_device_unlocked
        "screen_on" -> R.string.automation_event_screen_on
        "screen_off" -> R.string.automation_event_screen_off
        "charging_connected" -> R.string.automation_event_charging_connected
        "charging_disconnected" -> R.string.automation_event_charging_disconnected
        "battery_low" -> R.string.automation_event_battery_low
        "battery_okay" -> R.string.automation_event_battery_okay
        "network_connected" -> R.string.automation_event_network_connected
        "network_disconnected" -> R.string.automation_event_network_disconnected
        "bluetooth_connected" -> R.string.automation_event_bluetooth_connected
        "bluetooth_disconnected" -> R.string.automation_event_bluetooth_disconnected
        "headset_connected" -> R.string.automation_event_headset_connected
        "headset_disconnected" -> R.string.automation_event_headset_disconnected
        "device_boot" -> R.string.automation_event_device_boot
        "memory_updated" -> R.string.automation_event_memory_updated
        "task_completed" -> R.string.automation_event_task_completed
        "task_failed" -> R.string.automation_event_task_failed
        else -> return context.getString(R.string.automation_event, type)
    }
    return context.getString(label)
}

private fun triggerFilters(context: Context, rule: JSONObject): String {
    val filters = rule.optJSONObject("filters") ?: return ""
    return listOf(
        "packageName" to R.string.automation_filter_app,
        "titleContains" to R.string.automation_filter_title,
        "textContains" to R.string.automation_filter_text,
        "taskId" to R.string.automation_filter_task,
        "transport" to R.string.automation_filter_transport,
    ).mapNotNull { (key, label) ->
        filters.optString(key).takeIf { it.isNotBlank() }?.let { context.getString(label, it) }
    }.joinToString("\n")
}

private fun triggerConditions(context: Context, rule: JSONObject): String {
    val conditions = rule.optJSONObject("conditions") ?: return ""
    return listOf(
        Triple("charging", R.string.automation_condition_charging, R.string.automation_condition_not_charging),
        Triple("unlocked", R.string.automation_condition_unlocked, R.string.automation_condition_locked),
        Triple("networkConnected", R.string.automation_condition_online, R.string.automation_condition_offline),
    ).mapNotNull { (key, yes, no) ->
        if (conditions.has(key)) context.getString(if (conditions.optBoolean(key)) yes else no) else null
    }.joinToString(" · ")
}

private fun formatTime(context: Context, time: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(context.resources.configuration.locales[0])
        .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

private fun statusLabel(context: Context, status: String): String =
    context.getString(
        when (status) {
            "ready" -> R.string.automation_ready
            "queued" -> R.string.automation_queued
            "running" -> R.string.automation_running
            "completed" -> R.string.automation_completed
            "failed" -> R.string.automation_failed
            "cancelled" -> R.string.automation_cancelled
            "interrupted" -> R.string.automation_interrupted
            "configuration_required" -> R.string.automation_config_required
            else -> R.string.automation_ready
        }
    )
