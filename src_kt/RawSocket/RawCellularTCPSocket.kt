package com.example.nekit.RawSocket

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import com.example.nekit.Utils.StreamScanner
import com.example.nekit.Opt
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory
import android.annotation.SuppressLint

import com.example.nekit.Utils.CellularNetworkRequester

/**
 * Implementation of RawTCPSocketProtocol targeting the cellular network.
 * Binds the underlying java.net.Socket to Android's cellular Network before connecting.
 * 
 * Uses CellularNetworkRequester to dynamically request the Cellular network via Reflection,
 * making this socket completely self-contained without needing App-level Context injection.
 */
class RawCellularTCPSocket : RawTCPSocketProtocol {

    private val logger = LoggerFactory.getLogger(RawCellularTCPSocket::class.java)
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val scope = CoroutineScope(NetworkDispatchers.CellularBlockingIO + SupervisorJob())
    private var readJob: Job? = null

    override var delegate: WeakReference<RawTCPSocketDelegate?>? = null

    private var connectedHost: String? = null
    private var connectedPort: Int = 0
    private var connectTimeoutMillis: Int = 5000

    private var scanner: StreamScanner? = null
    private var scanning: Boolean = false
    private var readDataPrefix: ByteArray? = null
    private var isReading = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    override val sourceIPAddress: IPAddress?
        get() = try {
            socket?.localAddress?.hostAddress?.let { IPAddress.parse(it) }
        } catch (e: Exception) {
            null
        }

    override val sourcePort: Port?
        get() = try {
            socket?.localPort?.let { Port(it) }
        } catch (e: Exception) {
            null
        }

    override val destinationIPAddress: IPAddress?
        get() = connectedHost?.let {
            try { IPAddress.parse(it) } catch (e: Exception) { null }
        } ?: try {
            socket?.inetAddress?.hostAddress?.let { IPAddress.parse(it) }
        } catch (e: Exception) {
            null
        }

    override val destinationPort: Port?
        get() = if (connectedPort != 0) {
            Port(connectedPort)
        } else {
            try { socket?.port?.let { Port(it) } } catch (e: Exception) { null }
        }

    @SuppressLint("NewApi")
    @Throws(Exception::class)
    override suspend fun connectTo(host: String, port: Int, enableTLS: Boolean, tlsSettings: Map<String, Any>?) {
        if (socket?.isClosed == false && socket?.isConnected == true) {
            logger.warn("connectTo called on an already connected socket. Disconnecting first.")
            forceDisconnect()
        }

        this.connectedHost = host
        this.connectedPort = port

        logger.info("Attempting to connect to {}:{} via Cellular with timeout {}ms", host, port, connectTimeoutMillis)

        withContext(NetworkDispatchers.CellularBlockingIO) {
            try {
                // 1. Create native socket
                val baseSocket = Socket()
                
                // 2. Request and wait for cellular network via Reflection utility (max 5 seconds timeout)
                logger.debug("Requesting cellular network from system...")
                val cellularNetwork = CellularNetworkRequester.requestAndGetCellularNetwork(5000L)
                if (cellularNetwork != null) {
                    logger.debug("Binding socket to active cellular network: {}", cellularNetwork)
                    cellularNetwork.bindSocket(baseSocket)
                } else {
                    logger.warn("Cellular network request timed out or unavailable. Socket will use default network route.")
                }

                // 3. Connect the socket
                val endpoint = InetSocketAddress(host, port)
                baseSocket.connect(endpoint, connectTimeoutMillis)

                // 4. Upgrade to TLS if needed
                val finalSocket = if (enableTLS) {
                    logger.info("Enabling TLS for connection to {}:{}", host, port)
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    
                    // The autoClose parameter should be true so closing the SSLSocket closes the base socket
                    val sslSocket = factory.createSocket(baseSocket, host, port, true)
                    
                    // Might need to pass SNI or other tlsSettings here depending on requirements
                    // For now simple SSL usage as placeholder
                    sslSocket
                } else {
                    baseSocket
                }

                socket = finalSocket
                inputStream = finalSocket.getInputStream()
                outputStream = finalSocket.getOutputStream()

                logger.info("Successfully connected to {}:{} via native Cellular Socket", host, port)
                
                // Switch back to calling context for delegate
                withContext(Dispatchers.Main.immediate) {
                    delegate?.get()?.didConnect(this@RawCellularTCPSocket)
                }
            } catch (e: Exception) {
                logger.error("Failed to connect to {}:{}: {}", host, port, e.message, e)
                withContext(Dispatchers.Main.immediate) {
                    delegate?.get()?.didErrorOccur(e, this@RawCellularTCPSocket)
                }
                cleanup()
                throw e
            }
        }
    }

