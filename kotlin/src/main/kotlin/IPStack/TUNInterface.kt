package nekit.IPStack

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import org.slf4j.LoggerFactory
import nekit.IPStack.Native.JnaTunDevice
import nekit.IPStack.Native.TunDeviceInterface
import java.io.IOException // For potential IO errors from TUN device
import nekit.Tunnel.QueueFactory

@OptIn(kotlin.ExperimentalStdlibApi::class)
/**
 * TUNInterface provides a mechanism to register IP Stacks (implementing IPStackProtocol)
 * to process IP packets from a virtual TUN interface, now using JnaTunDevice.
 */
open class TUNInterface {
    private val logger = LoggerFactory.getLogger(TUNInterface::class.java)
    private val tunDevice: TunDeviceInterface = JnaTunDevice()
    private var tunFd: Int = -1
    var interfaceName: String? = null
        private set
    var mtu: Int = 1500 // Default MTU, will be updated
        private set

    private val stacks: MutableList<IPStackProtocol> = mutableListOf()
    private val stacksMutex = Mutex() // To protect access to the 'stacks' list

    // Coroutine scope for the packet reading loop and other async operations within TUNInterface
    private val interfaceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    /**
     * Starts processing packets. This should be called after registering all IP stacks.
     * It opens the TUN device, configures it (conceptually), and starts the read loop.
     */
    open fun start() {
        interfaceScope.launch {
            if (tunFd >= 0) {
                logger.warn("TUNInterface already started with fd: {}", tunFd)
                return@launch
            }

            logger.info("Opening TUN device...")
            // TODO: Allow TUN device name to be configurable
            tunFd = tunDevice.openTun(null)

            if (tunFd < 0) {
                logger.error("Failed to open TUN device. Error code: {}", tunFd)
                // Potentially notify a higher-level component of this failure.
                return@launch
            }
            logger.info("TUN device opened successfully. File descriptor: {}", tunFd)

            interfaceName = tunDevice.getTunName(tunFd)
            if (interfaceName != null) {
                logger.info("TUN interface name: {}", interfaceName)
                mtu = tunDevice.getMTU(interfaceName!!) // Use actual name if available
                logger.info("TUN interface MTU set to: {}", mtu)
            } else {
                logger.warn("Could not get TUN interface name for fd: {}. Using default MTU {}.", tunFd, mtu)
                // mtu = tunDevice.getMTU("tun0") // Or some default name, but get_mtu_if might not work without a real name
            }

            // Start IP stacks (must be done after TUN device is ready conceptually)
            // This lock is for the stacks list, not TUN device operations themselves.
            stacksMutex.withLock {
                for (stack in stacks) {
                    try {
                        stack.start()
                    } catch (e: Exception) {
                        logger.error("Failed to start IP stack {}: {}", stack, e.message, e)
                    }
                }
            }
            logger.info("All IP stacks started. Starting packet reading loop on TUN fd: {}", tunFd)
            readPacketLoop() // Launch the reading loop
        }
    }

    /**
     * Stops processing packets. Closes the TUN device and stops all registered IP stacks.
     */
    open fun stop() {
        logger.info("Stopping TUNInterface...")
        interfaceScope.launch { // Ensure operations are within the scope to be cancelled
            if (tunFd >= 0) {
                logger.info("Closing TUN device with fd: {}", tunFd)
                tunDevice.closeTun(tunFd)
                tunFd = -1
                interfaceName = null
            }

            stacksMutex.withLock {
                for (stack in stacks) {
                    try {
                        stack.stop()
                    } catch (e: Exception) {
                        logger.error("Failed to stop IP stack {}: {}", stack, e.message, e)
                    }
                }
                stacks.clear()
            }
        }
        interfaceScope.cancel("TUNInterface stopped.") // Cancel all coroutines in this scope
        logger.info("TUNInterface fully stopped.")
    }

