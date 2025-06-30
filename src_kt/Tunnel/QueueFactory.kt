@file:OptIn(ExperimentalStdlibApi::class)
package com.example.nekit.Tunnel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlin.coroutines.coroutineContext

object QueueFactory {

    @OptIn(ExperimentalCoroutinesApi::class)
    val processingQueueDispatcher: kotlinx.coroutines.CloseableCoroutineDispatcher =
        newSingleThreadContext("NEKit.ProcessingQueue")

    fun getProcessingDispatcher(): CoroutineDispatcher {
        return processingQueueDispatcher
    }

    val processingScope: CoroutineScope = CoroutineScope(SupervisorJob() + processingQueueDispatcher)

    suspend fun isOnProcessingQueue(): Boolean {
        return coroutineContext[CoroutineDispatcher.Key] == processingQueueDispatcher
    }

    suspend fun <T> executeOnQueueSuspending(block: suspend () -> T): T {
        return if (isOnProcessingQueue()) {
            block()
        } else {
            kotlinx.coroutines.withContext(processingQueueDispatcher) {
                block()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> executeOnQueueBlocking(block: () -> T): T {
        return kotlinx.coroutines.runBlocking(processingQueueDispatcher) {
            block()
        }
    }

    fun getIOScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun shutdown() {
        processingQueueDispatcher.close()
    }
}