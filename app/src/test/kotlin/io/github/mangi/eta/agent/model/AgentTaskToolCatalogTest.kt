package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.automation.AgentTaskRules
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AgentTaskToolCatalogTest {
    private fun functions() = JSONArray().also(AgentTaskToolCatalog::appendTo).let { tools ->
        (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }.associateBy { it.getString("name") }
    }

    @Test fun publishesEightUniqueClosedSchemas() {
        val functions = functions()
        assertEquals(setOf("tasks_triggers", "tasks_list", "tasks_create", "tasks_update", "tasks_delete", "tasks_run", "tasks_cancel", "tasks_history"), functions.keys)
        functions.values.forEach {
            val schema = it.getJSONObject("parameters")
            assertFalse(schema.getBoolean("additionalProperties"))
            assertTrue(schema.has("required"))
        }
    }

    @Test fun triggerSchemaMatchesRuleEngineAndBounds() {
        val fields = functions().getValue("tasks_create").getJSONObject("parameters").getJSONObject("properties")
        val trigger = fields.getJSONObject("trigger").getJSONObject("properties")
        val types = trigger.getJSONObject("type").getJSONArray("enum")
        assertEquals(AgentTaskRules.timeTypes + AgentTaskRules.eventTypes, (0 until types.length()).map(types::getString).toSet())
        assertEquals(900, trigger.getJSONObject("seconds").getInt("minimum"))
        assertEquals(10000, fields.getJSONObject("maxRuns").getInt("maximum"))
        assertEquals(8000, fields.getJSONObject("prompt").getInt("maxLength"))
    }

    @Test fun updatesRequireOptimisticRevision() {
        val required = functions().getValue("tasks_update").getJSONObject("parameters").getJSONArray("required")
        assertEquals(setOf("taskId", "expectedUpdatedAt"), (0 until required.length()).map(required::getString).toSet())
    }
}
