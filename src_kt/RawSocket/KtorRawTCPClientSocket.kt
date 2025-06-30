package com.example.nekit.RawSocket

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port

/**
 * Ktor-based implementation of RawTCPSocketProtocol for client-side TCP connections.
 * This implementation uses Ktor's coroutine-based sockets for better integration with the existing architecture.
 */
class KtorRawTCPClientSocket : RawTCPSocketProtocol {

    private val logger = LoggerFactory.getLogger(KtorRawTCPClientSocket::class.java)
    private var socket: Socket? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null
    private val selectorManager = ActorSelectorManager(Dispatchers.IO)
    // Removed readMutex - single read method eliminates concurrency issues
    
    override var delegate: WeakReference<RawTCPSocketDelegate?>? = null

    // Store connection parameters for IP/Port properties
    private var connectedHost: String? = null
    private var connectedPort: Int = 0

    // Default connect timeout
    private var connectTimeoutMillis: Long = 5000L

    @Throws(Exception::class)
    override suspend fun connectTo(host: String, port: Int, enableTLS: Boolean, tlsSettings: Map<String, Any>?) {
        if (socket?.isClosed == false) {
            logger.warn("connectTo called on an already connected socket. Disconnecting first.")
            forceDisconnect()
        }

        this.connectedHost = host
        this.connectedPort = port

        logger.info("Attempting to connect to {}:{} with timeout {}ms", host, port, connectTimeoutMillis)

        try {
            val socketBuilder = aSocket(selectorManager).tcp()
            
            socket = withTimeout(connectTimeoutMillis) {
                val baseSocket = socketBuilder.connect(host, port)
                
                if (enableTLS) {
                    logger.info("Enabling TLS for connection to {}:{}", host, port)
                    baseSocket.tls(coroutineContext = coroutineContext)
                } else {
                    baseSocket
                }
            }
            
            readChannel = socket!!.openReadChannel()
            writeChannel = socket!!.openWriteChannel(autoFlush = true)
            
            logger.info("Successfully connected to {}:{}", host, port)
            delegate?.get()?.didConnect(this)
            
        } catch (e: Exception) {
            logger.error("Failed to connect to {}:{}: {}", host, port, e.message, e)
            delegate?.get()?.didErrorOccur(e, this)
            cleanup()
            throw e
        }
    }

    @Throws(Exception::class)
    override suspend fun write(data: ByteArray) {
        val currentWriteChannel = writeChannel
        if (currentWriteChannel == null || socket?.isClosed == true) {
            logger.warn("write called on inactive or null socket. Data not sent.")
            throw IOException("Socket not connected or channel is null.")
        }

        try {
            logger.debug("Writing {} bytes to socket", data.size)
            currentWriteChannel.writeFully(data)
            currentWriteChannel.flush()
            logger.trace("Successfully wrote {} bytes to socket", data.size)
            delegate?.get()?.didWrite(data, this)
        } catch (e: Exception) {
            logger.error("Failed to write {} bytes to socket: {}", data.size, e.message, e)
            delegate?.get()?.didErrorOccur(e, this)
            throw e
        }
    }

    /**
     * Simplified read method that reads available data from the socket.
     * Complex reading logic (delimiters, fixed lengths) should be handled at the application layer.
     * This eliminates the need for mutex and reduces complexity.
     */
    override suspend fun readData() {
        val currentReadChannel = readChannel
        if (currentReadChannel == null || socket?.isClosed == true) {
            logger.warn("readData called on inactive or null socket.")
            return
        }

        try {
            while (!currentReadChannel.isClosedForRead && socket?.isClosed == false) {
                val buffer = ByteArray(8192) // 8KB buffer
                val bytesRead = currentReadChannel.readAvailable(buffer)
                
                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    logger.trace("Read {} bytes from socket", bytesRead)
                    delegate?.get()?.didRead(data, this@KtorRawTCPClientSocket)
                } else if (bytesRead == -1) {
                    // End of stream
                    logger.info("Socket reached end of stream")
                    break
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                logger.error("Error reading from socket: {}", e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawTCPClientSocket)
            }
        } finally {
            if (socket?.isClosed != false) {
                delegate?.get()?.didDisconnect(this@KtorRawTCPClientSocket)
            }
        }
    }

    // Removed complex readDataTo methods - these should be implemented at the application layer
    // This simplifies the socket implementation and eliminates concurrency issues

    override fun disconnect(becauseOf: Throwable?) {
        logger.info("disconnect() called. Closing socket gracefully. Cause: {}", becauseOf?.message)
        cleanup()
        delegate?.get()?.didDisconnect(this)
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        logger.info("forceDisconnect() called. Closing socket immediately. Cause: {}", becauseOf?.message)
        cleanup()
        delegate?.get()?.didDisconnect(this)
    }

    private fun cleanup() {
        try {
            writeChannel?.close()
            readChannel?.cancel()
            socket?.close()
        } catch (e: Exception) {
            logger.debug("Error during cleanup: {}", e.message)
        } finally {
            writeChannel = null
            readChannel = null
            socket = null
        }
    }

    override val isConnected: Boolean
        get() = socket?.isClosed == false

    override val sourceIPAddress: IPAddress?
        get() = try {
            (socket?.localAddress as? InetSocketAddress)?.address?.hostAddress?.let { IPAddress.parse(it) }
        } catch (e: Exception) {
            null
        }

    override val sourcePort: Port?
        get() = try {
            (socket?.localAddress as? InetSocketAddress)?.port?.toUShort()?.let { Port(it.toInt()) }
        } catch (e: Exception) {
            null
        }

    override val destinationIPAddress: IPAddress?
        get() = connectedHost?.let { 
            try {
                IPAddress.parse(it)
            } catch (e: Exception) {
                null
            }
        }

    override val destinationPort: Port?
        get() = if (connectedPort != 0) {
            Port(connectedPort.toUShort().toInt())
        } else {
            null
        }
}