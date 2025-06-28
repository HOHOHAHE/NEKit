import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

import org.slf4j.LoggerFactory

// Assuming IPStackProtocol.kt and other necessary interfaces/classes are available.
// Assuming QueueFactory.kt provides CoroutineScope/Dispatchers.

// --- Placeholder for Native Packet Flow (TUN/TAP Interface) ---
// TODO: This interface needs a concrete JNI/JNA based implementation for actual TUN/TAP interaction.
interface NativePacketFlowInterface {
    /**
     * Reads packets from the native tunnel interface.
     * This should be a suspending function for use in coroutines.
     * @return A Pair containing a list of packet data and a list of corresponding protocol numbers (e.g., AF_INET).
     *         Returns null if the flow is closed or an unrecoverable error occurs.
     */
    suspend fun readPackets(): Pair<List<ByteArray>, List<Int>>?

    /**
     * Writes packets to the native tunnel interface.
     * @param packets A list of ByteArrays, each representing a packet.
     * @param protocols A list of protocol numbers corresponding to each packet.
     * @return True if writing was successful (or successfully queued), false otherwise.
     */
    fun writePackets(packets: List<ByteArray>, protocols: List<Int>): Boolean
}

// Example placeholder implementation for NativePacketFlowInterface
// TODO: Replace with actual JNI/JNA based TUN/TAP implementation.
class PlaceholderNativePacketFlow : NativePacketFlowInterface {
    private val logger = LoggerFactory.getLogger(PlaceholderNativePacketFlow::class.java)
    private var job: Job? = null
    private val readScope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    override suspend fun readPackets(): Pair<List<ByteArray>, List<Int>>? {
        // Simulate asynchronous read with a delay. In real JNI, this would block or use async I/O.
        // This placeholder will return an empty list after a delay to allow the loop to run.
        // To stop it, cancel its scope.
        return try {
            withContext(readScope.coroutineContext) { // Ensure it's cancellable
                delay(1000) // Simulate waiting for packets
                logger.info("readPackets returning empty list. (TODO: Implement actual TUN read)")
                Pair(emptyList(), emptyList())
            }
        } catch (e: CancellationException) {
            logger.info("readPackets cancelled.")
            null
        }
    }

    override fun writePackets(packets: List<ByteArray>, protocols: List<Int>): Boolean {
        logger.info("Writing {} packets. (TODO: Implement actual TUN write)", packets.size)
        packets.forEachIndexed { index, bytes ->
            // logger.debug("  Packet {}: {} bytes, Proto: {}", index + 1, bytes.size, protocols.getOrNull(index) ?: "N/A")
        }
        return true
    }

    fun stopReading() { // Helper to stop the placeholder's simulated reading
        readScope.cancel()
    }
}
// --- End Placeholder ---


/**
 * TUNInterface provides a mechanism to register IP Stacks (implementing IPStackProtocol)
 * to process IP packets from a virtual TUN interface (represented by NativePacketFlowInterface).
 */
