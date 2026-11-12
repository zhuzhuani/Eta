package io.github.mangi.eta.ui.screens.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDiagnosticsExportSelectionTest {
    @Test
    fun selectionSnapshotsTaskAndFullCaptureModeAtRequestTime() {
        val taskId = "task-a"
        var fullCaptureEnabled = true
        val fullRequest = snapshotTaskDiagnosticsExportSelection(taskId, fullCaptureEnabled)

        fullCaptureEnabled = false
        assertEquals("task-a", fullRequest.taskId)
        assertTrue(fullRequest.includeSensitive)

        val redactedRequest = snapshotTaskDiagnosticsExportSelection("task-b", fullCaptureEnabled)
        assertEquals("task-b", redactedRequest.taskId)
        assertFalse(redactedRequest.includeSensitive)
    }
}
