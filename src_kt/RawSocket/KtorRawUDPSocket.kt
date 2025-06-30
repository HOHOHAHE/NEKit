package com.example.nekit.RawSocket

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.io.IOException

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port

/**
 * Ktor-based implementation of RawUDPSocketProtocol for UDP connections.
 * This implementation uses Ktor's coroutine-based UDP sockets for better integration with the existing architecture.
 */
class KtorRawUDPSocket(private val host: String, private val port: Int) : RawUDPSocketProtocol {

    private val logger = LoggerFactory.getLogger(KtorRawUDPSocket::class.java)
    private var socket: Any? = null // Can be BoundDatagramSocket or ConnectedDatagramSocket
    private val selectorManager = ActorSelectorManager(Dispatchers.IO)
    private val writeMutex = Mutex() // 防止並發寫入
    private val readMutex = Mutex() // 防止並發讀取
    private var readJob: Job? = null
    
    override var delegate: WeakReference<RawUDPSocketDelegate?>? = null

    // Connection state
    private var _isConnected: Boolean = false
    override val isConnected: Boolean get() = _isConnected

    // Address properties
    private var _sourceIPAddress: IPAddress? = null
    private var _sourcePort: Port? = null
    private var _destinationIPAddress: IPAddress? = null
    private var _destinationPort: Port? = null
    private var _localAddress: IPAddress? = null

    override val sourceIPAddress: IPAddress? get() = _sourceIPAddress
    override val sourcePort: Port? get() = _sourcePort
    override val destinationIPAddress: IPAddress? get() = _destinationIPAddress
    override val destinationPort: Port? get() = _destinationPort
    override val localAddress: IPAddress? get() = _localAddress