    /**
     * Registers a new IP stack.
     * Packets read from the TUN interface are passed to each IP stack in registration order
     * until one of them accepts and processes it.
     *
     * @param stack The IP stack to register.
     */
    open suspend fun register(stack: IPStackProtocol) {
        stacksMutex.withLock {
            stack.outputFunc = { packets, versions -> // versions might be ignored if packets are full IP packets
                if (tunFd >= 0) {
                    logger.debug("Outputting {} packets to TUN fd: {}", packets.size, tunFd)
                    for (packet in packets) {
                        // Error handling for individual writes can be added here if needed
                        val bytesWritten = tunDevice.writePacket(tunFd, packet, 0, packet.size)
                        if (bytesWritten < 0) {
                            logger.error("Error writing packet to TUN fd {}. Bytes written: {}", tunFd, bytesWritten)
                            // Potentially handle this error, e.g., stop the interface or notify stacks
                        } else if (bytesWritten < packet.size) {
                            logger.warn("Partial write to TUN fd {}: {} of {} bytes.", tunFd, bytesWritten, packet.size)
                        }
                    }
                } else {
                    logger.warn("Output function called but TUN device is not open (fd: {}). Dropping {} packets.", tunFd, packets.size)
                }
            }
            stacks.add(stack)
            logger.info("Registered IP stack: {}", stack)
        }
    }

    private fun readPacketLoop() {
        interfaceScope.launch(Dispatchers.IO) { // Use IO dispatcher for blocking read
            val buffer = ByteArray(mtu.coerceAtLeast(1500)) // Ensure buffer is at least a common MTU
            logger.info("Starting readPacketLoop on TUN fd: {}, buffer size: {}", tunFd, buffer.size)

            while (isActive && tunFd >= 0) {
                try {
                    val bytesRead = tunDevice.readPacket(tunFd, buffer, 0, buffer.size)

                    if (bytesRead > 0) {
                        val packet = buffer.copyOfRange(0, bytesRead)
                        logger.debug("Read {} bytes from TUN fd: {}", bytesRead, tunFd)

                        // Determine IP version from packet header for IPStackProtocol.input
                        // This is a simplified way; a proper IP header parser is better.
                        val version = when ((packet.firstOrNull()?.toInt() ?: 0) shr 4) {
                            4 -> AddressFamily.AF_INET
                            6 -> AddressFamily.AF_INET6
                            else -> {
                                logger.warn("Unknown IP version in packet from TUN fd: {}. First byte: {}", tunFd, packet.firstOrNull()?.toString(16))
                                null // Or a specific error indicator
                            }
                        }

                        // Pass to stacks. This part can be run on `interfaceScope`'s context (default or processing)
                        // if `stack.input` is not blocking for too long, or launch another coroutine.
                        // For now, direct call within the IO loop's coroutine.
                        withContext(interfaceScope.coroutineContext) { // Switch back to main processing context if needed
                            var accepted = false
                            val currentStacks = stacksMutex.withLock { ArrayList(stacks) } // Iterate a copy
                            for (stack in currentStacks) {
                                if (stack.input(packet, version?.value)) {
                                    accepted = true
                                    break
                                }
                            }
                            if (!accepted) {
                                logger.trace("Packet from TUN fd {} not accepted by any stack ({} bytes)", tunFd, bytesRead)
                            }
                        }
                    } else if (bytesRead == 0) {
                        // Non-blocking read returned 0, meaning no data currently. This is fine.
                        // Yield to prevent tight loop if JNA read is truly non-blocking and returns 0 often.
                        yield()
                    } else { // bytesRead < 0
                        logger.error("Error reading from TUN fd {}. Read returned: {}. Stopping read loop.", tunFd, bytesRead)
                        // Consider specific error codes. EAGAIN/EWOULDBLOCK might mean try again.
                        // For simplicity, any negative value is treated as a fatal read error here.
                        // Attempt to close the TUN interface gracefully.
                        // Launch in a new coroutine to not block the IO thread if stop is complex.
                        launch(interfaceScope.coroutineContext) { stop() }
                        break
                    }
                } catch (e: CancellationException) {
                    logger.info("readPacketLoop on TUN fd {} cancelled.", tunFd)
                    break
                } catch (e: IOException) { // Specific IO errors from read
                    logger.error("IOException in readPacketLoop on TUN fd {}: {}", tunFd, e.message, e)
                    if (tunFd >=0) { // Only try to stop if fd seems valid
                         launch(interfaceScope.coroutineContext) { stop() }
                    }
                    break
                } catch (e: Exception) { // Other generic errors
                    logger.error("Generic error in readPacketLoop on TUN fd {}: {}", tunFd, e.message, e)
                     if (tunFd >=0) {
                         launch(interfaceScope.coroutineContext) { stop() }
                    }
                    break
                }
            }
            logger.info("readPacketLoop finished for TUN fd: {}.", tunFd)
            // If loop exited due to fd < 0 or !isActive, ensure stop() is called if not already.
            // This might be redundant if errors/cancellation already called stop().
            if (isActive) { // If scope itself is still active but loop broke
                 launch(interfaceScope.coroutineContext) { stop() }
            }
        }
    }
}
