package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/** CPU-only, bounded lifetime; this never wakes/unlocks the physical screen or changes Doze policy. */
internal class AgentAutomationExecutionLease(
    context: Context,
    controller: AgentRunController,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { controller.cancel() }
    private val wakeLock = requireNotNull(context.getSystemService(PowerManager::class.java))
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Eta:automation")
    private var cancellationBinding: AgentRunController.ResourceBinding? = null

    init {
        controller.throwIfCancelled()
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(MAX_DURATION_MS)
        handler.postDelayed(timeout, MAX_DURATION_MS)
        cancellationBinding = controller.register(::close)
        if (closed.get()) cancellationBinding?.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        cancellationBinding?.close()
        if (wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        // Android may stop the job much earlier. This is a resource ceiling, not a scheduling promise.
        const val MAX_DURATION_MS = 30 * 60_000L
    }
}
