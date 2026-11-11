package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.automation.AgentTaskTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.junit.Assert.*
import org.junit.Test

class AgentAutomationToolPolicyTest {
    @Test fun allowsAuditedGuiToolsOnlyThroughAVirtualDisplay() {
        listOf("launch_app", "open_uri", "observe_screen", "tap", "tap_element", "swipe", "type_text", "press_key").forEach {
            assertNull(it, AgentAutomationToolPolicy.rejection(it))
            assertTrue(it, AgentAutomationToolPolicy.requiresVirtualDisplay(it))
        }
        AgentToolRequirements.toolNames.filter {
            AgentToolRequirements.requiresAccessibility(it) || AgentToolRequirements.requiresGuiControl(it)
        }.forEach {
            assertTrue(it, AgentAutomationToolPolicy.requiresVirtualDisplay(it) || AgentAutomationToolPolicy.rejection(it) != null)
        }
        assertNull(AgentAutomationToolPolicy.rejection("browser_use"))
    }

    @Test fun rejectsPhysicalDisplayPresentationAndSystemGlobalUi() {
        listOf("present_to_user", "open_system_panel", "set_alarm", "set_timer").forEach {
            assertEquals(it, "AUTOMATION_FOREGROUND_UNAVAILABLE", AgentAutomationToolPolicy.rejection(it)?.code)
        }
        listOf("HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "POWER").forEach { key ->
            assertNotNull(key, AgentAutomationToolPolicy.argumentRejection("press_key", org.json.JSONObject().put("button", key)))
        }
        listOf("BACK", "ENTER", "PASTE").forEach { key ->
            assertNull(key, AgentAutomationToolPolicy.argumentRejection("press_key", org.json.JSONObject().put("button", key)))
        }
        listOf("main", "physical", "foreground").forEach { display ->
            assertNotNull(AgentAutomationToolPolicy.argumentRejection("launch_app", org.json.JSONObject().put("display", display)))
        }
        assertNull(AgentAutomationToolPolicy.argumentRejection("launch_app", org.json.JSONObject().put("display", "virtual")))
    }

    @Test fun cannotCreateOrChainOtherTasks() {
        AgentTaskTools.names.forEach {
            assertEquals(it, "AUTOMATION_TASK_TOOL_UNAVAILABLE", AgentAutomationToolPolicy.rejection(it)?.code)
        }
    }

    @Test fun preservesNonGuiToolsSubjectToTheirExistingPermissions() {
        listOf("terminal", "read_file", "write_file", "web_search", "fetch_url", "memory_get", "memory_write", "device_status").forEach {
            assertNull(it, AgentAutomationToolPolicy.rejection(it))
        }
    }
}
