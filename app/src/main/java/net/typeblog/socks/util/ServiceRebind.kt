package net.typeblog.socks.util

/**
 * Shared escalating-but-bounded rebind delay for the cross-process AIDL
 * binds (UI ViewModel and floating bubble). Attempt counting, watchdogs,
 * and retry policy stay with each caller; only the step function is shared
 * so the 200/1000/3000 ladder can never drift apart.
 */
object ServiceRebind {

    @JvmStatic
    fun backoffDelayMs(attempts: Int): Long = when {
        attempts <= 3 -> 200L
        attempts <= 10 -> 1000L
        else -> 3000L
    }
}
