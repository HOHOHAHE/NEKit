package nekit.RawSocket.ktor

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import nekit.Utils.IPAddress
import nekit.Utils.Port
import nekit.Utils.StreamScanner
import nekit.Opt
import nekit.RawSocket.protocol.RawTCPSocketProtocol
import nekit.RawSocket.protocol.RawTCPSocketDelegate
import nekit.RawSocket.core.NetworkDispatchers

/**
 * Ktor-based implementation of RawTCPSocketProtocol for client-side TCP connections.
 * This implementation uses Ktor's coroutine-based sockets for better integration with the existing architecture.
 */
class RawTCPSocket : RawTCPSocketProtocol {

    private val logger = LoggerFactory.getLogger(RawTCPSocket::class.java)
    private var socket: Socket? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null
    // 使用共享的 SelectorManager 而不是為每個連線建立新的
    private val selectorManager = NetworkDispatchers.selectorManager
    
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
                delegate?.get()?.didWrite(data, this@RawTCPSocket)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error("Failed to write {} bytes to socket: {}", data.size, e.message)
                    delegate?.get()?.didErrorOccur(e, this@RawTCPSocket)
                    cleanup()
                }
            }
        }
    }

    /**
     * 統一的讀取方法，所有讀取操作都通過此方法進行
     * 類似 Swift 版本的架構，所有數據都通過 readCallback 處理
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
                val buffer = ByteArray(Opt.MAX_NWTCPSOCKET_READ_DATA_SIZE) // 128KB buffer for better performance
                val bytesRead = currentReadChannel.readAvailable(buffer)
                
                when (bytesRead) {
                    -1 -> {
                        // End of stream
                        logger.debug("End of stream reached")
                        delegate?.get()?.didDisconnect(this@RawTCPSocket)
                    }
                    0 -> {
                        // No data available, exit gracefully
                        logger.trace("No data available, exiting read")
                    }
                    else -> {
                        // Data received - 通過統一的 readCallback 處理
                        val actualData = buffer.copyOf(bytesRead)
                        logger.trace("Read {} bytes from socket", bytesRead)
                        readCallback(actualData)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error("Error reading from socket: {}", e.message, e)
                    delegate?.get()?.didErrorOccur(e, this@RawTCPSocket)
                } else {
                    // CancellationException - normal cancellation, no action needed
                }
            } finally {
                isReading.set(false)
            }
        }
    }

    /**
     * 統一的讀取回調處理方法（類似 Swift 版本的 readCallback）
     * 所有從 socket 讀取的數據都會通過此方法處理
     */
    private suspend fun readCallback(data: ByteArray) {
        var processedData = data
        
        // 如果有待處理的前綴數據，合併處理
        readDataPrefix?.let { prefix ->
            processedData = prefix + data
            readDataPrefix = null
        }
        
        if (scanning && scanner != null) {
            // 掃描模式：使用 scanner 處理數據
            val result = scanner!!.addAndScan(processedData)
            
            if (result != null) {
                val (foundData, remainderData) = result
                
                if (foundData != null) {
                    // 找到模式，返回包含模式的數據
                    logger.trace("Found pattern after scanning {} bytes", scanner!!.currentLength)
                    delegate?.get()?.didRead(foundData, this@RawTCPSocket)
                    
                    // 處理剩餘數據
                    if (remainderData.isNotEmpty()) {
                        readDataPrefix = remainderData
                        logger.trace("Stored {} bytes as readDataPrefix", remainderData.size)
                    }
                } else {
                    // 超過最大長度，返回累積的數據
                    logger.warn("Maximum scan length exceeded")
                    delegate?.get()?.didRead(remainderData, this@RawTCPSocket)
                }
                
                // 完成掃描
                scanning = false
                scanner = null
            } else {
                // 模式未找到，繼續讀取更多數據
                logger.trace("Pattern not found yet, continuing to read")
                readData() // 遞歸調用繼續讀取
            }
        } else {
            // 正常模式：直接返回數據
            delegate?.get()?.didRead(processedData, this@RawTCPSocket)
        }
    }

    /**
     * 消耗並返回存儲的前綴數據（類似 Swift 版本的 consumeReadData）
     */
    private fun consumeReadData(): ByteArray? {
        val data = readDataPrefix
        readDataPrefix = null
        return data
    }

    /**
     * Read specific length of data from the socket.
     * Optimized to use larger read chunks when possible.
     */
    override fun readDataTo(length: Int) {
        GlobalScope.launch {
            val currentReadChannel = readChannel
            if (currentReadChannel == null || socket?.isClosed == true) {
                logger.warn("readDataTo called on inactive or null socket.")
                return@launch
            }

            try {
                val buffer = ByteArray(length)
                var totalRead = 0
                
                // Use larger chunks for better performance when reading large amounts
                val chunkSize = minOf(length, Opt.MAX_NWTCPSOCKET_READ_DATA_SIZE)
                val tempBuffer = ByteArray(chunkSize)
                
                while (totalRead < length && !currentReadChannel.isClosedForRead && socket?.isClosed == false) {
                    val remainingBytes = length - totalRead
                    val readSize = minOf(remainingBytes, chunkSize)
                    
                    val bytesRead = currentReadChannel.readAvailable(tempBuffer, 0, readSize)
                    if (bytesRead > 0) {
                        // Copy data from temp buffer to main buffer
                        System.arraycopy(tempBuffer, 0, buffer, totalRead, bytesRead)
                        totalRead += bytesRead
                    } else if (bytesRead == -1) {
                        // End of stream before reading required length
                        logger.warn("Socket reached end of stream before reading {} bytes (read {})", length, totalRead)
                        break
                    }
                    // If bytesRead == 0, continue trying to read more data
                }
                
                if (totalRead > 0) {
                    val data = if (totalRead == length) buffer else buffer.copyOf(totalRead)
                    logger.trace("Read {} bytes from socket (requested {})", totalRead, length)
                    delegate?.get()?.didRead(data, this@RawTCPSocket)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error("Error reading {} bytes from socket: {}", length, e.message, e)
                    delegate?.get()?.didErrorOccur(e, this@RawTCPSocket)
                }
            }
        }
    }

    /**
     * Read data until a specific pattern (including the pattern).
     */
    override fun readDataTo(data: ByteArray) {
        readDataTo(data, Opt.MAX_NWTCPSCAN_LENGTH) // Use optimized default max length
    }

    /**
     * 讀取數據直到找到指定模式（包含模式）
     * 重構為使用統一架構：設置 scanner 狀態後調用 readData()
     * 類似 Swift 版本的實現方式
     */
    override fun readDataTo(data: ByteArray, maxLength: Int) {
        val currentReadChannel = readChannel
        if (currentReadChannel == null || socket?.isClosed == true) {
            logger.warn("readDataTo called on inactive or null socket.")
            return
        }

        // 設置掃描狀態（類似 Swift 版本）
        scanner = StreamScanner(data, maxLength)
        scanning = true
        
        logger.trace("Starting pattern scan for {} bytes pattern, max length: {}", data.size, maxLength)
        
        // 調用統一的 readData() 方法，讓所有數據通過 readCallback 處理
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
            
            // 重置掃描狀態
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