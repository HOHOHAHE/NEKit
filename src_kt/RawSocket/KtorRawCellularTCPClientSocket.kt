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

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import com.example.nekit.Utils.StreamScanner
import com.example.nekit.Opt

/**
 * Ktor-based implementation of RawTCPSocketProtocol for client-side TCP connections,
 * forced to use the Cellular network.
 * 
 * TODO: Ktor common API does not natively support binding to a specific network interface (like Cellular).
 * You will need to implement platform-specific logic here:
 * - On Android: Extract the underlying java.io.FileDescriptor or java.net.Socket and use ConnectivityManager.Network.bindSocket()
 * - On iOS (if KMP native): You might need to use NWConnection directly with requiredInterfaceType = .cellular
 */
class KtorRawCellularTCPClientSocket : RawTCPSocketProtocol {

    private val logger = LoggerFactory.getLogger(KtorRawCellularTCPClientSocket::class.java)
    private var socket: Socket? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null
    private val selectorManager = NetworkDispatchers.selectorManager
    private val writeMutex = Mutex() // 防止並發寫入
    private val readMutex = Mutex() // 防止並發讀取
    
    override var delegate: WeakReference<RawTCPSocketDelegate?>? = null

    // Store connection parameters for IP/Port properties
    private var connectedHost: String? = null
    private var connectedPort: Int = 0

    // Default connect timeout
    private var connectTimeoutMillis: Long = 5000L

    // 統一讀取架構的狀態變數（類似 Swift 版本）
    private var scanner: StreamScanner? = null
    private var scanning: Boolean = false
    private var readDataPrefix: ByteArray? = null

    @Throws(Exception::class)
    override suspend fun connectTo(host: String, port: Int, enableTLS: Boolean, tlsSettings: Map<String, Any>?) {
        if (socket?.isClosed == false) {
            logger.warn("connectTo called on an already connected socket. Disconnecting first.")
            forceDisconnect()
        }

        this.connectedHost = host
        this.connectedPort = port

        logger.info("Attempting to connect to {}:{} via Cellular with timeout {}ms", host, port, connectTimeoutMillis)

        try {
            // TODO: Apply cellular binding logic here before connecting
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
            
            logger.info("Successfully connected to {}:{} via Cellular", host, port)
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
                logger.debug("Writing {} bytes to Cellular socket", data.size)
                currentWriteChannel.writeFully(data)
                currentWriteChannel.flush()
                logger.trace("Successfully wrote {} bytes to Cellular socket", data.size)
                delegate?.get()?.didWrite(data, this@KtorRawCellularTCPClientSocket)
            } catch (e: Exception) {
                logger.error("Failed to write {} bytes to Cellular socket: {}", data.size, e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawCellularTCPClientSocket)
                throw e
            }
        }
    }

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
                val buffer = ByteArray(Opt.MAX_NWTCPSOCKET_READ_DATA_SIZE)
                val bytesRead = currentReadChannel.readAvailable(buffer)
                
                when (bytesRead) {
                    -1 -> {
                        logger.debug("End of stream reached")
                        delegate?.get()?.didDisconnect(this@KtorRawCellularTCPClientSocket)
                    }
                    0 -> {
                        logger.trace("No data available, exiting read")
                    }
                    else -> {
                        val actualData = buffer.copyOf(bytesRead)
                        logger.trace("Read {} bytes from Cellular socket", bytesRead)
                        readCallback(actualData)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error("Error reading from Cellular socket: {}", e.message, e)
                    delegate?.get()?.didErrorOccur(e, this@KtorRawCellularTCPClientSocket)
                }
            } finally {
                isReading.set(false)
            }
        }
    }

    private suspend fun readCallback(data: ByteArray) {
        var processedData = data
        
        readDataPrefix?.let { prefix ->
            processedData = prefix + data
            readDataPrefix = null
        }
        
        if (scanning && scanner != null) {
            val result = scanner!!.addAndScan(processedData)
            
            if (result != null) {
                val (foundData, remainderData) = result
                
                if (foundData != null) {
                    logger.trace("Found pattern after scanning {} bytes", scanner!!.currentLength)
                    delegate?.get()?.didRead(foundData, this@KtorRawCellularTCPClientSocket)
                    
                    if (remainderData.isNotEmpty()) {
                        readDataPrefix = remainderData
                    }
                } else {
                    logger.warn("Maximum scan length exceeded")
                    delegate?.get()?.didRead(remainderData, this@KtorRawCellularTCPClientSocket)
                }
                
                scanning = false
                scanner = null
            } else {
                readData()
            }
        } else {
            delegate?.get()?.didRead(processedData, this@KtorRawCellularTCPClientSocket)
        }
    }

    private fun consumeReadData(): ByteArray? {
        val data = readDataPrefix
        readDataPrefix = null
        return data
    }

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
                    
                    val chunkSize = minOf(length, Opt.MAX_NWTCPSOCKET_READ_DATA_SIZE)
                    val tempBuffer = ByteArray(chunkSize)
                    
                    while (totalRead < length && !currentReadChannel.isClosedForRead && socket?.isClosed == false) {
                        val remainingBytes = length - totalRead
                        val readSize = minOf(remainingBytes, chunkSize)
                        
                        val bytesRead = currentReadChannel.readAvailable(tempBuffer, 0, readSize)
                        if (bytesRead > 0) {
                            System.arraycopy(tempBuffer, 0, buffer, totalRead, bytesRead)
                            totalRead += bytesRead
                        } else if (bytesRead == -1) {
                            logger.warn("Socket reached end of stream before reading {} bytes (read {})", length, totalRead)
                            break
                        }
                    }
                    
                    if (totalRead > 0) {
                        val data = if (totalRead == length) buffer else buffer.copyOf(totalRead)
                        logger.trace("Read {} bytes from Cellular socket (requested {})", totalRead, length)
                        delegate?.get()?.didRead(data, this@KtorRawCellularTCPClientSocket)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        logger.error("Error reading {} bytes from Cellular socket: {}", length, e.message, e)
                        delegate?.get()?.didErrorOccur(e, this@KtorRawCellularTCPClientSocket)
                    }
                }
            }
        }
    }

    override fun readDataTo(data: ByteArray) {
        readDataTo(data, Opt.MAX_NWTCPSCAN_LENGTH)
    }

    override fun readDataTo(data: ByteArray, maxLength: Int) {
        val currentReadChannel = readChannel
        if (currentReadChannel == null || socket?.isClosed == true) {
            logger.warn("readDataTo called on inactive or null socket.")
            return
        }

        scanner = StreamScanner(data, maxLength)
        scanning = true
        
        logger.trace("Starting pattern scan for {} bytes pattern, max length: {}", data.size, maxLength)
        
        readData()
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
            
            scanner = null
            scanning = false
            readDataPrefix = null
            isReading.set(false)
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
