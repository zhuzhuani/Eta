package io.github.mangi.eta.agent.automation

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 自动任务失败自愈（保守版）。
 *
 * 红线：**只有「还没动手就失败」才自动重试**——也就是整个会话里没有任何工具真正执行过。
 * 一旦点过屏幕、发过消息、改过文件，就不再重试，只报错：
 * 重试最坏的结果是把消息发两遍、按钮点两下，那比不重试更麻烦。
 *
 * 处理三类：
 *  - 网络／服务端临时故障（超时、连不上、502/503/504、429）→ 最多自动重试 2 次，间隔 30 秒、120 秒。
 *  - 权限／无障碍未连接、上下文或模型侧问题 → 不重试，写一条「为什么失败 + 怎么办」的通知。
 *  - 其他没认出来的失败 → 一律不重试（认不出来就别赌），只在通知和任务历史里留原因。
 */
internal object AgentTaskRetryPolicy {

    /** 最多自动重试几次（第 1 次失败后算第 1 次重试）。 */
    const val MAX_ATTEMPTS = 2

    private val BACKOFF_MS = longArrayOf(30_000L, 120_000L)
    private const val PREFS = "automation_retry"
    private const val MAX_TRACKED = 50

    enum class Decision { RETRY, GIVE_UP }

    data class Classification(
        val decision: Decision,
        val reason: String,
        val advice: String,
    )

    private val networkHints = listOf(
        "timeout", "timed out", "超时", "connect", "connection", "socket", "unreachable",
        "unknownhost", "unknown host", "network", "网络", "502", "503", "504", "429",
        "temporarily", "reset by peer", "eof", "stream closed",
    )
    private val permissionHints = listOf(
        "permission", "权限", "accessibility", "无障碍", "not granted", "未授权", "未连接",
    )
    private val contextHints = listOf(
        "context", "上下文", "超限", "too long", "maximum context", "token",
    )

    fun classify(error: String?, content: String?, touched: Boolean): Classification {
        val text = (error.orEmpty() + " " + content.orEmpty()).lowercase()
        fun has(hints: List<String>) = hints.any { text.contains(it) }
        return when {
            touched -> Classification(
                Decision.GIVE_UP,
                "已经动过手机后才失败",
                "为免重复操作（发两次消息、点两次按钮）这种情况不自动重试；" +
                    "看一眼任务历史里的失败原因，改好再手动跑一次。",
            )
            has(permissionHints) -> Classification(
                Decision.GIVE_UP,
                "缺少权限或无障碍未连接",
                "去设置里补上对应权限（点屏幕类功能需要无障碍保持连接），然后手动跑一次。",
            )
            has(contextHints) && !has(networkHints) -> Classification(
                Decision.GIVE_UP,
                "上下文或模型侧问题",
                "把任务提示写短一点，或拆成两个任务再来。",
            )
            has(networkHints) -> Classification(
                Decision.RETRY,
                "网络或服务端临时故障",
                "会自动重试，不用管。",
            )
            else -> Classification(
                Decision.GIVE_UP,
                "未能识别的失败",
                "保守起见不自动重试；失败原因已写进任务历史和自留日志，必要时导诊断包我看。",
            )
        }
    }

    /** 记录并返回这是第几次尝试（从 1 开始）。 */
    fun attempt(context: Context, runId: String): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getInt(runId, 0) + 1
        val editor = prefs.edit().putInt(runId, next)
        val keys = prefs.all.keys
        if (keys.size > MAX_TRACKED) {
            keys.filter { it != runId }.take(keys.size - MAX_TRACKED).forEach { editor.remove(it) }
        }
        editor.apply()
        AndroidAgentLogger.info("task.retry runId=$runId 第${next}次尝试")
        return next
    }

    fun backoffFor(attempt: Int): Long = BACKOFF_MS.getOrElse(attempt - 1) { BACKOFF_MS.last() }

    fun clear(context: Context, runId: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(runId).apply()
    }
}