    @Throws(Exception::class)
    override fun write(data: ByteArray) {
        val currentStream = outputStream
        if (currentStream == null || socket?.isClosed == true || socket?.isConnected == false) {
            logger.warn("write called on inactive or null socket. Data not sent.")
            throw java.io.IOException("Socket not connected or stream is null.")
        }

        scope.launch {
            try {
                logger.debug("Writing {} bytes to Cellular socket", data.size)
                currentStream.write(data)
                currentStream.flush()
                logger.trace("Successfully wrote {} bytes to Cellular socket", data.size)
                delegate?.get()?.didWrite(data, this@RawCellularTCPSocket)
            } catch (e: Exception) {
                logger.error("Failed to write {} bytes to Cellular socket: {}", data.size, e.message, e)
                delegate?.get()?.didErrorOccur(e, this@RawCellularTCPSocket)
                throw e
            }
        }
    }

    override fun readData() {
        val currentStream = inputStream
        if (currentStream == null || socket?.isClosed == true || socket?.isConnected == false) {
            logger.warn("readData called on inactive or null socket.")
            return
        }

        if (!isReading.compareAndSet(false, true)) {
            logger.trace("readData already in progress, skipping")
            return
        }

        readJob = scope.launch {
            try {
                val buffer = ByteArray(Opt.MAX_NWTCPSOCKET_READ_DATA_SIZE)
                // blocking read on IO dispatcher
                val bytesRead = currentStream.read(buffer)

                when (bytesRead) {
                    -1 -> {
                        logger.debug("End of stream reached")
                        delegate?.get()?.didDisconnect(this@RawCellularTCPSocket)
                        cleanup()
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
                    delegate?.get()?.didErrorOccur(e, this@RawCellularTCPSocket)
                    cleanup()
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
                    delegate?.get()?.didRead(foundData, this@RawCellularTCPSocket)
                    
                    if (remainderData.isNotEmpty()) {
                        readDataPrefix = remainderData
                    }
                } else {
                    logger.warn("Maximum scan length exceeded")
                    delegate?.get()?.didRead(remainderData, this@RawCellularTCPSocket)
                }
                
                scanning = false
                scanner = null
            } else {
                readData()
            }
        } else {
            delegate?.get()?.didRead(processedData, this@RawCellularTCPSocket)
        }
    }

    override fun readDataTo(length: Int) {
        val currentStream = inputStream
        if (currentStream == null || socket?.isClosed == true || socket?.isConnected == false) {
            logger.warn("readDataTo called on inactive or null socket.")
            return
        }

        scope.launch {
            try {
                val buffer = ByteArray(length)
                var totalRead = 0
                
                while (totalRead < length && socket?.isClosed == false) {
                    val bytesToRead = length - totalRead
                    val bytesRead = currentStream.read(buffer, totalRead, bytesToRead)
                    
                    if (bytesRead == -1) {
                        logger.warn("Socket reached end of stream before reading {} bytes (read {})", length, totalRead)
                        break
                    }
                    totalRead += bytesRead
                }
                
                if (totalRead > 0) {
                    val data = if (totalRead == length) buffer else buffer.copyOf(totalRead)
                    logger.trace("Read {} bytes from Cellular socket (requested {})", totalRead, length)
                    delegate?.get()?.didRead(data, this@RawCellularTCPSocket)
                } else if (totalRead == 0 && length > 0) {
                    delegate?.get()?.didDisconnect(this@RawCellularTCPSocket)
                    cleanup()
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error("Error reading {} bytes from Cellular socket: {}", length, e.message, e)
                    delegate?.get()?.didErrorOccur(e, this@RawCellularTCPSocket)
                    cleanup()
                }
            }
        }
    }

    override fun readDataTo(data: ByteArray) {
        readDataTo(data, Opt.MAX_NWTCPSCAN_LENGTH)
    }

    override fun readDataTo(data: ByteArray, maxLength: Int) {
        val currentStream = inputStream
        if (currentStream == null || socket?.isClosed == true || socket?.isConnected == false) {
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
            readJob?.cancel()
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            logger.debug("Error during cleanup: {}", e.message)
        } finally {
            inputStream = null
            outputStream = null
            socket = null
            
            scanner = null
            scanning = false
            readDataPrefix = null
            isReading.set(false)
        }
    }
}
