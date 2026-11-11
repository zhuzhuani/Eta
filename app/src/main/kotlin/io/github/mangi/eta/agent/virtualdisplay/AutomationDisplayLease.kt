package io.github.mangi.eta.agent.virtualdisplay

import java.util.concurrent.atomic.AtomicBoolean

/** Exclusive ownership handle for a display dedicated to one automated run. */
class AutomationDisplayLease internal constructor(
    val displayId: Int,
    private val isCurrent: () -> Boolean,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun isValid(): Boolean = !closed.get() && isCurrent()

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
