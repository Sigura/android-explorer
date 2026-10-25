package com.mediaviewer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

private val abandonScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs a blocking call that may hang (a stuck USB drive) with a watchdog (README §2.1).
 * On timeout or cancellation the caller continues at once; the call is abandoned and its result
 * dropped. Returns null on timeout or error.
 */
suspend fun <T : Any> abandonable(timeoutMs: Long, block: () -> T?): T? {
    val job = abandonScope.async { runCatching { runInterruptible { block() } }.getOrNull() }
    return try {
        withTimeoutOrNull(timeoutMs) { job.await() }.also { if (it == null) job.cancel() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        job.cancel()
        throw e
    }
}
