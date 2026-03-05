package nekit.RawSocket.cellular

import nekit.Utils.IPAddress
import nekit.Utils.Port
import nekit.Opt
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import android.annotation.SuppressLint

import nekit.Utils.CellularNetworkRequester
import nekit.RawSocket.protocol.RawUDPSocketProtocol
import nekit.RawSocket.protocol.RawUDPSocketDelegate
import nekit.RawSocket.core.NetworkDispatchers

/**
 * Native implementation of RawUDPSocketProtocol targeting the cellular network.
 * Wraps java.net.DatagramSocket and binds it to Android's cellular Network before
 * transmitting.
 *
 * Uses CellularNetworkRequester to dynamically request the Cellular network via Reflection,
 * making this socket completely self-contained without needing App-level Context injection.
 */
class RawCellularUDPSocket(private val host: String, private val port: Int) : RawUDPSocketProtocol {

    private val logger = LoggerFactory.getLogger(RawCellularUDPSocket::class.java)
    private var socket: DatagramSocket? = null
    
    private val scope = CoroutineScope(NetworkDispatchers.CellularBlockingIO + SupervisorJob())
    private var readJob: Job? = null
    
    override var delegate: WeakReference<RawUDPSocketDelegate?>? = null
    override var onDatagramReceived: ((data: ByteArray, sourceAddress: IPAddress, sourcePort: Port) -> Unit)? = null

