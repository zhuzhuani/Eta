package io.github.mangi.eta.agent.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 「重试中」通知上的「立刻重试」落点：把等着的重试提前到现在。
 * 只是提前排一次作业，不改动重试计数与原有退避节奏；作业认领到运行时会自己撤掉通知。
 */
internal class AgentTaskRetryNowReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RETRY_NOW) return
        val app = context.applicationContext
        runCatching { AgentTaskScheduler.retryNow(app) }
    }

    companion object {
        const val ACTION_RETRY_NOW = "io.github.mangi.eta.action.RETRY_NOW"
    }
}
