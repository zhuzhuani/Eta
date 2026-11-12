package io.github.mangi.eta.agent.virtualdisplay

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImePolicyDiagnosticsTest {
    @Test
    fun eachImePolicyStepFailsClosedWithItsFiniteBranchAndStageMapping() {
        val cases = listOf(
            Case(AutomationDisplayFailureDetail.Branch.IME_SERVICE_LOOKUP, FailurePoint.SERVICE_LOOKUP),
            Case(AutomationDisplayFailureDetail.Branch.IME_SET_METHOD_LOOKUP, FailurePoint.SET_LOOKUP),
            Case(AutomationDisplayFailureDetail.Branch.IME_SET_METHOD_INVOCATION, FailurePoint.SET_INVOCATION),
            Case(AutomationDisplayFailureDetail.Branch.IME_GET_METHOD_LOOKUP, FailurePoint.GET_LOOKUP),
            Case(AutomationDisplayFailureDetail.Branch.IME_GET_METHOD_INVOCATION, FailurePoint.GET_INVOCATION),
            Case(AutomationDisplayFailureDetail.Branch.IME_READBACK_MISMATCH, FailurePoint.READBACK_MISMATCH),
        )

        cases.forEach { case ->
            var detail: AutomationDisplayFailureDetail? = null
            val succeeded = applyImePolicyHide(
                access = FakeImePolicyAccess(case.failurePoint),
                displayId = 12,
                expectedPolicy = 1,
            ) { branch, error ->
                detail = AutomationDisplayFailureDetail(
                    reason = AutomationDisplayFailure.ROOT_BRIDGE_FAILED,
                    stage = AutomationDisplayFailureDetail.Stage.DISPLAY_ISOLATION,
                    code = AutomationDisplayFailureDetail.Code.DISPLAY_ISOLATION_UNAVAILABLE,
                    exceptionKind = AutomationDisplayFailureDetail.safeExceptionKind(error),
                    causeKind = AutomationDisplayFailureDetail.safeCauseKind(error),
                    branch = branch,
                )
            }

            assertFalse("${case.branch} must fail closed", succeeded)
            assertEquals(case.branch, detail?.branch)
            assertEquals(AutomationDisplayFailureDetail.Stage.DISPLAY_ISOLATION, detail?.stage)
            assertEquals(AutomationDisplayFailureDetail.Code.DISPLAY_ISOLATION_UNAVAILABLE, detail?.code)
            assertNotNull("${case.branch} must produce typed detail", detail)
            val parsed = AutomationDisplayFailureDetail.fromHelperFields(
                stageValue = detail?.stage?.name,
                codeValue = detail?.code?.name,
                exceptionValue = detail?.exceptionKind?.name,
                causeValue = detail?.causeKind?.name,
                sdkIntValue = 37,
                processUidValue = 1000,
                branchValue = detail?.branch?.name,
            )
            assertEquals(case.branch, parsed?.branch)
            assertEquals(AutomationDisplayFailureDetail.Stage.DISPLAY_ISOLATION, parsed?.stage)
            assertEquals(AutomationDisplayFailureDetail.Code.DISPLAY_ISOLATION_UNAVAILABLE, parsed?.code)
            assertFalse(detail.toString().contains("private exception text"))
        }
    }

    @Test
    fun serviceLookupUsesPrimaryWhenAvailableAndFallsBackAfterPrimaryFailure() {
        var fallbackCalls = 0
        val primaryService = Any()
        val preferred = object : ImePolicyAccess {
            override fun getWindowManagerService(): Any = primaryService
            override fun getWindowManagerServiceFallback(): Any? {
                fallbackCalls++
                return Any()
            }
            override fun findSetDisplayImePolicy(service: Any) = { _: Int, _: Int -> }
            override fun findGetDisplayImePolicy(service: Any) = { _: Int -> 1 }
        }
        assertTrue(applyImePolicyHide(preferred, 12, 1) { _, _ -> })
        assertEquals(0, fallbackCalls)

        val fallbackService = Any()
        val recovering = object : ImePolicyAccess {
            override fun getWindowManagerService(): Any? = throw IllegalStateException("primary unavailable")
            override fun getWindowManagerServiceFallback(): Any = fallbackService
            override fun findSetDisplayImePolicy(service: Any) = { _: Int, _: Int -> }
            override fun findGetDisplayImePolicy(service: Any) = { _: Int -> 1 }
        }
        assertTrue(applyImePolicyHide(recovering, 12, 1) { _, _ -> })

        val nullPrimary = object : ImePolicyAccess {
            override fun getWindowManagerService(): Any? = null
            override fun getWindowManagerServiceFallback(): Any = fallbackService
            override fun findSetDisplayImePolicy(service: Any) = { _: Int, _: Int -> }
            override fun findGetDisplayImePolicy(service: Any) = { _: Int -> 1 }
        }
        assertTrue(applyImePolicyHide(nullPrimary, 12, 1) { _, _ -> })
    }

    @Test
    fun emptyFallbackPreservesPrimaryInvocationTargetExceptionForDiagnostics() {
        val originalCause = IllegalStateException("primary cause")
        val primaryError = InvocationTargetException(originalCause)
        var reported: Throwable? = null
        var reportedBranch: AutomationDisplayFailureDetail.Branch? = null
        val access = object : ImePolicyAccess {
            override fun getWindowManagerService(): Any? = throw primaryError
            override fun getWindowManagerServiceFallback(): Any? = null
            override fun findSetDisplayImePolicy(service: Any) = null
            override fun findGetDisplayImePolicy(service: Any) = null
        }

        assertFalse(applyImePolicyHide(access, 12, 1) { branch, error ->
            reportedBranch = branch
            reported = error
        })
        assertEquals(AutomationDisplayFailureDetail.Branch.IME_SERVICE_LOOKUP, reportedBranch)
        assertTrue(reported === primaryError)
        assertTrue(reported?.cause === originalCause)
    }

    @Test
    fun setterInvocationRetainsOnlyFiniteInvocationAndCauseKinds() {
        var observed: AutomationDisplayFailureDetail.Branch? = null
        var exceptionKind: AutomationDisplayFailureDetail.ExceptionKind? = null
        var causeKind: AutomationDisplayFailureDetail.ExceptionKind? = null
        val success = applyImePolicyHide(
            access = FakeImePolicyAccess(FailurePoint.SET_INVOCATION),
            displayId = 12,
            expectedPolicy = 1,
        ) { branch, error ->
            observed = branch
            exceptionKind = AutomationDisplayFailureDetail.safeExceptionKind(error)
            causeKind = AutomationDisplayFailureDetail.safeCauseKind(error)
        }
        assertFalse(success)
        assertEquals(AutomationDisplayFailureDetail.Branch.IME_SET_METHOD_INVOCATION, observed)
        assertEquals(
            AutomationDisplayFailureDetail.ExceptionKind.INVOCATION_TARGET_EXCEPTION,
            exceptionKind,
        )
        assertEquals(AutomationDisplayFailureDetail.ExceptionKind.ILLEGAL_STATE_EXCEPTION, causeKind)
    }

    @Test
    fun readbackMismatchIsFailureAndExactHiddenReadbackSucceeds() {
        var branch: AutomationDisplayFailureDetail.Branch? = null
        val mismatch = applyImePolicyHide(
            access = FakeImePolicyAccess(FailurePoint.READBACK_MISMATCH),
            displayId = 12,
            expectedPolicy = 1,
        ) { failureBranch, _ -> branch = failureBranch }
        assertFalse(mismatch)
        assertEquals(AutomationDisplayFailureDetail.Branch.IME_READBACK_MISMATCH, branch)

        var reported = false
        val success = applyImePolicyHide(
            access = FakeImePolicyAccess(failurePoint = null),
            displayId = 12,
            expectedPolicy = 1,
        ) { _, _ -> reported = true }
        assertTrue(success)
        assertFalse(reported)
    }

    private data class Case(
        val branch: AutomationDisplayFailureDetail.Branch,
        val failurePoint: FailurePoint,
    )

    private enum class FailurePoint {
        SERVICE_LOOKUP,
        SET_LOOKUP,
        SET_INVOCATION,
        GET_LOOKUP,
        GET_INVOCATION,
        READBACK_MISMATCH,
    }

    private class FakeImePolicyAccess(private val failurePoint: FailurePoint?) : ImePolicyAccess {
        private val service = Any()

        override fun getWindowManagerService(): Any? {
            if (failurePoint == FailurePoint.SERVICE_LOOKUP) {
                throw IllegalStateException("private exception text")
            }
            return service
        }

        override fun getWindowManagerServiceFallback(): Any? {
            if (failurePoint == FailurePoint.SERVICE_LOOKUP) {
                throw IllegalStateException("fallback exception text")
            }
            return null
        }

        override fun findSetDisplayImePolicy(service: Any): ((displayId: Int, policy: Int) -> Unit)? {
            if (failurePoint == FailurePoint.SET_LOOKUP) return null
            return { _, _ ->
                if (failurePoint == FailurePoint.SET_INVOCATION) {
                    throw InvocationTargetException(IllegalStateException("private exception text"))
                }
            }
        }

        override fun findGetDisplayImePolicy(service: Any): ((displayId: Int) -> Int?)? {
            if (failurePoint == FailurePoint.GET_LOOKUP) return null
            return { _ ->
                when (failurePoint) {
                    FailurePoint.GET_INVOCATION -> throw InvocationTargetException(
                        IllegalStateException("private exception text"),
                    )
                    FailurePoint.READBACK_MISMATCH -> 0
                    else -> 1
                }
            }
        }
    }
}
