package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.automation.AgentTaskRules
import org.json.JSONArray
import org.json.JSONObject

/** Saved automation is independent of the memory toggle, but unavailable in roleplay. */
internal object AgentTaskToolCatalog {
    fun appendTo(tools: JSONArray) {
        fun text(max: Int) = JSONObject().put("type", "string").put("minLength", 1).put("maxLength", max)
        fun integer(min: Long, max: Long) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
        fun obj(fields: JSONObject) = JSONObject().put("type", "object")
            .put("additionalProperties", false).put("properties", fields)
        fun schema(name: String, description: String, fields: JSONObject = JSONObject(), required: List<String> = emptyList()) {
            tools.put(AgentToolSchema.function(name, description, obj(fields).put("required", JSONArray(required))))
        }
        fun taskId() = JSONObject().put("taskId", text(64))
        val trigger = obj(JSONObject()
            .put("type", JSONObject().put("type", "string").put("enum", JSONArray((AgentTaskRules.timeTypes + AgentTaskRules.eventTypes).toList())))
            .put("at", text(64)).put("startAt", text(64)).put("seconds", integer(900, 31_536_000))
            .put("time", text(5)).put("timeZone", text(100))
            .put("weekdays", JSONObject().put("type", "array").put("items", integer(1, 7)))
            .put("filters", obj(JSONObject().put("packageName", text(200)).put("titleContains", text(200))
                .put("textContains", text(200)).put("taskId", text(64)).put("transport", text(50))))
            .put("conditions", obj(JSONObject().put("charging", JSONObject().put("type", "boolean"))
                .put("unlocked", JSONObject().put("type", "boolean")).put("networkConnected", JSONObject().put("type", "boolean")))))
            .put("required", JSONArray().put("type"))
        val definition = JSONObject().put("name", text(100)).put("prompt", text(8000)).put("trigger", trigger)
            .put("enabled", JSONObject().put("type", "boolean"))
            .put("cooldownSeconds", integer(60, 86400)).put("maxRuns", integer(1, 10000))
        schema("tasks_triggers", "Inspect supported automation triggers, permissions and scheduling limits before creating a task. Android time triggers can be delayed.")
        schema("tasks_list", "List saved assistant tasks, triggers, enabled state, run counts and updatedAt revisions.")
        schema("tasks_create", "Save a task only when the user explicitly requests future scheduling or automation. First inspect tasks_triggers. once uses offset ISO 8601 at; interval seconds >=900; daily uses HH:mm plus IANA timeZone and optional weekdays 1–7. Executions use current model/permissions, cannot operate the main-screen GUI or manage other tasks. Prefer bounded maxRuns; enabled=false saves a draft.", definition, listOf("name", "prompt", "trigger"))
        schema("tasks_update", "Update only requested fields using exact expectedUpdatedAt from tasks_list. Disabling cancels queued and running work; counts/history are preserved.", JSONObject(definition.toString()).put("taskId", text(64)).put("expectedUpdatedAt", integer(0, Long.MAX_VALUE)), listOf("taskId", "expectedUpdatedAt"))
        schema("tasks_delete", "Delete an exact user-selected task, cancel its work and remove its run records.", taskId(), listOf("taskId"))
        schema("tasks_run", "Queue one immediate run when requested, respecting active-run exclusion and maxRuns without resetting the schedule.", taskId(), listOf("taskId"))
        schema("tasks_cancel", "Cancel queued/running executions without deleting the rule or changing its enabled state.", taskId(), listOf("taskId"))
        schema("tasks_history", "Read bounded task execution history. Interrupted operations are never automatically replayed.", taskId().put("limit", integer(1, 50)), listOf("taskId"))
    }
}
