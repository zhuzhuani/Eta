package io.github.mangi.eta.ui.screens.tasks

/** Immutable task and privacy-mode snapshot captured when the SAF picker is launched. */
internal data class TaskDiagnosticsExportSelection(
    val taskId: String,
    val includeSensitive: Boolean,
)

internal fun snapshotTaskDiagnosticsExportSelection(
    taskId: String,
    fullCaptureEnabled: Boolean,
): TaskDiagnosticsExportSelection = TaskDiagnosticsExportSelection(
    taskId = taskId,
    includeSensitive = fullCaptureEnabled,
)
