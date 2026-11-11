package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.automation.AgentTaskTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.ToolExecutionDecision

/** Automation may use an isolated display, never physical-screen UI or task chaining. */
internal object AgentAutomationToolPolicy {
    private val virtualDisplayTools = setOf(
        "launch_app", "open_uri", "observe_screen", "tap", "tap_area", "tap_element",
        "long_press", "long_press_element", "swipe", "scroll", "scroll_element",
        "type_text", "input_text", "replace_text", "clear_text", "paste_text", "press_key",
        "wait", "wait_for_text", "wait_for_package", "set_clipboard", "get_clipboard",
    )

    fun requiresVirtualDisplay(toolName: String): Boolean = toolName in virtualDisplayTools

    fun rejection(toolName: String): ToolExecutionDecision.Reject? = when {
        toolName in AgentTaskTools.names -> ToolExecutionDecision.Reject(
            "AUTOMATION_TASK_TOOL_UNAVAILABLE", "自动任务不能创建、修改或运行其他自动任务；本次调用未执行",
        )
        // A new GUI tool is denied until its display routing has been audited.
        (AgentToolRequirements.requiresAccessibility(toolName) ||
            AgentToolRequirements.requiresGuiControl(toolName) ||
            AgentToolRequirements.hasForegroundIntentFallback(toolName)) &&
            !requiresVirtualDisplay(toolName) -> foregroundRejection()
        else -> null
    }

    fun argumentRejection(toolName: String, arguments: org.json.JSONObject): ToolExecutionDecision.Reject? = when {
        toolName == "launch_app" &&
            io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy.RequestedDisplay.parse(
                arguments.optString("display", "auto"),
            ) == io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy.RequestedDisplay.MAIN -> foregroundRejection()
        // HOME/RECENTS/statusbar can affect the primary display even with input -d.
        toolName == "press_key" && arguments.optString("button").uppercase(java.util.Locale.ROOT) !in
            setOf("BACK", "ENTER", "PASTE") -> foregroundRejection()
        else -> null
    }

    fun foregroundRejection() = ToolExecutionDecision.Reject(
        "AUTOMATION_FOREGROUND_UNAVAILABLE", "自动任务只能操作独立虚拟屏，不能切回主屏或打开系统全局界面；请从前台完成此操作",
    )
}
