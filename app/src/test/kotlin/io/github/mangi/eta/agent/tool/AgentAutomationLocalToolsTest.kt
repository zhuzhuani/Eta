package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentAutomationLocalToolsTest {
    @Test fun refusesGuiWithoutVirtualDisplayPermission() {
        tools(virtual = false, root = true).use {
            assertEquals("AUTOMATION_VIRTUAL_DISPLAY_DISABLED", call(it, "observe_screen").getString("code"))
        }
    }

    @Test fun refusesGuiWithoutRootRatherThanObservingPhysicalScreen() {
        tools(virtual = true, root = false).use {
            assertEquals("AUTOMATION_ROOT_REQUIRED", call(it, "observe_screen").getString("code"))
        }
    }

    @Test @Config(sdk = [33]) fun android13NeverFallsBackToPhysicalScreen() {
        tools(virtual = true, root = true).use {
            assertEquals("AUTOMATION_DISPLAY_UNSUPPORTED", call(it, "observe_screen").getString("code"))
        }
    }

    @Test fun blocksMainDisplayAndGlobalActionsBeforeAnyOperation() {
        tools(virtual = true, root = true).use {
            listOf(
                "launch_app" to """{"package_name":"example.app","display":"main"}""",
                "press_key" to """{"button":"NOTIFICATIONS"}""",
                "press_key" to """{"button":"HOME"}""",
                "open_system_panel" to """{"panel":"notifications"}""",
                "present_to_user" to "{}",
            ).forEach { (name, args) ->
                assertEquals(name, "AUTOMATION_FOREGROUND_UNAVAILABLE", call(it, name, args).getString("code"))
            }
        }
    }

    @Test fun taskChainingRemainsBlockedEvenWithoutRuntimeCallback() {
        tools(virtual = true, root = false).use {
            assertEquals("AUTOMATION_TASK_TOOL_UNAVAILABLE", call(it, "tasks_run", """{"id":"another"}""").getString("code"))
        }
    }

    @Test fun foregroundIntentFallbackNeverStartsAnActivity() {
        tools(virtual = true, root = false).use { tools ->
            val method = AgentLocalTools::class.java.getDeclaredMethod(
                "executeForegroundIntent", String::class.java, kotlin.jvm.functions.Function0::class.java,
            ).apply { isAccessible = true }
            val result = method.invoke(tools, "set_timer", { error("Must not launch physical UI") }) as JSONObject
            assertEquals("AUTOMATION_FOREGROUND_UNAVAILABLE", result.getString("code"))
        }
    }

    private fun call(tools: AgentLocalTools, name: String, args: String = "{}") =
        JSONObject(tools.execute(AgentModelClient.ToolCall(name, name, args)).content)

    private fun tools(virtual: Boolean, root: Boolean) = AgentLocalTools(
        context = RuntimeEnvironment.getApplication(), logger = NoOpLogger,
        automation = true, rootAvailable = { root }, virtualDisplayEnabled = { virtual },
        isToolEnabled = { true }, deviceDirectToolsEnabled = { true },
        screenObservationProvider = { error("Must never observe physical display on failed automation setup") },
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
