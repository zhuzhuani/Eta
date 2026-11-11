package io.github.mangi.eta.agent.automation

import android.app.job.JobScheduler
import android.content.Context
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTaskSchedulerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dao get() = EtaDatabase.get(context).agentTaskDao()
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        try {
            AgentTaskScheduler.jobFinished()
            dao.remove("scheduler-test")
            AgentTaskScheduler.refresh(context)
        } finally {
            EtaDatabase.closeForTests()
        }
    }

    @Test
    fun futureTimerHasPersistedWakeupBeforeAnyRunIsQueuedAndRemovalCancelsIt() = runBlocking {
        val due = System.currentTimeMillis() + 90_000
        dao.insertTask(task(due))
        assertTrue(dao.queuedRuns().isEmpty())
        AgentTaskScheduler.refresh(context)
        val job = scheduler.getPendingJob(1110)
        assertNotNull(job)
        assertEquals(
            "io.github.mangi.eta.agent.automation.AgentTaskJobService",
            job!!.service.className
        )
        assertFalse(job.isPeriodic)
        assertEquals(true, job.isPersisted)
        dao.remove("scheduler-test")
        AgentTaskScheduler.refresh(context)
        assertNull(scheduler.getPendingJob(1110))
    }

    @Test
    fun refreshSchedulesRecoveryWhenOnlyAnOrphanedRunningRunRemains() = runBlocking {
        dao.insertTask(task(System.currentTimeMillis() + 90_000))
        val run = AgentTaskRunEntity("orphaned", "scheduler-test", "time:orphaned", queuedAt = 1)
        assertTrue(dao.enqueue(dao.task("scheduler-test")!!, run, null))
        assertEquals(1, dao.claim(run.id, 2))

        AgentTaskScheduler.refresh(context)

        val job = scheduler.getPendingJob(1110)
        assertNotNull(job)
        assertEquals(0L, job!!.minLatencyMillis)
    }

    @Test
    fun refreshImmediatelyDispatchesManualRunForPausedTask() = runBlocking {
        val paused = task(System.currentTimeMillis() + 90_000).copy(enabled = false)
        dao.insertTask(paused)
        val run = AgentTaskRunEntity("paused-manual", paused.id, "manual:paused", queuedAt = 1)
        assertTrue(dao.enqueue(paused, run, paused.nextRunAt, manual = true))

        AgentTaskScheduler.refresh(context)

        assertEquals(0L, scheduler.getPendingJob(1110)!!.minLatencyMillis)
    }

    @Test
    fun refreshingRulesDoesNotReplaceAnExecutingJob() = runBlocking {
        dao.insertTask(task(System.currentTimeMillis() + 90_000))
        AgentTaskScheduler.refresh(context)
        val original = scheduler.getPendingJob(1110)
        assertEquals(true, AgentTaskScheduler.jobStarted())
        dao.remove("scheduler-test")
        AgentTaskScheduler.refresh(context)
        assertEquals(original, scheduler.getPendingJob(1110))
    }

    private fun task(due: Long) = AgentTaskEntity(
        id = "scheduler-test",
        name = "Test timer",
        prompt = "Return the test result",
        triggerJson = JSONObject().put("type", "once")
            .put("at", Instant.ofEpochMilli(due).toString()).toString(),
        enabled = true,
        nextRunAt = due,
        cooldownSeconds = 900,
        maxRuns = 1,
        createdAt = 0,
        updatedAt = 0,
    )
}
