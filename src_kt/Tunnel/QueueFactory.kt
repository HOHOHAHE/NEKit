package com.example.nekit.Tunnel

import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

/**
 * Factory object for providing shared CoroutineDispatchers,
 * mimicking the role of GCD DispatchQueue provider in the original Swift code.
 */
object QueueFactory {

    /**
     * A dedicated single-threaded dispatcher for serial processing tasks,
     * similar to "NEKit.ProcessingQueue" from Swift.
     * Using CloseableCoroutineDispatcher to allow closing the underlying thread if needed on application shutdown.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // For newSingleThreadContext
    val processingQueueDispatcher: kotlinx.coroutines.CloseableCoroutineDispatcher =
        newSingleThreadContext("NEKit.ProcessingQueue")

    /**
     * Returns the shared serial processing dispatcher.
     */
    fun getProcessingDispatcher(): CoroutineDispatcher {
        return processingQueueDispatcher
    }

    /**
     * Provides a general-purpose CoroutineScope for launching tasks on the processing dispatcher.
     * Uses SupervisorJob to prevent failure of one child from cancelling the scope.
     */
    val processingScope: CoroutineScope = CoroutineScope(SupervisorJob() + processingQueueDispatcher)


    /**
     * Checks if the current coroutine is running on the main processing dispatcher.
     * This is an approximation of Swift's `DispatchQueue.getSpecific` and `onQueue()`.
     * Note: This check can be fragile and might not work reliably with all dispatcher configurations
     * or customized CoroutineContexts. It's generally better to design code such that this check is not needed,
     * e.g., by using `withContext` to switch to the desired dispatcher.
     *
     * @return True if the current coroutine's dispatcher appears to be the processingQueueDispatcher.
     */
    suspend fun isOnProcessingQueue(): Boolean {
        return coroutineContext[CoroutineDispatcher.Key] == processingQueueDispatcher
    }

    /**
     * Executes a block of code synchronously on the processing queue dispatcher.
     * If the current coroutine is already on the processing queue, the block is executed directly.
     * Otherwise, it dispatches the block to the processing queue and waits for its completion.
     *
     * This is a suspending function. If called from a non-coroutine context and blocking is required,
     * `runBlocking` would be needed around the call to this function or the block's execution.
     *
     * The original Swift `executeOnQueueSynchronizedly` was blocking. This suspending version
     * is more idiomatic for coroutines. If strict blocking from any thread is needed,
     * see `executeOnQueueBlocking`.
     *
     * @param block The block of code to execute.
     * @return The result of the block.
     */
    suspend fun <T> executeOnQueueSuspending(block: suspend () -> T): T {
        return if (isOnProcessingQueue()) {
            block()
        } else {
            withContext(processingQueueDispatcher) {
                block()
            }
        }
    }

    /**
     * Executes a non-suspending block of code synchronously (blocking the current thread)
     * on the processing queue dispatcher.
     *
     * WARNING: Avoid using this from within a coroutine that runs on the same `processingQueueDispatcher`
     * if `isOnProcessingQueue()` check is not perfectly reliable, as it could lead to deadlock.
     * Prefer using `executeOnQueueSuspending` or `Mutex.withLock` within coroutines for synchronization.
     * This function is provided for cases where blocking is strictly required from a non-coroutine context
     * or a coroutine on a different dispatcher.
     *
     * @param block The non-suspending block of code to execute.
     * @return The result of the block.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun <T> executeOnQueueBlocking(block: () -> T): T {
        // The isOnProcessingQueue check is difficult to make 100% robust for all scenarios here
        // without more context on the calling thread when it's not a coroutine.
        // For simplicity and to match the Swift version's intent of "if not on queue, then sync to it":
        // We will just use runBlocking on the target dispatcher.
        // If the current thread *is* the thread of processingQueueDispatcher, runBlocking will still work
        // by effectively running the block if the dispatcher is not busy, or queueing it.
        // However, if this function is called from a coroutine already on processingQueueDispatcher,
        // using runBlocking here can lead to issues (deadlock if dispatcher is single-threaded and busy).
        // This is why a suspending version `executeOnQueueSuspending` is preferred.

        // A direct check against thread name (if newSingleThreadContext("name") is used) is an option
        // but fragile. Example: if (Thread.currentThread().name == "NEKit.ProcessingQueue") return block()

        // For a general purpose blocking utility from any thread:
        return runBlocking(processingQueueDispatcher) {
            block()
        }
    }

    /**
     * Helper to get a general-purpose IO CoroutineScope.
     * This was used as a placeholder in some translations.
     * It's different from the specific `processingScope`.
     */
    fun getIOScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // To be called at application shutdown to release resources if newSingleThreadContext was used.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shutdown() {
        processingQueueDispatcher.close()
    }
}
