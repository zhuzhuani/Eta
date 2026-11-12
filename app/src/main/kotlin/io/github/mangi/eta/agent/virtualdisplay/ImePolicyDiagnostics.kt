package io.github.mangi.eta.agent.virtualdisplay

/** Reflection seam for the display-specific IME isolation policy. */
internal interface ImePolicyAccess {
    fun getWindowManagerService(): Any?
    fun findSetDisplayImePolicy(service: Any): ((displayId: Int, policy: Int) -> Unit)?
    fun findGetDisplayImePolicy(service: Any): ((displayId: Int) -> Int?)?
}

/** Runs the actual fail-closed IME isolation flow through an injectable system-service seam. */
internal fun applyImePolicyHide(
    access: ImePolicyAccess,
    displayId: Int,
    expectedPolicy: Int,
    onFailure: (AutomationDisplayFailureDetail.Branch, Throwable?) -> Unit,
): Boolean {
    fun fail(branch: AutomationDisplayFailureDetail.Branch, error: Throwable? = null): Boolean {
        onFailure(branch, error)
        return false
    }

    val service = try {
        access.getWindowManagerService()
    } catch (error: Throwable) {
        return fail(AutomationDisplayFailureDetail.Branch.IME_SERVICE_LOOKUP, error)
    } ?: return fail(AutomationDisplayFailureDetail.Branch.IME_SERVICE_LOOKUP)

    val setter = try {
        access.findSetDisplayImePolicy(service)
    } catch (error: Throwable) {
        return fail(AutomationDisplayFailureDetail.Branch.IME_SET_METHOD_LOOKUP, error)
    } ?: return fail(AutomationDisplayFailureDetail.Branch.IME_SET_METHOD_LOOKUP)

    try {
        setter(displayId, expectedPolicy)
    } catch (error: Throwable) {
        return fail(AutomationDisplayFailureDetail.Branch.IME_SET_METHOD_INVOCATION, error)
    }

    val getter = try {
        access.findGetDisplayImePolicy(service)
    } catch (error: Throwable) {
        return fail(AutomationDisplayFailureDetail.Branch.IME_GET_METHOD_LOOKUP, error)
    } ?: return fail(AutomationDisplayFailureDetail.Branch.IME_GET_METHOD_LOOKUP)

    val actualPolicy = try {
        getter(displayId)
    } catch (error: Throwable) {
        return fail(AutomationDisplayFailureDetail.Branch.IME_GET_METHOD_INVOCATION, error)
    }
    if (actualPolicy != expectedPolicy) {
        return fail(AutomationDisplayFailureDetail.Branch.IME_READBACK_MISMATCH)
    }
    return true
}

/** Production adapter uses reflection so API/signature variation remains a fail-closed result. */
internal class ReflectiveImePolicyAccess : ImePolicyAccess {
    override fun getWindowManagerService(): Any? {
        val global = Class.forName("android.view.WindowManagerGlobal")
        return global.getMethod("getWindowManagerService").invoke(null)
    }

    override fun findSetDisplayImePolicy(service: Any): ((displayId: Int, policy: Int) -> Unit)? {
        val method = service.javaClass.methods.firstOrNull {
            it.name == "setDisplayImePolicy" && it.parameterCount == 2
        } ?: return null
        return { displayId, policy -> method.invoke(service, displayId, policy) }
    }

    override fun findGetDisplayImePolicy(service: Any): ((displayId: Int) -> Int?)? {
        val method = service.javaClass.methods.firstOrNull {
            it.name == "getDisplayImePolicy" && it.parameterCount == 1
        } ?: return null
        return { displayId -> method.invoke(service, displayId) as? Int }
    }
}
