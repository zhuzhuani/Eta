package io.github.mangi.eta.data.db

import androidx.room.Room
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.agent.automation.AgentTaskRules
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTaskDaoTest {
    private lateinit var db: EtaDatabase
    private val task = AgentTaskEntity(
        "task",
        "Test",
        "Read calendar",
        "{\"type\":\"interval\",\"seconds\":3600}",
        true,
        1000,
        60,
        2,
        createdAt = 0,
        updatedAt = 1
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            EtaDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sameFireAndActiveRunCannotExecuteTwice() = runBlocking {
        val dao = db.agentTaskDao()
        dao.insertTask(task)
        val run = AgentTaskRunEntity("run", task.id, "time:1000", queuedAt = 1000)
        assertTrue(dao.enqueue(task, run, 2000))
        assertFalse(dao.enqueue(task, run.copy(id = "duplicate"), 2000))
        assertFalse(
            dao.enqueue(
                task,
                run.copy(id = "other", fireKey = "event:2"),
                2000,
                manual = true
            )
        )
        assertEquals(1, dao.claim(run.id, 1000))
        assertEquals(0, dao.claim(run.id, 1000))
        dao.complete(run, "completed", 2000, "done")
        dao.complete(run, "completed", 3000, "done twice")
        assertEquals(1, dao.task(task.id)!!.runCount)
        assertFalse(
            dao.enqueue(
                dao.task(task.id)!!,
                run.copy(id = "retry", queuedAt = 100_000),
                2000
            )
        )
    }

    @Test
    fun maxRunsDisableAndRestartMarksUncertainWorkWithoutReplay() = runBlocking {
        val dao = db.agentTaskDao()
        dao.insertTask(task.copy(maxRuns = 1))
        val run = AgentTaskRunEntity("run", task.id, "event:1", queuedAt = 1000)
        assertTrue(dao.enqueue(dao.task(task.id)!!, run, null))
        assertEquals(1, dao.claim(run.id, 1000))
        dao.recoverInterrupted(2000)
        assertEquals("interrupted", dao.runs(task.id, 10).single().status)
        assertTrue(dao.queuedRuns().isEmpty())
        assertEquals(1, dao.task(task.id)!!.runCount)
        assertEquals("interrupted", dao.task(task.id)!!.lastStatus)
        assertFalse(dao.task(task.id)!!.enabled)
        assertFalse(
            dao.enqueue(
                dao.task(task.id)!!,
                run.copy(id = "third", fireKey = "event:3", queuedAt = 200_000),
                null,
                manual = true
            )
        )
    }

    @Test
    fun pausedTaskCannotAcceptEventsAndCooldownRejectsBurst() = runBlocking {
        val dao = db.agentTaskDao()
        dao.insertTask(task.copy(enabled = false))
        val run = AgentTaskRunEntity("run", task.id, "event:1", queuedAt = 1000)
        assertFalse(dao.enqueue(dao.task(task.id)!!, run, null))
        assertTrue(dao.enqueue(dao.task(task.id)!!, run, null, manual = true))
        dao.complete(run, "completed", 1001, "")
        dao.updateTask(dao.task(task.id)!!.copy(enabled = true))
        assertFalse(
            dao.enqueue(
                dao.task(task.id)!!,
                run.copy(id = "burst", fireKey = "event:2", queuedAt = 2000),
                null
            )
        )
        dao.remove(task.id)
        assertTrue(dao.runs(task.id, 10).isEmpty())
        assertNull(dao.task(task.id))
    }

    @Test
    fun cancellationIsDurableBeforeClaimAndRuntimeCanObserveRunningCancellation() = runBlocking {
        val dao = db.agentTaskDao()
        dao.insertTask(task)
        val queued = AgentTaskRunEntity("queued", task.id, "event:queued", queuedAt = 1000)
        assertTrue(dao.enqueue(task, queued, null))
        assertTrue(dao.requestCancellation(task.id, 1100).isEmpty())
        assertEquals(0, dao.claim(queued.id, 1200))
        assertEquals("cancelled", dao.run(queued.id)!!.status)

        dao.insertTask(task.copy(id = "running-task"))
        val running = AgentTaskRunEntity("running", "running-task", "event:running", queuedAt = 1300)
        assertTrue(dao.enqueue(dao.task("running-task")!!, running, null))
        assertEquals(1, dao.claim(running.id, 1400))
        assertEquals(listOf(running.id), dao.requestCancellation("running-task", 1500))
        assertTrue(dao.isRunCancellationRequested(running.id))
        assertEquals("running", dao.run(running.id)!!.status)
    }

    @Test
    fun queuedRunsAreOrderedBoundedAndAllowLaterConditionEligibleRunToBeSelected() = runBlocking {
        val dao = db.agentTaskDao()
        val blockedTask = task.copy(
            id = "blocked-task",
            triggerJson = "{\"type\":\"interval\",\"seconds\":3600,\"conditions\":{\"charging\":true}}",
        )
        val eligibleTask = task.copy(
            id = "eligible-task",
            triggerJson = "{\"type\":\"interval\",\"seconds\":3600,\"conditions\":{\"networkConnected\":true}}",
        )
        dao.insertTask(blockedTask)
        dao.insertTask(eligibleTask)
        assertTrue(dao.enqueue(blockedTask, AgentTaskRunEntity("blocked", blockedTask.id, "event:1", queuedAt = 1000), null))
        assertTrue(dao.enqueue(eligibleTask, AgentTaskRunEntity("eligible", eligibleTask.id, "event:2", queuedAt = 2000), null))
        for (index in 0..100) {
            val bulkTask = task.copy(id = "bulk-task-$index")
            dao.insertTask(bulkTask)
            assertTrue(
                dao.enqueue(
                    bulkTask,
                    AgentTaskRunEntity("bulk-$index", bulkTask.id, "event:$index", queuedAt = 3000L + index),
                    null,
                ),
            )
        }

        val candidates = dao.queuedRuns(100)
        assertEquals(100, candidates.size)
        assertEquals(listOf("blocked", "eligible"), candidates.take(2).map { it.id })
        assertFalse(candidates.any { it.id == "bulk-100" })
        val taskById = dao.tasks().associateBy { it.id }
        val selected = candidates.first { run ->
            AgentTaskRules.conditionsMatch(
                org.json.JSONObject(taskById.getValue(run.taskId).triggerJson),
                mapOf("charging" to false, "networkConnected" to true),
            )
        }
        assertEquals("eligible", selected.id)
        assertEquals(emptyList<AgentTaskRunEntity>(), dao.queuedRuns(0))
    }

    @Test
    fun revisionUpdateRetainsConcurrentExecutionCountAndRejectsStaleEdit() = runBlocking {
        val dao = db.agentTaskDao()
        dao.insertTask(task)
        val snapshot = dao.task(task.id)!!
        val run = AgentTaskRunEntity("run", task.id, "manual:1", queuedAt = 1000)
        assertTrue(dao.enqueue(snapshot, run, 2000, manual = true))
        dao.complete(run, "completed", 1100, "done")
        assertTrue(dao.revise(snapshot.copy(name = "Updated", updatedAt = 2), snapshot.updatedAt))
        assertEquals(1, dao.task(task.id)!!.runCount)
        assertEquals("completed", dao.task(task.id)!!.lastStatus)
        assertFalse(dao.revise(snapshot.copy(name = "Stale", updatedAt = 3), snapshot.updatedAt))
        assertEquals("Updated", dao.task(task.id)!!.name)
    }

    @Test
    fun missingConfigurationDoesNotConsumeAnExecutionAndCanBeRunAfterSetup() = runBlocking {
        val dao = db.agentTaskDao()
        dao.insertTask(task.copy(maxRuns = 1))
        val run = AgentTaskRunEntity("run", task.id, "time:1000", queuedAt = 1000)
        assertTrue(dao.enqueue(dao.task(task.id)!!, run, null))
        dao.complete(run, "configuration_required", 1100, "")
        assertEquals(0, dao.task(task.id)!!.runCount)
        assertTrue(dao.task(task.id)!!.enabled)
        val manual = run.copy(id = "configured", fireKey = "manual:1", queuedAt = 1200)
        assertTrue(dao.enqueue(dao.task(task.id)!!, manual, null, manual = true))
        assertEquals(1, dao.claim(manual.id, 1200))
        dao.complete(manual, "completed", 1300, "done")
        assertEquals(1, dao.task(task.id)!!.runCount)
        assertFalse(dao.task(task.id)!!.enabled)
    }
}