    override fun connect() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                connectAsync()
            } catch (e: Exception) {
                logger.error("Failed to connect UDP socket: {}", e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawUDPSocket)
            }
        }
    }

    private suspend fun connectAsync() {
        if (_isConnected) {
            logger.warn("UDP socket is already connected")
            return
        }

        logger.info("Connecting UDP socket to {}:{}", host, port)

        try {
            // Create UDP socket and connect to remote address
            val remoteAddress = InetSocketAddress(host, port) as SocketAddress
            socket = aSocket(selectorManager).udp().connect(remoteAddress)
            
            // Update connection state
            _isConnected = true
            _destinationIPAddress = IPAddress.parse(host)
            _destinationPort = Port(port)
            
            // Get actual bound address info
            val currentSocket = socket
            if (currentSocket is BoundDatagramSocket) {
                val boundSocketAddress = currentSocket.localAddress as? InetSocketAddress
                boundSocketAddress?.let {
                    _localAddress = IPAddress.parse(it.address.hostAddress)
                    _sourceIPAddress = _localAddress
                    _sourcePort = Port(it.port)
                }
            }

            logger.info("Successfully connected UDP socket to {}:{}", host, port)
            
            // Start reading data
            startReading()
            
        } catch (e: Exception) {
            logger.error("Failed to connect UDP socket to {}:{}: {}", host, port, e.message, e)
            cleanup()
            delegate?.get()?.didErrorOccur(e, this)
            throw e
        }
    }

    override fun disconnect() {
        GlobalScope.launch(Dispatchers.IO) {
            disconnectAsync()
        }
    }

    private suspend fun disconnectAsync() {
        if (!_isConnected) {
            logger.debug("UDP socket is already disconnected")
            return
        }

        logger.info("Disconnecting UDP socket")
        cleanup()
        delegate?.get()?.didCancel(this)
    }

    private suspend fun cleanup() {
        _isConnected = false
        readJob?.cancel()
        readJob = null
        
        try {
            val currentSocket = socket
            when (currentSocket) {
                is BoundDatagramSocket -> currentSocket.close()
                is ConnectedDatagramSocket -> currentSocket.close()
            }
        } catch (e: Exception) {
            logger.warn("Error closing UDP socket: {}", e.message)
        }
        socket = null
        
        // Reset address properties
        _sourceIPAddress = null
        _sourcePort = null
        _destinationIPAddress = null
        _destinationPort = null
        _localAddress = null
    }

    override fun write(data: ByteArray) {
        GlobalScope.launch(Dispatchers.IO) {
            writeAsync(data)
        }
    }

    private suspend fun writeAsync(data: ByteArray) {
        val currentSocket = socket
        if (currentSocket == null || !_isConnected) {
            logger.warn("write called on inactive UDP socket. Data not sent.")
            delegate?.get()?.didErrorOccur(IOException("UDP socket not connected"), this)
            return
        }

        writeMutex.withLock {
            try {
                logger.debug("Writing {} bytes to UDP socket", data.size)
                when (currentSocket) {
                    is ConnectedDatagramSocket -> {
                        val packet = Datagram(buildPacket { writeFully(data) }, currentSocket.remoteAddress)
                        currentSocket.send(packet)
                    }
                    else -> {
                        logger.error("Cannot write to non-connected UDP socket")
                        delegate?.get()?.didErrorOccur(IllegalStateException("Socket not connected"), this@KtorRawUDPSocket)
                        return@withLock
                    }
                }
                logger.trace("Successfully wrote {} bytes to UDP socket", data.size)
            } catch (e: Exception) {
                logger.error("Failed to write {} bytes to UDP socket: {}", data.size, e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawUDPSocket)
            }
        }
    }

    override fun bind(host: String?, port: Int) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                bindAsync(host, port)
            } catch (e: Exception) {
                logger.error("Failed to bind UDP socket: {}", e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawUDPSocket)
            }
        }
    }

    private suspend fun bindAsync(host: String?, port: Int) {
        if (_isConnected) {
            logger.warn("Cannot bind already connected UDP socket")
            return
        }

        logger.info("Binding UDP socket to {}:{}", host ?: "0.0.0.0", port)

        try {
            // Create bound UDP socket
            val localAddress = InetSocketAddress(host, port) as SocketAddress
            socket = aSocket(selectorManager).udp().bind(localAddress)
            
            // Update connection state
            _isConnected = true
            
            // Get local address info
            val currentSocket = socket
            if (currentSocket is BoundDatagramSocket) {
                val localSocketAddress = currentSocket.localAddress as? InetSocketAddress
                localSocketAddress?.let {
                    _localAddress = IPAddress.parse(it.address.hostAddress)
                    _sourceIPAddress = _localAddress
                    _sourcePort = Port(it.port)
                }
            }

            logger.info("Successfully bound UDP socket to {}:{}", host ?: "0.0.0.0", port)
            
            // Start reading data
            startReading()
            
        } catch (e: Exception) {
            logger.error("Failed to bind UDP socket to {}:{}: {}", host ?: "0.0.0.0", port, e.message, e)
            cleanup()
            delegate?.get()?.didErrorOccur(e, this)
            throw e
        }
    }

    override fun send(data: ByteArray, destinationHost: String, destinationPort: Int) {
        GlobalScope.launch(Dispatchers.IO) {
            sendAsync(data, destinationHost, destinationPort)
        }
    }

    private suspend fun sendAsync(data: ByteArray, destinationHost: String, destinationPort: Int) {
        val currentSocket = socket
        if (currentSocket == null || !_isConnected) {
            logger.warn("send called on inactive UDP socket. Data not sent.")
            delegate?.get()?.didErrorOccur(IOException("UDP socket not connected"), this)
            return
        }

        writeMutex.withLock {
            try {
                logger.debug("Sending {} bytes to {}:{}", data.size, destinationHost, destinationPort)
                val remoteSocketAddress = InetSocketAddress(destinationHost, destinationPort) as SocketAddress
                val packet = Datagram(
                    packet = buildPacket { writeFully(data) },
                    address = remoteSocketAddress
                )
                
                (currentSocket as? BoundDatagramSocket)?.send(packet)
                logger.trace("Successfully sent {} bytes to {}:{}", data.size, destinationHost, destinationPort)
            } catch (e: Exception) {
                logger.error("Failed to send {} bytes to {}:{}: {}", data.size, destinationHost, destinationPort, e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawUDPSocket)
            }
        }
    }

    private fun startReading() {
        readJob = GlobalScope.launch(Dispatchers.IO) {
            while (_isConnected && socket != null) {
                try {
                    readData()
                } catch (e: CancellationException) {
                    // Job was cancelled, exit gracefully
                    break
                } catch (e: Exception) {
                    logger.error("Error in UDP read loop: {}", e.message, e)
                    delegate?.get()?.didErrorOccur(e, this@KtorRawUDPSocket)
                    break
                }
            }
        }
    }

    private suspend fun readData() {
        val currentSocket = socket
        if (currentSocket == null || !_isConnected) {
            logger.warn("readData called on inactive UDP socket.")
            return
        }

        readMutex.withLock {
            try {
                val datagram = when (currentSocket) {
                    is BoundDatagramSocket -> currentSocket.receive()
                    is ConnectedDatagramSocket -> currentSocket.receive()
                    else -> throw IllegalStateException("Unknown socket type")
                }
                val data = datagram.packet.readBytes()
                
                logger.trace("Received {} bytes from UDP socket", data.size)
                delegate?.get()?.didReceive(data, this@KtorRawUDPSocket)
                
            } catch (e: CancellationException) {
                // Job was cancelled, don't handle as error
                throw e
            } catch (e: Exception) {
                logger.error("Failed to read from UDP socket: {}", e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawUDPSocket)
            }
        }
    }
}