    private var _isConnected: Boolean = false
    override val isConnected: Boolean get() = _isConnected

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
        scope.launch {
            try {
                connectAsync()
            } catch (e: Exception) {
                logger.error("Failed to connect Cellular UDP socket: {}", e.message, e)
                withContext(Dispatchers.Main.immediate) {
                    delegate?.get()?.didErrorOccur(e, this@RawCellularUDPSocket)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private suspend fun connectAsync() {
        if (_isConnected) {
            logger.warn("UDP socket is already connected")
            return
        }

        logger.info("Connecting Cellular UDP socket to {}:{}", host, port)

        withContext(NetworkDispatchers.CellularBlockingIO) {
            try {
                val newSocket = DatagramSocket(null) // Unbound
                newSocket.reuseAddress = true

                // Request and wait for cellular network via Reflection utility
                logger.debug("Requesting cellular network from system...")
                val cellularNetwork = CellularNetworkRequester.requestAndGetCellularNetwork(5000L)
                if (cellularNetwork != null) {
                    logger.debug("Binding UDP socket to active cellular network: {}", cellularNetwork)
                    cellularNetwork.bindSocket(newSocket)
                } else {
                    logger.warn("Cellular network request timed out or unavailable. Socket will use default network route.")
                }

                // Connect the remote address
                val remoteAddress = InetSocketAddress(host, port)
                newSocket.connect(remoteAddress)
                
                socket = newSocket
                _isConnected = true
                _destinationIPAddress = IPAddress.parse(host)
                _destinationPort = Port(port)
                
                // Get local address if available
                val localAddressStr = newSocket.localAddress?.hostAddress
                if (localAddressStr != null) {
                    _localAddress = IPAddress.parse(localAddressStr)
                    _sourceIPAddress = _localAddress
                    _sourcePort = Port(newSocket.localPort)
                }

                logger.info("Successfully connected Cellular UDP socket to {}:{}", host, port)
                
                startReading()
                
            } catch (e: Exception) {
                logger.error("Failed to connect Cellular UDP socket to {}:{}: {}", host, port, e.message, e)
                cleanup()
                withContext(Dispatchers.Main.immediate) {
                    delegate?.get()?.didErrorOccur(e, this@RawCellularUDPSocket)
                }
                throw e
            }
        }
    }

    override fun disconnect() {
        scope.launch {
            disconnectAsync()
            withContext(Dispatchers.Main.immediate) {
                delegate?.get()?.didCancel(this@RawCellularUDPSocket)
            }
        }
    }

    private suspend fun disconnectAsync() {
        if (!_isConnected) {
            logger.debug("UDP socket is already disconnected")
            return
        }

        logger.info("Disconnecting Cellular UDP socket")
        cleanup()
    }

    private fun cleanup() {
        _isConnected = false
        try {
            readJob?.cancel()
            socket?.close()
        } catch (e: Exception) {
            logger.warn("Error closing UDP socket: {}", e.message)
        } finally {
            readJob = null
            socket = null
            
            _sourceIPAddress = null
            _sourcePort = null
            _destinationIPAddress = null
            _destinationPort = null
            _localAddress = null
        }
    }

    override fun write(data: ByteArray) {
        scope.launch {
            writeAsync(data)
        }
    }

    private suspend fun writeAsync(data: ByteArray) {
        val currentSocket = socket
        if (currentSocket == null || !_isConnected) {
            logger.warn("write called on inactive UDP socket. Data not sent.")
            withContext(Dispatchers.Main.immediate) {
                delegate?.get()?.didErrorOccur(IOException("UDP socket not connected"), this@RawCellularUDPSocket)
            }
            return
        }

        try {
            logger.debug("Writing {} bytes to Cellular UDP socket", data.size)
            
            // For connected datagram sockets, we don't need to specify the packet address
            // unless we want to override the connection
            val packet = DatagramPacket(data, data.size)
            currentSocket.send(packet)
            
            logger.trace("Successfully wrote {} bytes to Cellular UDP socket", data.size)
        } catch (e: Exception) {
            logger.error("Failed to write {} bytes to Cellular UDP socket: {}", data.size, e.message, e)
            withContext(Dispatchers.Main.immediate) {
                delegate?.get()?.didErrorOccur(e, this@RawCellularUDPSocket)
            }
        }
    }

    override fun bind(host: String?, port: Int) {
        scope.launch {
            try {
                bindAsync(host, port)
            } catch (e: Exception) {
                logger.error("Failed to bind Cellular UDP socket: {}", e.message, e)
                withContext(Dispatchers.Main.immediate) {
                    delegate?.get()?.didErrorOccur(e, this@RawCellularUDPSocket)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private suspend fun bindAsync(host: String?, port: Int) {
        if (_isConnected) {
            logger.warn("Cannot bind already connected UDP socket")
            return
        }

        logger.info("Binding Cellular UDP socket to {}:{}", host ?: "0.0.0.0", port)

        withContext(NetworkDispatchers.CellularBlockingIO) {
            try {
                val newSocket = DatagramSocket(null) // Unbound
                newSocket.reuseAddress = true
                
                // Request and wait for cellular network via Reflection utility
                logger.debug("Requesting cellular network from system...")
                val cellularNetwork = CellularNetworkRequester.requestAndGetCellularNetwork(5000L)
                if (cellularNetwork != null) {
                    logger.debug("Binding UDP socket to active cellular network: {}", cellularNetwork)
                    cellularNetwork.bindSocket(newSocket)
                } else {
                    logger.warn("Cellular network request timed out or unavailable. Socket will use default network route.")
                }

                val bindHost = host ?: "0.0.0.0"
                val bindAddress = InetSocketAddress(bindHost, port)
                newSocket.bind(bindAddress)
                
                socket = newSocket
                _isConnected = true
                
                // Get local address info
                val localAddressStr = newSocket.localAddress?.hostAddress
                if (localAddressStr != null) {
                    _localAddress = IPAddress.parse(localAddressStr)
                    _sourceIPAddress = _localAddress
                    _sourcePort = Port(newSocket.localPort)
                }

                logger.info("Successfully bound Cellular UDP socket to {}:{}", host ?: "0.0.0.0", port)
                
                startReading()
                
            } catch (e: Exception) {
                logger.error("Failed to bind Cellular UDP socket to {}:{}: {}", host ?: "0.0.0.0", port, e.message, e)
                cleanup()
                withContext(Dispatchers.Main.immediate) {
                    delegate?.get()?.didErrorOccur(e, this@RawCellularUDPSocket)
                }
                throw e
            }
        }
    }

    override suspend fun suspendBind(host: String?, port: Int) {
        bindAsync(host, port)
    }

    override fun send(data: ByteArray, destinationHost: String, destinationPort: Int) {
        scope.launch {
            sendAsync(data, destinationHost, destinationPort)
        }
    }

    private suspend fun sendAsync(data: ByteArray, destinationHost: String, destinationPort: Int) {
        val currentSocket = socket
        if (currentSocket == null || !_isConnected) {
            logger.warn("send called on inactive UDP socket. Data not sent.")
            withContext(Dispatchers.Main.immediate) {
                delegate?.get()?.didErrorOccur(IOException("UDP socket not connected"), this@RawCellularUDPSocket)
            }
            return
        }

        try {
            logger.debug("Sending {} bytes to {}:{} via Cellular", data.size, destinationHost, destinationPort)
            
            val destAddress = InetSocketAddress(destinationHost, destinationPort)
            val packet = DatagramPacket(data, data.size, destAddress)
            currentSocket.send(packet)
            
            logger.trace("Successfully sent {} bytes to {}:{} via Cellular", data.size, destinationHost, destinationPort)
        } catch (e: Exception) {
            logger.error("Failed to send {} bytes to {}:{}: {}", data.size, destinationHost, destinationPort, e.message, e)
            withContext(Dispatchers.Main.immediate) {
                delegate?.get()?.didErrorOccur(e, this@RawCellularUDPSocket)
            }
        }
    }

    private fun startReading() {
        readJob = scope.launch {
            val buffer = ByteArray(65535) // Max UDP packet size
            val packet = DatagramPacket(buffer, buffer.size)

            while (_isConnected && socket != null) {
                try {
                    // Blocking receive on IO thread
                    socket?.receive(packet)
                    
                    val actualLength = packet.length
                    val data = buffer.copyOf(actualLength)
                    
                    logger.trace("Received {} bytes from Cellular UDP socket", actualLength)

                    val remoteSocketAddress = packet.socketAddress as? InetSocketAddress
                    val datagramCallback = onDatagramReceived

                    if (datagramCallback != null && remoteSocketAddress != null) {
                        val sourceHost = remoteSocketAddress.address.hostAddress
                        val sourcePortValue = remoteSocketAddress.port
                        
                        if (sourceHost != null) {
                            val sourceAddress = IPAddress.parse(sourceHost)
                            val sourcePort = Port(sourcePortValue)
                            
                            if (sourceAddress != null) {
                                withContext(Dispatchers.Main.immediate) {
                                    datagramCallback.invoke(data, sourceAddress, sourcePort)
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main.immediate) {
                        delegate?.get()?.didReceive(data, this@RawCellularUDPSocket)
                    }

                    // Reset packet length for next receive exactly as typical Java implementations do
                    packet.length = buffer.size

                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error("Error in Cellular UDP read loop: {}", e.message, e)
                    withContext(Dispatchers.Main.immediate) {
                        delegate?.get()?.didErrorOccur(e, this@RawCellularUDPSocket)
                    }
                    break
                }
            }
        }
    }
}
