package com.example.nekit.RawSocket

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
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
    private val writeMutex = Mutex() // 防止並發寫入
    private val readMutex = Mutex() // 防止並發讀取
    
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
    override fun write(data: ByteArray) {
        val currentWriteChannel = writeChannel
        if (currentWriteChannel == null || socket?.isClosed == true) {
            logger.warn("write called on inactive or null socket. Data not sent.")
            throw IOException("Socket not connected or channel is null.")
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                logger.debug("Writing {} bytes to socket", data.size)
                currentWriteChannel.writeFully(data)
                currentWriteChannel.flush()
                logger.trace("Successfully wrote {} bytes to socket", data.size)
                delegate?.get()?.didWrite(data, this@KtorRawTCPClientSocket)
            } catch (e: Exception) {
                logger.error("Failed to write {} bytes to socket: {}", data.size, e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawTCPClientSocket)
                throw e
            }
        }
    }

    /**
     * Simplified read method that reads available data from the socket.
     * Complex reading logic (delimiters, fixed lengths) should be handled at the application layer.
     * This eliminates the need for mutex and reduces complexity.
     */
    private var isReading = AtomicBoolean(false)
    
    override fun readData() {
        val currentReadChannel = readChannel
        if (currentReadChannel == null || socket?.isClosed == true) {
            logger.warn("readData called on inactive or null socket.")
            return
        }
        
        // 防止併發讀取
        if (!isReading.compareAndSet(false, true)) {
            logger.trace("readData already in progress, skipping")
            return
        }
    
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(16384) // 16KB buffer
                val bytesRead = currentReadChannel.readAvailable(buffer)
                
                when (bytesRead) {
                    -1 -> {
                        // End of stream
                        logger.debug("End of stream reached")
                        delegate?.get()?.didDisconnect(this@KtorRawTCPClientSocket)
                    }
                    0 -> {
                        // No data available, exit gracefully
                        logger.trace("No data available, exiting read")
                    }
                    else -> {
                        // Data received
                        val actualData = buffer.copyOf(bytesRead)
                        logger.trace("Read {} bytes from socket", bytesRead)
                        delegate?.get()?.didRead(actualData, this@KtorRawTCPClientSocket)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error("Error reading from socket: {}", e.message, e)
                    delegate?.get()?.didErrorOccur(e, this@KtorRawTCPClientSocket)
                } else {
                    // CancellationException - normal cancellation, no action needed
                }
            } finally {
                isReading.set(false)
            }
        }
    }

    /**
     * Read specific length of data from the socket.
     */
    override fun readDataTo(length: Int) {
        GlobalScope.launch {
            val currentReadChannel = readChannel
            if (currentReadChannel == null || socket?.isClosed == true) {
                logger.warn("readDataTo called on inactive or null socket.")
                return@launch
            }

            readMutex.withLock {
                try {
                    val buffer = ByteArray(length)
                    var totalRead = 0
                    
                    while (totalRead < length && !currentReadChannel.isClosedForRead && socket?.isClosed == false) {
                        val bytesRead = currentReadChannel.readAvailable(buffer, totalRead, length - totalRead)
                        if (bytesRead > 0) {
                            totalRead += bytesRead
                        } else if (bytesRead == -1) {
                            // End of stream before reading required length
                            logger.warn("Socket reached end of stream before reading {} bytes (read {})", length, totalRead)
                            break
                        }
                    }
                    
                    if (totalRead > 0) {
                        val data = if (totalRead == length) buffer else buffer.copyOf(totalRead)
                        logger.trace("Read {} bytes from socket (requested {})", totalRead, length)
                        delegate?.get()?.didRead(data, this@KtorRawTCPClientSocket)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        logger.error("Error reading {} bytes from socket: {}", length, e.message, e)
                        delegate?.get()?.didErrorOccur(e, this@KtorRawTCPClientSocket)
                    }
                }
            }
        }
    }

    /**
     * Read data until a specific pattern (including the pattern).
     */
    override fun readDataTo(data: ByteArray) {
        readDataTo(data, 8192) // Default max length
    }

    /**
     * Read data until a specific pattern (including the pattern).
     */
    override fun readDataTo(data: ByteArray, maxLength: Int) {
        GlobalScope.launch {
            val currentReadChannel = readChannel
            if (currentReadChannel == null || socket?.isClosed == true) {
                logger.warn("readDataTo called on inactive or null socket.")
                return@launch
            }

            readMutex.withLock {
                try {
                    val buffer = mutableListOf<Byte>()
                    val pattern = data
                    var totalRead = 0
                    
                    while (totalRead < maxLength && !currentReadChannel.isClosedForRead && socket?.isClosed == false) {
                        val tempBuffer = ByteArray(1)
                        val bytesRead = currentReadChannel.readAvailable(tempBuffer)
                        
                        if (bytesRead > 0) {
                            buffer.add(tempBuffer[0])
                            totalRead++
                            
                            // Check if we have found the pattern
                            if (buffer.size >= pattern.size) {
                                val lastBytes = buffer.takeLast(pattern.size).toByteArray()
                                if (lastBytes.contentEquals(pattern)) {
                                    // Found pattern, return all data including pattern
                                    val resultData = buffer.toByteArray()
                                    logger.trace("Found pattern after reading {} bytes", totalRead)
                                    delegate?.get()?.didRead(resultData, this@KtorRawTCPClientSocket)
                                    return@withLock
                                }
                            }
                        } else if (bytesRead == -1) {
                            // End of stream
                            logger.warn("Socket reached end of stream before finding pattern")
                            break
                        }
                    }
                    
                    // Return whatever we read even if pattern not found
                    if (buffer.isNotEmpty()) {
                        val resultData = buffer.toByteArray()
                        logger.trace("Read {} bytes without finding pattern", totalRead)
                        delegate?.get()?.didRead(resultData, this@KtorRawTCPClientSocket)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        logger.error("Error reading until pattern from socket: {}", e.message, e)
                        delegate?.get()?.didErrorOccur(e, this@KtorRawTCPClientSocket)
                    }
                }
            }
        }
    }

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