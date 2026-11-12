package io.github.mangi.eta.agent.virtualdisplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.HandlerThread
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

/** 已分离的资源只在工作线程回收，不再持有 Controller 的状态锁。 */
internal class VirtualDisplaySessionResources(
    val reader: ImageReader?,
    val frameWorker: HandlerThread?,
    val virtualDisplay: VirtualDisplay?,
    val bridge: RootVirtualDisplayBridge?,
    val manager: DisplayManager?,
    val listener: DisplayManager.DisplayListener?,
) {
    fun close() {
        fun closePart(block: () -> Unit) {
            try {
                block()
            } catch (exception: Exception) {
                AndroidAgentLogger.warnThrottled("virtual_display_cleanup_failed") {
                    "Virtual display cleanup failed: type=${exception.safeLogType()}"
                }
            }
        }
        closePart { listener?.let { manager?.unregisterDisplayListener(it) } }
        closePart { reader?.setOnImageAvailableListener(null, null) }
        closePart { reader?.close() }
        closePart { frameWorker?.quitSafely() }
        closePart { virtualDisplay?.release() }
        closePart { bridge?.close() }
    }
}

internal class RootVirtualDisplayBridge private constructor(
    val displayId: Int,
    val process: Process,
    private val supervisor: ShellProcessSupervisor,
    private val disconnected: AtomicBoolean,
    private val closing: AtomicBoolean,
    private val terminationLock: Any,
) {
    val isAlive: Boolean
        get() = !disconnected.get() && process.isAlive

    fun close() {
        synchronized(terminationLock) { closing.set(true) }
        try {
            process.outputStream.write("release\n".toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            process.outputStream.close()
        } catch (_: Exception) {
            // 断开的管道交给下面的进程树回收处理。
        }
        supervisor.beginClosing()
        try {
            if (process.isAlive) supervisor.terminateAndReap(process) else supervisor.reapProcess(process)
        } finally {
            supervisor.unregisterProcess(process)
        }
    }

    companion object {
        fun create(
            appContext: Context,
            surface: android.view.Surface,
            width: Int,
            height: Int,
            densityDpi: Int,
            rootAvailable: () -> Boolean,
            automation: Boolean = false,
            onFailureDetail: (AutomationDisplayFailureDetail) -> Unit = {},
            onDisconnected: (Process) -> Unit,
        ): RootVirtualDisplayBridge? {
            val failureReported = AtomicBoolean(false)
            val closing = AtomicBoolean(false)
            val terminationLock = Any()
            fun reportDetail(detail: AutomationDisplayFailureDetail) {
                synchronized(terminationLock) {
                    if (closing.get() || !failureReported.compareAndSet(false, true)) return
                    runCatching { onFailureDetail(detail) }
                }
            }
            fun reportFailure(
                stage: AutomationDisplayFailureDetail.Stage,
                code: AutomationDisplayFailureDetail.Code,
                error: Throwable? = null,
                branch: AutomationDisplayFailureDetail.Branch? = null,
                sdkInt: Int = android.os.Build.VERSION.SDK_INT,
                processUid: Int? = null,
            ) {
                reportDetail(
                    AutomationDisplayFailureDetail(
                        reason = failureReason(code),
                        stage = stage,
                        code = code,
                        exceptionKind = AutomationDisplayFailureDetail.safeExceptionKind(error),
                        causeKind = AutomationDisplayFailureDetail.safeCauseKind(error),
                        sdkInt = sdkInt,
                        processUid = processUid,
                        branch = branch,
                    ),
                )
            }
            val token = VirtualDisplaySurfaceRegistry.register(surface)
            val supervisor = ShellProcessSupervisor(rootAvailable = rootAvailable)
            var process: Process? = null
            var accepted = false
            try {
                val apkPath = appContext.applicationInfo.sourceDir
                val command = "CLASSPATH=${DeviceToolContract.quote(apkPath)} app_process /system/bin " +
                    "io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain"
                val started = supervisor.startShellProcess("root", command, mergeStderr = true) ?: run {
                    reportFailure(
                        AutomationDisplayFailureDetail.Stage.PROCESS_START,
                        AutomationDisplayFailureDetail.Code.PROCESS_START_FAILED,
                    )
                    return null
                }
                process = started
                val readyLatch = CountDownLatch(1)
                val disconnected = AtomicBoolean(false)
                var resolvedDisplayId: Int? = null
                val payload = JSONObject()
                    .put("action", "hold_display")
                    .put("user_id", DeviceToolContract.appUserId(appContext))
                    .put("surface_token", token)
                    .put("width", width)
                    .put("height", height)
                    .put("density_dpi", densityDpi)
                    .put("automation", automation)
                    .toString() + "\n"

                thread(name = "eta-vd-bridge-stdout", isDaemon = true) {
                    try {
                        BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8)).use { reader ->
                            while (true) {
                                val bounded = readBoundedProtocolLine(reader)
                                val line = bounded.value ?: break
                                if (bounded.tooLong) {
                                    if (line.startsWith(VirtualDisplayCommandMain.READY_PREFIX) ||
                                        line.startsWith(VirtualDisplayCommandMain.RESULT_PREFIX)
                                    ) {
                                        reportFailure(
                                            AutomationDisplayFailureDetail.Stage.REQUEST_HANDSHAKE,
                                            AutomationDisplayFailureDetail.Code.OUTPUT_LIMIT_EXCEEDED,
                                        )
                                        readyLatch.countDown()
                                    }
                                    continue
                                }
                                if (line.startsWith(VirtualDisplayCommandMain.READY_PREFIX)) {
                                    resolvedDisplayId = line.removePrefix(VirtualDisplayCommandMain.READY_PREFIX)
                                        .trim().toIntOrNull()
                                    if (resolvedDisplayId == null) {
                                        reportFailure(
                                            AutomationDisplayFailureDetail.Stage.DISPLAY_ID_VALIDATION,
                                            AutomationDisplayFailureDetail.Code.INVALID_DISPLAY_ID,
                                        )
                                    }
                                    readyLatch.countDown()
                                } else if (line.startsWith(VirtualDisplayCommandMain.RESULT_PREFIX)) {
                                    parseHelperResult(
                                        line.removePrefix(VirtualDisplayCommandMain.RESULT_PREFIX),
                                        ::reportDetail,
                                    )
                                    readyLatch.countDown()
                                }
                            }
                        }
                    } catch (exception: java.io.IOException) {
                        reportFailure(
                            AutomationDisplayFailureDetail.Stage.PROCESS_LIFECYCLE,
                            AutomationDisplayFailureDetail.Code.PROCESS_EXITED,
                            exception,
                        )
                    } finally {
                        synchronized(terminationLock) {
                            if (shouldReportUnexpectedBridgeTermination(closing.get(), failureReported.get())) {
                                reportFailure(
                                    AutomationDisplayFailureDetail.Stage.PROCESS_LIFECYCLE,
                                    AutomationDisplayFailureDetail.Code.PROCESS_EXITED,
                                )
                            }
                        }
                        disconnected.set(true)
                        readyLatch.countDown()
                        onDisconnected(started)
                    }
                }
                try {
                    started.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                    started.outputStream.flush()
                } catch (exception: Exception) {
                    reportFailure(
                        AutomationDisplayFailureDetail.Stage.REQUEST_HANDSHAKE,
                        AutomationDisplayFailureDetail.Code.REQUEST_WRITE_FAILED,
                        exception,
                    )
                    return null
                }
                val awaited = try {
                    readyLatch.await(4, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                val id = resolvedDisplayId
                if (!awaited) {
                    reportFailure(
                        AutomationDisplayFailureDetail.Stage.REQUEST_HANDSHAKE,
                        AutomationDisplayFailureDetail.Code.HANDSHAKE_TIMEOUT,
                    )
                    return null
                }
                if (id == null || !CanvasRoutingPolicy.isVirtualDisplay(id) ||
                    disconnected.get() || !started.isAlive
                ) {
                    if (!failureReported.get()) {
                        val exitedBeforeReady = id == null && (disconnected.get() || !started.isAlive)
                        reportFailure(
                            if (id == null && !exitedBeforeReady) {
                                AutomationDisplayFailureDetail.Stage.REQUEST_HANDSHAKE
                            } else {
                                AutomationDisplayFailureDetail.Stage.PROCESS_LIFECYCLE
                            },
                            when {
                                exitedBeforeReady || id != null -> AutomationDisplayFailureDetail.Code.PROCESS_EXITED
                                else -> AutomationDisplayFailureDetail.Code.MALFORMED_RESULT
                            },
                        )
                    }
                    return null
                }
                accepted = true
                return RootVirtualDisplayBridge(
                    id,
                    started,
                    supervisor,
                    disconnected,
                    closing,
                    terminationLock,
                )
            } catch (exception: Exception) {
                AndroidAgentLogger.warn("Virtual display bridge failed: type=${exception.safeLogType()}")
                reportFailure(
                    AutomationDisplayFailureDetail.Stage.PROCESS_LIFECYCLE,
                    AutomationDisplayFailureDetail.Code.BRIDGE_EXCEPTION,
                    exception,
                )
                return null
            } finally {
                VirtualDisplaySurfaceRegistry.unregister(token)
                if (!accepted) {
                    synchronized(terminationLock) { closing.set(true) }
                    supervisor.beginClosing()
                    process?.let { failed ->
                        try {
                            if (failed.isAlive) supervisor.terminateAndReap(failed) else supervisor.reapProcess(failed)
                        } finally {
                            supervisor.unregisterProcess(failed)
                        }
                    }
                }
            }
        }

        private fun failureReason(code: AutomationDisplayFailureDetail.Code): AutomationDisplayFailure = when (code) {
            AutomationDisplayFailureDetail.Code.ROOT_UNAVAILABLE,
            AutomationDisplayFailureDetail.Code.ROOT_REQUIRED -> AutomationDisplayFailure.ROOT_UNAVAILABLE
            AutomationDisplayFailureDetail.Code.DISPLAY_UNSUPPORTED -> AutomationDisplayFailure.DISPLAY_UNSUPPORTED
            AutomationDisplayFailureDetail.Code.DISPLAY_LOST -> AutomationDisplayFailure.DISPLAY_LOST
            AutomationDisplayFailureDetail.Code.DISPLAY_UNAVAILABLE,
            AutomationDisplayFailureDetail.Code.INVALID_DISPLAY_ID -> AutomationDisplayFailure.DISPLAY_UNAVAILABLE
            else -> AutomationDisplayFailure.ROOT_BRIDGE_FAILED
        }

        private fun parseHelperResult(
            payload: String,
            onFailure: (AutomationDisplayFailureDetail) -> Unit,
        ) {
            val failure = runCatching { JSONObject(payload) }.getOrNull()?.let { result ->
                if (result.optBoolean("ok", false)) return
                AutomationDisplayFailureDetail.fromHelperFields(
                    stageValue = result.optString("stage").takeIf { it != "null" },
                    codeValue = result.optString("code").takeIf { it != "null" },
                    exceptionValue = result.optString("exception_kind").takeIf { it != "null" },
                    causeValue = result.optString("cause_kind").takeIf { it != "null" },
                    sdkIntValue = result.optInt("sdk_int", -1).takeIf { it >= 0 },
                    processUidValue = result.optInt("process_uid", -1).takeIf { it >= 0 },
                    branchValue = result.optString("branch").takeIf { it != "null" },
                )
            }
            onFailure(
                failure ?: AutomationDisplayFailureDetail(
                    reason = AutomationDisplayFailure.ROOT_BRIDGE_FAILED,
                    stage = AutomationDisplayFailureDetail.Stage.REQUEST_HANDSHAKE,
                    code = AutomationDisplayFailureDetail.Code.MALFORMED_RESULT,
                    sdkInt = android.os.Build.VERSION.SDK_INT,
                    processUid = null,
                ),
            )
        }
    }
}
