package io.github.mangi.eta.agent.virtualdisplay

import java.io.StringReader
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDisplayFailureDetailTest {
    @Test
    fun unknownAndInvalidStageCodePairsAreRejected() {
        assertNull(
            AutomationDisplayFailureDetail.fromHelperFields(
                stageValue = "NOT_A_STAGE",
                codeValue = "CREATE_DISPLAY_FAILED",
                exceptionValue = null,
                causeValue = null,
                sdkIntValue = 35,
                processUidValue = 1000,
                branchValue = null,
            ),
        )
        assertNull(
            AutomationDisplayFailureDetail.fromHelperFields(
                stageValue = AutomationDisplayFailureDetail.Stage.PROCESS_START.name,
                codeValue = AutomationDisplayFailureDetail.Code.CREATE_DISPLAY_FAILED.name,
                exceptionValue = null,
                causeValue = null,
                sdkIntValue = 35,
                processUidValue = 1000,
                branchValue = null,
            ),
        )
        assertNull(
            AutomationDisplayFailureDetail.fromHelperFields(
                stageValue = AutomationDisplayFailureDetail.Stage.PROCESS_START.name,
                codeValue = "FUTURE_UNLISTED_CODE",
                exceptionValue = null,
                causeValue = null,
                sdkIntValue = 35,
                processUidValue = 1000,
                branchValue = null,
            ),
        )
        assertEquals(
            AutomationDisplayFailureDetail.Code.DISPLAY_LOST,
            AutomationDisplayFailureDetail.fromHelperFields(
                stageValue = AutomationDisplayFailureDetail.Stage.DISPLAY_VALIDATION.name,
                codeValue = AutomationDisplayFailureDetail.Code.DISPLAY_LOST.name,
                exceptionValue = null,
                causeValue = null,
                sdkIntValue = 35,
                processUidValue = 1000,
                branchValue = AutomationDisplayFailureDetail.Branch.DISPLAY_MANAGER_LOOKUP.name,
            )?.code,
        )
    }

    @Test
    fun displayFlagsAndImePolicyFailuresHaveDistinctFiniteBranches() {
        val flagsFailure = AutomationDisplayFailureDetail.fromHelperFields(
            stageValue = AutomationDisplayFailureDetail.Stage.DISPLAY_ISOLATION.name,
            codeValue = AutomationDisplayFailureDetail.Code.DISPLAY_ISOLATION_UNAVAILABLE.name,
            exceptionValue = null,
            causeValue = null,
            sdkIntValue = 35,
            processUidValue = 1000,
            branchValue = AutomationDisplayFailureDetail.Branch.DISPLAY_FLAGS.name,
        )
        assertEquals(AutomationDisplayFailureDetail.Branch.DISPLAY_FLAGS, flagsFailure?.branch)
        val imeFailure = AutomationDisplayFailureDetail.fromHelperFields(
            stageValue = AutomationDisplayFailureDetail.Stage.DISPLAY_ISOLATION.name,
            codeValue = AutomationDisplayFailureDetail.Code.DISPLAY_ISOLATION_UNAVAILABLE.name,
            exceptionValue = null,
            causeValue = null,
            sdkIntValue = 35,
            processUidValue = 1000,
            branchValue = AutomationDisplayFailureDetail.Branch.IME_POLICY.name,
        )
        assertEquals(AutomationDisplayFailureDetail.Branch.IME_POLICY, imeFailure?.branch)
        assertFalse(flagsFailure?.branch == imeFailure?.branch)
    }

    @Test
    fun expectedBridgeCloseAndAlreadyReportedFailureDoNotEmitExitFailure() {
        assertFalse(shouldReportUnexpectedBridgeTermination(closing = true, failureReported = false))
        assertFalse(shouldReportUnexpectedBridgeTermination(closing = false, failureReported = true))
        assertTrue(shouldReportUnexpectedBridgeTermination(closing = false, failureReported = false))
    }

    @Test
    fun invocationTargetCauseIsRetainedOnlyForThatWrapper() {
        val reflected = InvocationTargetException(SecurityException("not logged"))
        assertEquals(
            AutomationDisplayFailureDetail.ExceptionKind.INVOCATION_TARGET_EXCEPTION,
            AutomationDisplayFailureDetail.safeExceptionKind(reflected),
        )
        assertEquals(
            AutomationDisplayFailureDetail.ExceptionKind.SECURITY_EXCEPTION,
            AutomationDisplayFailureDetail.safeCauseKind(reflected),
        )
        assertNull(AutomationDisplayFailureDetail.safeCauseKind(IllegalStateException("not logged")))
    }

    @Test
    fun protocolReaderBoundsLineAndStillIdentifiesProtocolPrefix() {
        val plainText = "ordinary log " + "x".repeat(5_000) + "\n"
        val boundedPlain = readBoundedProtocolLine(StringReader(plainText))
        assertTrue(boundedPlain.tooLong)
        assertTrue(boundedPlain.value.orEmpty().startsWith("ordinary log "))
        assertFalse(boundedPlain.value.orEmpty().startsWith(VirtualDisplayCommandMain.RESULT_PREFIX))

        val protocol = VirtualDisplayCommandMain.RESULT_PREFIX + "x".repeat(5_000) + "\n"
        val boundedProtocol = readBoundedProtocolLine(StringReader(protocol))
        assertTrue(boundedProtocol.tooLong)
        assertTrue(boundedProtocol.value.orEmpty().startsWith(VirtualDisplayCommandMain.RESULT_PREFIX))
        assertEquals(4096, boundedProtocol.value?.length)
    }

    @Test
    fun shortLineAndEmptyEofRemainDistinct() {
        assertEquals("ETA_VD_READY:7", readBoundedProtocolLine(StringReader("ETA_VD_READY:7\n")).value)
        assertFalse(readBoundedProtocolLine(StringReader("ok\n")).tooLong)
        assertNull(readBoundedProtocolLine(StringReader("")).value)
    }
}
