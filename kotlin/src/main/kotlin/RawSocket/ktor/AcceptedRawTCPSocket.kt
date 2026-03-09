package nekit.RawSocket.ktor

import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetSocketAddress

import nekit.Utils.IPAddress
import nekit.Utils.Port
import nekit.RawSocket.protocol.RawTCPSocketProtocol
import nekit.RawSocket.protocol.RawTCPSocketDelegate

/**
 * Adapts a Ktor `Socket` (representing an accepted client connection)
 * to the `RawTCPSocketProtocol` interface expected by the rest of the system.
 * 
 * This implementation uses Ktor's coroutine-based sockets for better integration
 * with the existing coroutine-based architecture.
 */
class AcceptedRawTCPSocket(
    private val socket: Socket
) : RawTCPSocketProtocol {

    private val logger = LoggerFactory.getLogger(AcceptedRawTCPSocket::class.java)
    override var delegate: WeakReference<RawTCPSocketDelegate?>? = null
    
    private val readChannel: ByteReadChannel = socket.openReadChannel()
    private val writeChannel: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)
    private val socketScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Marked true once cleanup() is initiated, used to suppress write-failure callbacks on an already-closing socket. */
    @Volatile private var _isClosed = false

    /**
     * Serialized write queue: all write() calls enqueue data here.
     * A single actor coroutine drains this queue, ensuring writeFully() is never called concurrently.
     */
    private val writeQueue = Channel<Pair<ByteArray, () -> Unit>>(Channel.BUFFERED)

    init {
        logger.info("AcceptedRawTCPSocket created for socket: {}", socket)
        // Single writer actor - the ONLY coroutine that calls writeChannel.writeFully()
        socketScope.launch {
            for ((data, onWritten) in writeQueue) {
                if (_isClosed) break
                try {
                    if (!writeChannel.isClosedForWrite) {
                        writeChannel.writeFully(data)
                        writeChannel.flush()
                        onWritten()
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    val errorMsg = when {
                        e.message?.contains("Connection reset") == true -> "Connection reset by peer"
                        e.message?.contains("Broken pipe") == true -> "Broken pipe - connection closed"
                        e.message?.contains("closed") == true -> "Connection closed"
                        e is IOException -> "IO error during write"
                        else -> "Write failed: ${e.javaClass.simpleName}"
                    }
                    if (_isClosed || socket.isClosed || writeChannel.isClosedForWrite ||
                        e.message?.contains("Broken pipe") == true ||
                        e.message?.contains("closed") == true) {
                        logger.warn("Suppressed write error (connection already closing): {}", errorMsg)
                    } else {
                        logger.error("Failed to write {} bytes to socket: {}", data.size, errorMsg, e)
                        delegate?.get()?.didErrorOccur(e, this@AcceptedRawTCPSocket)
                    }
                }
            }
        }
    }

    override val isConnected: Boolean
        get() = !socket.isClosed && !readChannel.isClosedForRead && !writeChannel.isClosedForWrite

    // For an accepted socket, source/destination are relative to the server.
    // socket.localAddress() is server's listening address.
    // socket.remoteAddress() is client's address.
    // RawTCPSocketProtocol's source/dest might be interpreted differently by consumers (e.g., ProxySocket).
    // ProxySocket expects sourceIP/Port to be the client, and destIP/Port to be the original target.
    // This adapter, wrapping an accepted socket, *is* the client connection from server's view.
    // So, its "source" is the client (remoteAddress) and "destination" is the server itself (localAddress).

    override val sourceIPAddress: IPAddress?
        get() = try {
            (socket.remoteAddress as? InetSocketAddress)?.address?.hostAddress?.let {
                IPAddress.parse(it)
            }
        } catch (e: Exception) {
            logger.debug("Error getting source IP address: {}", e.message)
            null
        }

    override val sourcePort: Port?
        get() = try {
            (socket.remoteAddress as? InetSocketAddress)?.port?.toUShort()?.let { Port(it.toInt()) }
        } catch (e: Exception) {
            logger.debug("Error getting source port: {}", e.message)
            null
        }

    override val destinationIPAddress: IPAddress?
        get() = try {
            (socket.localAddress as? InetSocketAddress)?.address?.hostAddress?.let {
                IPAddress.parse(it)
            }
        } catch (e: Exception) {
            logger.debug("Error getting destination IP address: {}", e.message)
            null
        }

    override val destinationPort: Port?
        get() = try {
            (socket.localAddress as? InetSocketAddress)?.port?.toUShort()?.let { Port(it.toInt()) }
        } catch (e: Exception) {
            logger.debug("Error getting destination port: {}", e.message)
            null
        }

    override suspend fun connectTo(host: String, port: Int, enableTLS: Boolean, tlsSettings: Map<String, Any>?) {
        // This method is for initiating an outbound connection.
        // An accepted socket is already connected. Calling this is likely an error.
        logger.error("connectTo called on an already accepted socket. This is unexpected.")
        delegate?.get()?.didErrorOccur(IllegalStateException("connectTo cannot be called on an accepted socket."), this)
    }

    override fun write(data: ByteArray) {
        if (_isClosed || socket.isClosed) {
            logger.warn("write called on closed socket. Data not sent.")
            return
        }
        // Enqueue write; the single actor coroutine will call writeFully() serially.
        val offered = writeQueue.trySend(Pair(data) {
            delegate?.get()?.didWrite(data, this@AcceptedRawTCPSocket)
        })
        if (offered.isFailure) {
            logger.warn("Write queue full or closed, dropping {} bytes", data.size)
        }
    }

    override fun readData() {
        if (_isClosed || socket.isClosed || readChannel.isClosedForRead) {
            logger.warn("readData called on closed socket or read channel.")
            return
        }

        // Launch coroutine for async read operation
        socketScope.launch {
            try {
                // 使用更大的緩衝區提高性能
                val buffer = ByteArray(65536) // 64KB buffer for better performance
                val bytesRead = readChannel.readAvailable(buffer)
                
                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    logger.trace("Read {} bytes from socket", bytesRead)
                    delegate?.get()?.didRead(data, this@AcceptedRawTCPSocket)
                } else if (bytesRead == -1) {
                    // End of stream
                    logger.info("Socket reached end of stream")
                    delegate?.get()?.didDisconnect(this@AcceptedRawTCPSocket)
                    return@launch
                } else if (bytesRead == 0) {
                    // No data available, exit gracefully instead of blocking
                    logger.trace("No data available, exiting read")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    val errorMsg = when {
                        e.message?.contains("Connection reset") == true -> "Connection reset by peer"
                        e.message?.contains("closed") == true -> "Connection closed during read"
                        e is IOException -> "IO error during read operation"
                        else -> "Read operation failed: ${e.javaClass.simpleName}"
                    }
                    logger.error("Error reading from socket: {}", errorMsg, e)
                    delegate?.get()?.didErrorOccur(e, this@AcceptedRawTCPSocket)
                }
            } finally {
                if (socket.isClosed || readChannel.isClosedForRead) {
                    delegate?.get()?.didDisconnect(this@AcceptedRawTCPSocket)
                }
            }
        }
    }

    override fun readDataTo(length: Int) {
        socketScope.launch {
            if (_isClosed || socket.isClosed || readChannel.isClosedForRead) {
                logger.warn("readDataTo called on closed socket or read channel.")
                return@launch
            }

            try {
                val buffer = ByteArray(length)
                readChannel.readFully(buffer, 0, length)
                logger.trace("Read exactly {} bytes from socket", length)
                delegate?.get()?.didRead(buffer, this@AcceptedRawTCPSocket)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    val errorMsg = when {
                        e.message?.contains("Connection reset") == true -> "Connection reset by peer"
                        e.message?.contains("closed") == true -> "Connection closed during read"
                        e is IOException -> "IO error during read operation"
                        else -> "Read operation failed: ${e.javaClass.simpleName}"
                    }
                    logger.error("Error reading {} bytes from socket: {}", length, errorMsg, e)
                    delegate?.get()?.didErrorOccur(e, this@AcceptedRawTCPSocket)
                } else {
                    // CancellationException - do nothing
                }
            }
        }
    }

    override fun readDataTo(data: ByteArray) {
        socketScope.launch {
            if (_isClosed || socket.isClosed || readChannel.isClosedForRead) {
                logger.warn("readDataTo called on closed socket or read channel.")
                return@launch
            }

            try {
                readChannel.readFully(data, 0, data.size)
                logger.trace("Read exactly {} bytes into provided buffer", data.size)
                delegate?.get()?.didRead(data, this@AcceptedRawTCPSocket)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    val errorMsg = when {
                        e.message?.contains("Connection reset") == true -> "Connection reset by peer"
                        e.message?.contains("closed") == true -> "Connection closed during read"
                        e is IOException -> "IO error during read operation"
                        else -> "Read operation failed: ${e.javaClass.simpleName}"
                    }
                    logger.error("Error reading {} bytes into buffer: {}", data.size, errorMsg, e)
                    delegate?.get()?.didErrorOccur(e, this@AcceptedRawTCPSocket)
                } else {
                    // CancellationException - do nothing
                }
            }
        }
    }

    override fun readDataTo(data: ByteArray, maxLength: Int) {
        socketScope.launch {
            if (_isClosed || socket.isClosed || readChannel.isClosedForRead) {
                logger.warn("readDataTo called on closed socket or read channel.")
                return@launch
            }

            val actualLength = minOf(maxLength, data.size)
            try {
                readChannel.readFully(data, 0, actualLength)
                logger.trace("Read exactly {} bytes into provided buffer (max: {})", actualLength, maxLength)
                // Only return the actually read portion
                val result = if (actualLength < data.size) {
                    data.copyOf(actualLength)
                } else {
                    data
                }
                delegate?.get()?.didRead(result, this@AcceptedRawTCPSocket)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    val errorMsg = when {
                        e.message?.contains("Connection reset") == true -> "Connection reset by peer"
                        e.message?.contains("closed") == true -> "Connection closed during read"
                        e is IOException -> "IO error during read operation"
                        else -> "Read operation failed: ${e.javaClass.simpleName}"
                    }
                    logger.error("Error reading {} bytes into buffer: {}", actualLength, errorMsg, e)
                    delegate?.get()?.didErrorOccur(e, this@AcceptedRawTCPSocket)
                } else {
                    // CancellationException - do nothing
                }
            }
        }
    }

    override fun disconnect(becauseOf: Throwable?) {
        logger.info("disconnect() called. Closing socket gracefully. Cause: {}", becauseOf?.message)
        cleanup()
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        logger.info("forceDisconnect() called. Closing socket immediately. Cause: {}", becauseOf?.message)
        cleanup()
    }

    private fun cleanup() {
        _isClosed = true
        writeQueue.close()
        socketScope.cancel()
        try {
            writeChannel.close()
            readChannel.cancel()
            socket.close()
        } catch (e: Exception) {
            logger.debug("Error during cleanup: {}", e.message)
        }
    }
}