open class TUNInterface(
    private var packetFlow: NativePacketFlowInterface? // Nullable to allow it to be cleared on stop
) {
    private val logger = LoggerFactory.getLogger(TUNInterface::class.java)
    private val stacks: MutableList<IPStackProtocol> = mutableListOf()
    private val stacksMutex = Mutex() // To protect access to the 'stacks' list and 'packetFlow' state

    // Coroutine scope for the packet reading loop and other async operations within TUNInterface
    private val interfaceScope = CoroutineScope(SupervisorJob() + (QueueFactory.getIOScope().coroutineContext[CoroutineDispatcher] ?: Dispatchers.Default))


    /**
     * Starts processing packets. This should be called after registering all IP stacks.
     * A stopped interface should ideally not be restarted; create a new instance instead.
     */
    open fun start() {
        interfaceScope.launch {
            stacksMutex.withLock {
                if (packetFlow == null) {
                    logger.error("Packet flow is not set, cannot start.")
                    return@launch
                }
                for (stack in stacks) {
                    try {
                        stack.start()
                    } catch (e: Exception) {
                        logger.error("Failed to start stack {}: {}", stack, e.message, e)
                        // Decide if we should continue starting other stacks or stop.
                    }
                }
            }
            logger.info("All stacks started. Starting packet reading loop.")
            readPacketLoop() // Launch the reading loop
        }
    }

    /**
     * Stops processing packets. This should be called before releasing the interface.
     * It stops all registered IP stacks and cancels the packet reading loop.
     */
    open fun stop() {
        logger.info("Stopping...")
        interfaceScope.launch { // Ensure operations are within the scope to be cancelled
            stacksMutex.withLock {
                packetFlow = null // Prevent further reads/writes by clearing the reference
                                  // If using PlaceholderNativePacketFlow, call its stopReading method.
                                  // (this.packetFlow as? PlaceholderNativePacketFlow)?.stopReading()


                for (stack in stacks) {
                    try {
                        stack.stop()
                    } catch (e: Exception) {
                        logger.error("Failed to stop stack {}: {}", stack, e.message, e)
                    }
                }
                stacks.clear()
            }
        }
        interfaceScope.cancel("TUNInterface stopped.") // Cancel all coroutines in this scope
        logger.info("Stopped.")
    }

    /**
     * Registers a new IP stack.
     * Packets read from the TUN interface are passed to each IP stack in registration order
     * until one of them accepts and processes it.
     *
     * @param stack The IP stack to register.
     */
    open suspend fun register(stack: IPStackProtocol) { // Made suspend fun for mutex
        stacksMutex.withLock {
            // The outputFunc captures `this` TUNInterface.
            // If `TUNInterface` might be destroyed while `stack` (and its `outputFunc`) still exists,
            // a WeakReference to `this` or `packetFlow` inside the lambda might be needed.
            // However, typically stacks are stopped and cleared before TUNInterface is destroyed.
            stack.outputFunc = { packets, versions ->
                val currentPacketFlow = this.packetFlow // Capture current flow state under lock
                if (currentPacketFlow != null) {
                     // Launch write on a dispatcher if writePackets can block or is heavy.
                     // interfaceScope.launch { currentPacketFlow.writePackets(packets, versions) }
                     // For now, direct call:
                    currentPacketFlow.writePackets(packets, versions)
                } else {
                    logger.warn("Output function called but packetFlow is null (interface stopped?). Dropping {} packets.", packets.size)
                }
            }
            stacks.add(stack)
            logger.info("Registered stack: {}", stack)
        }
    }

    private fun readPacketLoop() {
        interfaceScope.launch {
            while (isActive) { // Loop while the scope is active (not cancelled by stop())
                val currentPacketFlow = packetFlow // Read under no lock, but check for null
                if (currentPacketFlow == null) {
                    logger.info("Packet flow is null, stopping read loop.")
                    break
                }

                try {
                    val result = currentPacketFlow.readPackets() // Suspending call
                    if (result == null) {
                        logger.info("readPackets returned null, indicating flow closed or error. Stopping loop.")
                        break // Exit loop if flow is closed or error
                    }

                    val (packets, versions) = result
                    if (packets.isNotEmpty()) {
                        // Process packets in a separate coroutine to free up the read loop quickly,
                        // if stack.input can be slow or blocking.
                        // For now, processing sequentially in this loop.
                        // Consider dispatcher for this block if `stack.input` is heavy.
                        // launch(Dispatchers.Default) {
                        for ((i, packet) in packets.withIndex()) {
                            val version = versions.getOrNull(i)
                            var accepted = false
                            // Iterate over a copy of stacks if modification during iteration is possible
                            // and not handled by stacksMutex (e.g. if stack.input itself calls register/unregister)
                            val currentStacks = stacksMutex.withLock { ArrayList(stacks) }
                            for (stack in currentStacks) {
                                if (stack.input(packet, version)) {
                                    accepted = true
                                    break // Packet was accepted by this stack
                                }
                            }
                            if (!accepted) {
                                // println("VERBOSE: TUNInterface: Packet not accepted by any stack: ${packet.take(20).joinToString { it.toUByte().toString(16) }}")
                            }
                        }
                        // }
                    }
                } catch (e: CancellationException) {
                    logger.info("readPacketLoop cancelled.")
                    break
                } catch (e: Exception) {
                    logger.error("Error in readPacketLoop: {}", e.message, e)
                    // Avoid tight loop on persistent errors from readPackets
                    delay(1000)
                }
            }
            logger.info("readPacketLoop finished.")
        }
    }
}
