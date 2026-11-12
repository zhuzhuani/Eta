package io.github.mangi.eta.agent.virtualdisplay

import java.io.Reader

internal data class BoundedProtocolLine(val value: String?, val tooLong: Boolean)

internal fun shouldReportUnexpectedBridgeTermination(closing: Boolean, failureReported: Boolean): Boolean =
    !closing && !failureReported

/** Reads one protocol line without allocating in proportion to untrusted child-process output. */
internal fun readBoundedProtocolLine(reader: Reader, maxChars: Int = 4096): BoundedProtocolLine {
    require(maxChars > 0)
    val line = StringBuilder(minOf(maxChars, 256))
    var tooLong = false
    while (true) {
        val next = reader.read()
        if (next < 0) return BoundedProtocolLine(line.toString().takeIf { it.isNotEmpty() }, tooLong)
        if (next == '\n'.code) return BoundedProtocolLine(line.toString(), tooLong)
        if (next != '\r'.code) {
            if (line.length < maxChars) line.append(next.toChar()) else tooLong = true
        }
    }
}

/** Stable, structured failure detail from the root virtual-display bridge. Never contains free-form text. */
internal data class AutomationDisplayFailureDetail(
    val reason: AutomationDisplayFailure,
    val stage: Stage,
    val code: Code,
    val exceptionKind: ExceptionKind? = null,
    val causeKind: ExceptionKind? = null,
    val sdkInt: Int? = null,
    val processUid: Int? = null,
    val branch: Branch? = null,
) {
    enum class Stage {
        PRECONDITION,
        PROCESS_START,
        REQUEST_HANDSHAKE,
        SURFACE_ACQUISITION,
        CREATE_DISPLAY,
        DISPLAY_ID_VALIDATION,
        DISPLAY_WAKE_LOCK,
        DISPLAY_ISOLATION,
        DISPLAY_VALIDATION,
        PROCESS_LIFECYCLE,
    }

    enum class Code {
        ROOT_UNAVAILABLE,
        VIRTUAL_DISPLAY_OCCUPIED,
        DISPLAY_UNSUPPORTED,
        PROCESS_START_FAILED,
        REQUEST_WRITE_FAILED,
        HANDSHAKE_TIMEOUT,
        READY_TIMEOUT,
        MALFORMED_RESULT,
        OUTPUT_LIMIT_EXCEEDED,
        HELPER_FAILED,
        ROOT_REQUIRED,
        SURFACE_UNAVAILABLE,
        SURFACE_ACQUISITION_FAILED,
        CREATE_DISPLAY_FAILED,
        INVALID_DISPLAY_ID,
        DISPLAY_WAKE_LOCK_UNAVAILABLE,
        DISPLAY_ISOLATION_UNAVAILABLE,
        DISPLAY_UNAVAILABLE,
        DISPLAY_LOST,
        PROCESS_EXITED,
        BRIDGE_EXCEPTION,
    }

    enum class ExceptionKind {
        SECURITY_EXCEPTION,
        ILLEGAL_ARGUMENT_EXCEPTION,
        ILLEGAL_STATE_EXCEPTION,
        IO_EXCEPTION,
        CLASS_NOT_FOUND_EXCEPTION,
        NO_SUCH_METHOD_EXCEPTION,
        NO_SUCH_FIELD_EXCEPTION,
        UNSUPPORTED_OPERATION_EXCEPTION,
        LINKAGE_ERROR,
        INVOCATION_TARGET_EXCEPTION,
        OTHER,
    }

    enum class Branch {
        SYSTEM_CONTEXT,
        SURFACE_PROVIDER,
        VIRTUAL_DISPLAY_CONFIG,
        DISPLAY_MANAGER_GLOBAL,
        DISPLAY_MANAGER,
        DISPLAY_WAKE_LOCK,
        DISPLAY_MANAGER_LOOKUP,
        DISPLAY_FLAGS,
        IME_POLICY,
        IME_SERVICE_LOOKUP,
        IME_SET_METHOD_LOOKUP,
        IME_SET_METHOD_INVOCATION,
        IME_GET_METHOD_LOOKUP,
        IME_GET_METHOD_INVOCATION,
        IME_READBACK_MISMATCH,
    }

    companion object {
        private fun Stage.accepts(code: Code): Boolean = when (this) {
            Stage.PRECONDITION -> code in setOf(
                Code.ROOT_UNAVAILABLE, Code.VIRTUAL_DISPLAY_OCCUPIED, Code.DISPLAY_UNSUPPORTED,
                Code.ROOT_REQUIRED, Code.HELPER_FAILED,
            )
            Stage.PROCESS_START -> code == Code.PROCESS_START_FAILED
            Stage.REQUEST_HANDSHAKE -> code in setOf(
                Code.REQUEST_WRITE_FAILED, Code.HANDSHAKE_TIMEOUT, Code.READY_TIMEOUT,
                Code.MALFORMED_RESULT, Code.OUTPUT_LIMIT_EXCEEDED,
            )
            Stage.SURFACE_ACQUISITION -> code in setOf(Code.SURFACE_UNAVAILABLE, Code.SURFACE_ACQUISITION_FAILED)
            Stage.CREATE_DISPLAY -> code == Code.CREATE_DISPLAY_FAILED
            Stage.DISPLAY_ID_VALIDATION -> code == Code.INVALID_DISPLAY_ID
            Stage.DISPLAY_WAKE_LOCK -> code == Code.DISPLAY_WAKE_LOCK_UNAVAILABLE
            Stage.DISPLAY_ISOLATION -> code in setOf(Code.DISPLAY_ISOLATION_UNAVAILABLE, Code.DISPLAY_UNSUPPORTED)
            Stage.DISPLAY_VALIDATION -> code in setOf(Code.DISPLAY_UNAVAILABLE, Code.DISPLAY_LOST)
            Stage.PROCESS_LIFECYCLE -> code in setOf(Code.PROCESS_EXITED, Code.BRIDGE_EXCEPTION, Code.DISPLAY_LOST)
        }

        fun fromHelperFields(
            stageValue: String?,
            codeValue: String?,
            exceptionValue: String?,
            causeValue: String?,
            sdkIntValue: Int?,
            processUidValue: Int?,
            branchValue: String?,
        ): AutomationDisplayFailureDetail? {
            val stage = Stage.entries.firstOrNull { it.name == stageValue } ?: return null
            val code = Code.entries.firstOrNull { it.name == codeValue } ?: return null
            if (!stage.accepts(code)) return null
            val reason = when (code) {
                Code.ROOT_UNAVAILABLE, Code.ROOT_REQUIRED -> AutomationDisplayFailure.ROOT_UNAVAILABLE
                Code.DISPLAY_UNSUPPORTED -> AutomationDisplayFailure.DISPLAY_UNSUPPORTED
                Code.DISPLAY_LOST -> AutomationDisplayFailure.DISPLAY_LOST
                Code.DISPLAY_UNAVAILABLE, Code.INVALID_DISPLAY_ID -> AutomationDisplayFailure.DISPLAY_UNAVAILABLE
                else -> AutomationDisplayFailure.ROOT_BRIDGE_FAILED
            }
            return AutomationDisplayFailureDetail(
                reason = reason,
                stage = stage,
                code = code,
                exceptionKind = ExceptionKind.entries.firstOrNull { it.name == exceptionValue },
                causeKind = ExceptionKind.entries.firstOrNull { it.name == exceptionValue }
                    ?.takeIf { it == ExceptionKind.INVOCATION_TARGET_EXCEPTION }
                    ?.let { ExceptionKind.entries.firstOrNull { kind -> kind.name == causeValue } },
                sdkInt = sdkIntValue?.takeIf { it in 1..100 },
                processUid = processUidValue?.takeIf { it in 0..2_000_000 },
                branch = Branch.entries.firstOrNull { it.name == branchValue },
            )
        }

        /** Returns a finite allowlisted class category; never exposes arbitrary runtime class names. */
        fun safeExceptionKind(error: Throwable?): ExceptionKind? = classify(error)

        /** Only reflection wrappers have a separately recorded cause category. */
        fun safeCauseKind(error: Throwable?): ExceptionKind? =
            (error as? java.lang.reflect.InvocationTargetException)?.targetException?.let(::classify)

        private fun classify(error: Throwable?): ExceptionKind? = when (error) {
            null -> null
            is SecurityException -> ExceptionKind.SECURITY_EXCEPTION
            is IllegalArgumentException -> ExceptionKind.ILLEGAL_ARGUMENT_EXCEPTION
            is IllegalStateException -> ExceptionKind.ILLEGAL_STATE_EXCEPTION
            is java.io.IOException -> ExceptionKind.IO_EXCEPTION
            is ClassNotFoundException -> ExceptionKind.CLASS_NOT_FOUND_EXCEPTION
            is NoSuchMethodException -> ExceptionKind.NO_SUCH_METHOD_EXCEPTION
            is NoSuchFieldException -> ExceptionKind.NO_SUCH_FIELD_EXCEPTION
            is UnsupportedOperationException -> ExceptionKind.UNSUPPORTED_OPERATION_EXCEPTION
            is LinkageError -> ExceptionKind.LINKAGE_ERROR
            is java.lang.reflect.InvocationTargetException -> ExceptionKind.INVOCATION_TARGET_EXCEPTION
            else -> ExceptionKind.OTHER
        }
    }
}
