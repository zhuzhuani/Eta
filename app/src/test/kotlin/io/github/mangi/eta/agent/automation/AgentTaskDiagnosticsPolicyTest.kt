package io.github.mangi.eta.agent.automation

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskDiagnosticsPolicyTest {
    @Test
    fun plannedTimeParserAcceptsOnlyBoundedNonnegativeDecimalFireKeys() {
        assertEquals(0L, AgentTaskDiagnostics.plannedTimeFromFireKey("time:0"))
        assertEquals(1234L, AgentTaskDiagnostics.plannedTimeFromFireKey("time:001234"))

        listOf(
            "time:-1",
            "time:+1",
            "time:1.0",
            "time: 1",
            "time:1 ",
            "time:1\n",
            "manual:1",
            "time:12345678901234567",
            "time:10000000000000000",
            "time:9223372036854775808",
            "TIME:1",
        ).forEach { assertNull("must reject $it", AgentTaskDiagnostics.plannedTimeFromFireKey(it)) }
    }

    @Test
    fun timingAttributesManualAndEventSourcesWithoutLeakingFireKeys() {
        assertEquals(
            AgentTaskDiagnostics.Timing("time", 1_000L, 250L, false),
            AgentTaskDiagnostics.timingForFireKey("time:1000", 1_250L),
        )
        assertEquals(
            AgentTaskDiagnostics.Timing("manual", null, null, false),
            AgentTaskDiagnostics.timingForFireKey("manual", 1_250L),
        )
        assertEquals(
            AgentTaskDiagnostics.Timing("manual", null, null, false),
            AgentTaskDiagnostics.timingForFireKey("manual:private-secret", 1_250L),
        )
        assertEquals(
            AgentTaskDiagnostics.Timing("event", null, null, false),
            AgentTaskDiagnostics.timingForFireKey("memory_updated:private-value", 1_250L),
        )
        assertEquals(
            AgentTaskDiagnostics.Timing("unknown", null, null, false),
            AgentTaskDiagnostics.timingForFireKey("unknown:package=com.secret.app", 1_250L),
        )
    }

    @Test
    fun clockRegressionDoesNotEmitNegativeDelay() {
        assertEquals(
            AgentTaskDiagnostics.Timing("time", 5_000L, null, true),
            AgentTaskDiagnostics.timingForFireKey("time:5000", 4_999L),
        )
    }

    @Test
    fun triggerProjectionDropsFiltersPackageNamesAndArbitraryContent() {
        val secrets = listOf(
            "com.private.bank",
            "bearer=secret-token",
            "my private notification body",
        )
        val trigger = JSONObject()
            .put("type", "notification_posted")
            .put(
                "filters",
                JSONObject()
                    .put("packageName", secrets[0])
                    .put("titleContains", secrets[1])
                    .put("textContains", secrets[2]),
            )
        val projected = AgentTaskDiagnostics.projectTrigger(trigger.toString())
        val encoded = projected.toString()

        assertEquals("notification_posted", projected.getString("type"))
        assertFalse(projected.has("filters"))
        assertFalse(projected.has("prompt"))
        secrets.forEach { assertFalse("projection leaked $it", encoded.contains(it)) }
    }

    @Test
    fun malformedOrUnexpectedTriggerFieldsBecomeUnknownWithoutCopyingInput() {
        val sensitive = "token=do-not-retain"
        val invalid = JSONObject()
            .put("type", "not_a_trigger")
            .put("prompt", sensitive)
            .put("packageName", "com.private.app")
            .put("credentials", sensitive)
        val projected = AgentTaskDiagnostics.projectTrigger(invalid.toString())

        assertEquals("unknown", projected.getString("type"))
        assertEquals(1, projected.length())
        assertFalse(projected.toString().contains(sensitive))
        assertEquals("unknown", AgentTaskDiagnostics.projectTrigger("not json").getString("type"))
    }

    @Test
    fun projectConditionsKeepsOnlyBooleanAllowlistedConditions() {
        val projected = AgentTaskDiagnostics.projectConditions(
            JSONObject()
                .put(
                    "conditions",
                    JSONObject()
                        .put("charging", true)
                        .put("unlocked", "credential=secret")
                        .put("networkConnected", false)
                        .put("packageName", "com.private.app"),
                )
                .toString(),
        )

        assertEquals(2, projected.length())
        assertTrue(projected.getBoolean("charging"))
        assertFalse(projected.getBoolean("networkConnected"))
        assertFalse(projected.has("unlocked"))
        assertFalse(projected.has("packageName"))
        assertFalse(projected.toString().contains("credential"))
    }

    @Test
    fun invalidNonBooleanConditionRejectsTheWholeTriggerProjection() {
        val projected = AgentTaskDiagnostics.projectTrigger(
            JSONObject()
                .put("type", "memory_updated")
                .put("conditions", JSONObject().put("charging", "true"))
                .toString(),
        )

        assertEquals("unknown", projected.getString("type"))
        assertEquals(1, projected.length())
        assertFalse(projected.has("conditions"))
    }

    @Test
    fun toolNamesAndErrorCodesAreStrictlyCanonicalized() {
        assertEquals("tasks_list", AgentTaskDiagnostics.canonicalToolName("tasks_list"))
        assertTrue("tasks_list" in AgentToolRequirements.toolNames)
        assertEquals("unknown", AgentTaskDiagnostics.canonicalToolName("shell password=secret"))
        assertEquals("NETWORK_UNAVAILABLE", AgentTaskDiagnostics.canonicalErrorCode("NETWORK_UNAVAILABLE"))
        assertEquals("UNKNOWN", AgentTaskDiagnostics.canonicalErrorCode("MCP_ERROR: api-key-secret"))
        assertEquals("UNKNOWN", AgentTaskDiagnostics.canonicalErrorCode("arbitrary exception text"))
        assertEquals("UNKNOWN", AgentTaskDiagnostics.canonicalErrorCode(null))
    }

    @Test
    fun identifierHashIsStableSha256AndDoesNotExposeSourceIdentifier() {
        val source = "task-id:secret-123"
        val first = AgentTaskDiagnostics.hashIdentifier(source)
        val second = AgentTaskDiagnostics.hashIdentifier(source)

        assertEquals("e2f6d19c6ccf7662095b144d473adf81437ef05dc7531f45f4bd321c8b57cddd", first)
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first.contains(source))
        assertNotEquals(source, first)
    }
}
