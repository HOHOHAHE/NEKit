package com.example.nekit.Socket.AdapterSocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.Factory.AdapterFactory
import org.slf4j.LoggerFactory
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

class SpeedAdapter(
    val factories: List<AdapterFactory>,
    val testUrl: String = "http://www.google.com/generate_204"
) : AdapterSocket() {

    private val logger = LoggerFactory.getLogger(SpeedAdapter::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fastestAdapter: AdapterSocket? = null

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session)
        logger.info("Finding fastest adapter for session: ${session.host}:${session.port}")

        scope.launch {
            val results = mutableListOf<Pair<AdapterSocket, Long>>()
            val jobs = factories.map { factory ->
                async {
                    val adapter = factory.getAdapter(session)
                    val startTime = System.currentTimeMillis()
                    try {
                        // This is a simplified test. A real implementation would need a more
                        // robust way to connect and measure speed without fully opening the socket.
                        adapter.openSocketWith(ConnectSession(host = testUrl, port = 80))
                        // In a real scenario, you'd wait for a successful connection callback.
                        // For this example, we'll just assume a fixed delay for testing.
                        delay(500) // Placeholder for connection time
                        val endTime = System.currentTimeMillis()
                        results.add(Pair(adapter, endTime - startTime))
                    } catch (e: Exception) {
                        logger.warn("Adapter ${adapter.typeName} failed speed test.", e)
                    }
                }
            }
            jobs.awaitAll()

            val winner = results.minByOrNull { it.second }
            if (winner != null) {
                logger.info("Fastest adapter is ${winner.first.typeName} with ${winner.second} ms.")
                fastestAdapter = winner.first
                // Now, open the real session with the fastest adapter
                fastestAdapter?.openSocketWith(session)
                _rawSocket = fastestAdapter?.rawSocket
                _rawSocket?.delegate = WeakReference(this@SpeedAdapter)
            } else {
                logger.error("No adapter succeeded in speed test.")
                forceDisconnect(Exception("No available adapter."))
            }
        }
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        fastestAdapter?.forceDisconnect(becauseOf)
        super.forceDisconnect(becauseOf)
    }
}