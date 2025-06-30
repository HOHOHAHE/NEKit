package com.example.nekit.RawSocket

import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
// Removed Mutex imports - no longer needed for simplified reading
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetSocketAddress

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port

/**
 * Adapts a Ktor `Socket` (representing an accepted client connection)
 * to the `RawTCPSocketProtocol` interface expected by the rest of the system.
 * 
 * This implementation uses Ktor's coroutine-based sockets for better integration
 * with the existing coroutine-based architecture.
 */
class KtorAcceptedRawSocketAdapter(
    private val socket: Socket
) : RawTCPSocketProtocol {

    private val logger = LoggerFactory.getLogger(KtorAcceptedRawSocketAdapter::class.java)
    override var delegate: WeakReference<RawTCPSocketDelegate?>? = null
    
    private val readChannel: ByteReadChannel = socket.openReadChannel()
    private val writeChannel: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)
    // Removed readMutex - no longer needed for simplified reading

    init {
        logger.info("KtorAcceptedRawSocketAdapter created for socket: {}", socket)
    }

    override val isConnected: Boolean
        get() = !socket.isClosed

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

    override suspend fun write(data: ByteArray) {
        if (socket.isClosed) {
            logger.warn("write called on closed socket. Data not sent.")
            delegate?.get()?.didErrorOccur(IOException("Socket is closed, write failed."), this)
            return
        }

        try {
            logger.debug("Writing {} bytes to socket", data.size)
            writeChannel.writeFully(data)
            writeChannel.flush()
            logger.trace("Successfully wrote {} bytes to socket", data.size)
            delegate?.get()?.didWrite(data, this)
        } catch (e: Exception) {
            logger.error("Failed to write {} bytes to socket: {}", data.size, e.message, e)
            delegate?.get()?.didErrorOccur(e, this)
            throw e
        }
    }

    override suspend fun readData() {
        if (socket.isClosed) {
            logger.warn("readData called on closed socket.")
            return
        }

        try {
            while (!readChannel.isClosedForRead && !socket.isClosed) {
                val buffer = ByteArray(8192) // 8KB buffer
                val bytesRead = readChannel.readAvailable(buffer)
                
                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    logger.trace("Read {} bytes from socket", bytesRead)
                    delegate?.get()?.didRead(data, this@KtorAcceptedRawSocketAdapter)
                } else if (bytesRead == -1) {
                    // End of stream
                    logger.info("Socket reached end of stream")
                    break
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                logger.error("Error reading from socket: {}", e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorAcceptedRawSocketAdapter)
            }
        } finally {
            if (socket.isClosed) {
                delegate?.get()?.didDisconnect(this@KtorAcceptedRawSocketAdapter)
            }
        }
    }

    // Removed readDataTo methods and findDelimiterIndex - complex reading logic should be handled at application layer

    override fun disconnect(becauseOf: Throwable?) {
        logger.info("disconnect() called. Closing socket gracefully. Cause: {}", becauseOf?.message)
        cleanup()
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        logger.info("forceDisconnect() called. Closing socket immediately. Cause: {}", becauseOf?.message)
        cleanup()
    }

    private fun cleanup() {
        try {
            writeChannel.close()
            readChannel.cancel()
            socket.close()
        } catch (e: Exception) {
            logger.debug("Error during cleanup: {}", e.message)
        }
    }
}