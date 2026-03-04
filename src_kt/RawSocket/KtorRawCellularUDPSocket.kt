package com.example.nekit.RawSocket

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException
import java.io.IOException

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port

/**
 * Ktor-based implementation of RawUDPSocketProtocol for UDP connections,
 * forced to use the Cellular network.
 * 
 * TODO: Ktor common API does not natively support binding to a specific network interface (like Cellular).
 * You will need to implement platform-specific logic here:
 * - On Android: Extract the underlying java.io.FileDescriptor or java.net.DatagramSocket and use ConnectivityManager.Network.bindSocket()
 * - On iOS (if KMP native): You might need to use NWConnection directly with requiredInterfaceType = .cellular
 */
class KtorRawCellularUDPSocket(private val host: String, private val port: Int) : RawUDPSocketProtocol {

    private val logger = LoggerFactory.getLogger(KtorRawCellularUDPSocket::class.java)
    private var socket: Any? = null // Can be BoundDatagramSocket or ConnectedDatagramSocket
    private val selectorManager = NetworkDispatchers.selectorManager
    private val writeMutex = Mutex() // 防止並發寫入
    private val readMutex = Mutex() // 防止並發讀取
    private var readJob: Job? = null
    
    override var delegate: WeakReference<RawUDPSocketDelegate?>? = null
    override var onDatagramReceived: ((data: ByteArray, sourceAddress: IPAddress, sourcePort: Port) -> Unit)? = null

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
                delegate?.get()?.didErrorOccur(e, this@KtorRawCellularUDPSocket)
            }
        }
    }

    private suspend fun connectAsync() {
        if (_isConnected) {
            logger.warn("UDP socket is already connected")
            return
        }

        logger.info("Connecting Cellular UDP socket to {}:{}", host, port)

        try {
            // Create UDP socket and connect to remote address
            val remoteAddress = io.ktor.network.sockets.InetSocketAddress(host, port)
            
            // TODO: Apply cellular binding logic here before connecting
            socket = aSocket(selectorManager).udp().connect(remoteAddress)
            
            // Update connection state
            _isConnected = true
            _destinationIPAddress = IPAddress.parse(host)
            _destinationPort = Port(port)
            
            // Get actual bound address info
            val currentSocket = socket
            if (currentSocket is BoundDatagramSocket) {
                val boundSocketAddress = currentSocket.localAddress as? io.ktor.network.sockets.InetSocketAddress
                boundSocketAddress?.let {
                    _localAddress = IPAddress.parse(it.hostname)
                    _sourceIPAddress = _localAddress
                    _sourcePort = Port(it.port)
                }
            }

            logger.info("Successfully connected Cellular UDP socket to {}:{}", host, port)
            
            // Start reading data
            startReading()
            
        } catch (e: Exception) {
            logger.error("Failed to connect Cellular UDP socket to {}:{}: {}", host, port, e.message, e)
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

        logger.info("Disconnecting Cellular UDP socket")
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
                logger.debug("Writing {} bytes to Cellular UDP socket", data.size)
                when (currentSocket) {
                    is ConnectedDatagramSocket -> {
                        val packet = Datagram(buildPacket { writeFully(data) }, currentSocket.remoteAddress)
                        currentSocket.send(packet)
                    }
                    else -> {
                        logger.error("Cannot write to non-connected UDP socket")
                        delegate?.get()?.didErrorOccur(IllegalStateException("Socket not connected"), this@KtorRawCellularUDPSocket)
                        return@withLock
                    }
                }
                logger.trace("Successfully wrote {} bytes to Cellular UDP socket", data.size)
            } catch (e: Exception) {
                logger.error("Failed to write {} bytes to Cellular UDP socket: {}", data.size, e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawCellularUDPSocket)
            }
        }
    }

    override fun bind(host: String?, port: Int) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                bindAsync(host, port)
            } catch (e: Exception) {
                logger.error("Failed to bind Cellular UDP socket: {}", e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawCellularUDPSocket)
            }
        }
    }

    private suspend fun bindAsync(host: String?, port: Int) {
        if (_isConnected) {
            logger.warn("Cannot bind already connected UDP socket")
            return
        }

        logger.info("Binding Cellular UDP socket to {}:{}", host ?: "0.0.0.0", port)

        try {
            // Create bound UDP socket (0.0.0.0 will be used if host is null)
            val bindHost = host ?: "0.0.0.0"
            
            // TODO: Apply cellular binding logic here before binding
            socket = aSocket(selectorManager).udp().bind(io.ktor.network.sockets.InetSocketAddress(bindHost, port))
            
            // Update connection state
            _isConnected = true
            
            // Get local address info
            val currentSocket = socket
            if (currentSocket is BoundDatagramSocket) {
                val localSocketAddress = currentSocket.localAddress as? io.ktor.network.sockets.InetSocketAddress
                localSocketAddress?.let {
                    _localAddress = IPAddress.parse(it.hostname)
                    _sourceIPAddress = _localAddress
                    _sourcePort = Port(it.port)
                }
            }

            logger.info("Successfully bound Cellular UDP socket to {}:{}", host ?: "0.0.0.0", port)
            
            // Start reading data
            startReading()
            
        } catch (e: Exception) {
            logger.error("Failed to bind Cellular UDP socket to {}:{}: {}", host ?: "0.0.0.0", port, e.message, e)
            cleanup()
            delegate?.get()?.didErrorOccur(e, this)
            throw e
        }
    }

    override suspend fun suspendBind(host: String?, port: Int) {
        bindAsync(host, port)
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
                logger.debug("Sending {} bytes to {}:{} via Cellular", data.size, destinationHost, destinationPort)
                val remoteSocketAddress = io.ktor.network.sockets.InetSocketAddress(destinationHost, destinationPort)
                val packet = Datagram(
                    packet = buildPacket { writeFully(data) },
                    address = remoteSocketAddress
                )
                
                (currentSocket as? BoundDatagramSocket)?.send(packet)
                logger.trace("Successfully sent {} bytes to {}:{} via Cellular", data.size, destinationHost, destinationPort)
            } catch (e: Exception) {
                logger.error("Failed to send {} bytes to {}:{}: {}", data.size, destinationHost, destinationPort, e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawCellularUDPSocket)
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
                    logger.error("Error in Cellular UDP read loop: {}", e.message, e)
                    delegate?.get()?.didErrorOccur(e, this@KtorRawCellularUDPSocket)
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
                
                logger.trace("Received {} bytes from Cellular UDP socket", data.size)
                
                // If the new callback is set, use it to pass source address info (e.g. for SOCKS5 UDP Relay)
                val datagramCallback = onDatagramReceived
                if (datagramCallback != null) {
                    val remoteAddress = datagram.address as? io.ktor.network.sockets.InetSocketAddress
                    if (remoteAddress != null) {
                        val sourceAddress = IPAddress.parse(remoteAddress.hostname)
                        val sourcePort = Port(remoteAddress.port)
                        if (sourceAddress != null) {
                            datagramCallback.invoke(data, sourceAddress, sourcePort)
                        } else {
                            logger.warn("Received datagram but could not parse source IP: {}", remoteAddress.hostname)
                        }
                    } else {
                        logger.warn("Received datagram but address is not InetSocketAddress: {}", datagram.address)
                    }
                }
                
                // Always call the standard delegate
                delegate?.get()?.didReceive(data, this@KtorRawCellularUDPSocket)
                
            } catch (e: CancellationException) {
                // Job was cancelled, don't handle as error
                throw e
            } catch (e: Exception) {
                logger.error("Failed to read from Cellular UDP socket: {}", e.message, e)
                delegate?.get()?.didErrorOccur(e, this@KtorRawCellularUDPSocket)
            }
        }
    }
}
