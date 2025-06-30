package com.example.nekit.RawSocket

import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.io.IOException
import java.io.ByteArrayOutputStream // For pendingReadData buffer
import kotlinx.coroutines.sync.Mutex // For writeMutex
import kotlinx.coroutines.sync.withLock // For withLock extension function

import org.slf4j.LoggerFactory

import com.example.nekit.RawSocket.RawTCPSocketProtocol // Corrected import
import com.example.nekit.RawSocket.RawTCPSocketDelegate // Corrected import
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import com.example.nekit.Utils.StreamScanner
import com.example.nekit.Tunnel.QueueFactory
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.SocketStatus
import com.example.nekit.Event.Event.AdapterSocketEvent
import com.example.nekit.Messages.EventSource
// Import JNA related LibTun2Socks interfaces
import com.example.nekit.IPStack.Native.LibTun2SocksSocketCallbacks
import com.example.nekit.IPStack.Native.LibTun2SocksStackInterface

// Assuming ConnectSession is imported or available for AdapterSocket's session property
import com.example.nekit.Messages.ConnectSession


/**
 * TCP socket implementation built upon a tun2socks stack-managed connection.
 * Implements AdapterSocket (which implements RawTCPSocketProtocol) for interaction with the application's
 * higher levels (like Tunnel -> ProxyServer), and LibTun2SocksSocketCallbacks to receive
 * events from the native tun2socks layer via JNA.
 */
class TUNTCPSocket(
    internal val socketId: Int, // ID from tun2socks
    private val stackInterface: LibTun2SocksStackInterface, // Interface to call native tun2socks functions
    observe: Boolean = true, // For AdapterSocket observer
    // Allow passing a custom scope, primarily for testing or specific dispatching needs.
    // Defaults to QueueFactory.getProcessingDispatcher() for operations.
    customScope: CoroutineScope? = null
) : AdapterSocket(initialRawSocket = null, observe = observe), LibTun2SocksSocketCallbacks {

    private val logger = LoggerFactory.getLogger(TUNTCPSocket::class.java)

    private var reading: Boolean = false
    private val pendingReadData = ByteArrayOutputStream()
    private var readLengthTarget: Int? = null
    private var scanner: StreamScanner? = null

    private var remainWriteLength: Int = 0
    private var closeAfterWriting: Boolean = false
    private val writeMutex = Mutex()

    private val internalScope: CoroutineScope = customScope ?: CoroutineScope(
        (QueueFactory.getProcessingDispatcher() ?: Dispatchers.Default) + SupervisorJob()
    )

    init {
        logger.info("TUNTCPSocket created for socketId: {}. Observer enabled: {}", socketId, observe)
    }

    override val rawSocket: RawTCPSocketProtocol?
        get() = null

    override val isConnected: Boolean
        get() = _status == SocketStatus.ESTABLISHED || _status == SocketStatus.CONNECTING

    override val sourceIPAddress: IPAddress?
        get() = null

    override val sourcePort: Port?
        get() = null

    override val destinationIPAddress: IPAddress?
        get() = session?.host?.let { IPAddress.parse(it) }

    override val destinationPort: Port?
        get() = session?.port?.let { Port(it.toInt()) }

    override fun openSocketWith(session: ConnectSession) {
        if (isCancelled) {
            logger.warn("TUNTCPSocket {}: openSocketWith called on a cancelled socket for session: {}", socketId, session)
            return
        }
        this.session = session
        logger.info("TUNTCPSocket {}: Associated with session: {}. Current status: {}", socketId, session, _status)
        observer?.signal(AdapterSocketEvent.SocketOpened(this, session))

        if (_status == SocketStatus.ESTABLISHED) {
            internalScope.launch {
                logger.info("TUNTCPSocket {}: Was already connected by tun2socks. Signaling ready for forward for session: {}", socketId, session)
                delegate?.get()?.didBecomeReadyToForward(this@TUNTCPSocket)
            }
        } else {
            _status = SocketStatus.CONNECTING
        }
    }

    override fun disconnect(becauseOf: Throwable?) {
        internalScope.launch {
            logger.info("TUNTCPSocket {}: disconnect() called. Error: {}", socketId, becauseOf?.message)
            if (_status == SocketStatus.CLOSED || _status == SocketStatus.DISCONNECTING) {
                logger.debug("TUNTCPSocket {}: Already closing or closed.", socketId)
                return@launch
            }

            closeAfterWriting = true
            checkWriteStatusAndCloseIfNeeded()
        }
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        internalScope.launch {
            logger.info("TUNTCPSocket {}: forceDisconnect() called. Error: {}", socketId, becauseOf?.message)
            if (_status == SocketStatus.CLOSED && _cancelled) {
                logger.debug("TUNTCPSocket {}: Already force-closed and cancelled.", socketId)
                return@launch
            }

            super.forceDisconnect(becauseOf)
            stackInterface.closeTcp(socketId)

            if (_status != SocketStatus.CLOSED) {
                logger.warn("TUNTCPSocket {}: Forcing status to CLOSED locally after stackInterface.closeTcp, if callbacks don't follow.", socketId)
                this@TUNTCPSocket.delegate?.get()?.didDisconnect(this@TUNTCPSocket)
            }
        }
    }

    @Throws(IOException::class)
    override suspend fun write(data: ByteArray) {
        if (_status != SocketStatus.ESTABLISHED && _status != SocketStatus.CONNECTING) {
            throw IOException("TUNTCPSocket $socketId not connected or ready for writes (status: $_status).")
        }
        if (data.isEmpty()) return

        writeMutex.withLock {
            if (remainWriteLength > 0) {
                logger.warn("TUNTCPSocket {} write called while previous write ({} bytes) still pending.", socketId, remainWriteLength)
            }
            remainWriteLength = data.size
        }

        logger.debug("TUNTCPSocket {} writing {} bytes to tun2socks.", socketId, data.size)
        val bytesWritten = stackInterface.writeData(socketId, data, data.size)

        if (bytesWritten < 0) {
            val error = IOException("tun2socks writeData failed for socket $socketId, code: $bytesWritten")
            logger.error("TUNTCPSocket {} failed to write data. Error code: {}", socketId, bytesWritten, error)
            writeMutex.withLock { remainWriteLength = 0 }
            delegate?.get()?.didErrorOccur(error, this)
            forceDisconnect(error)
            throw error
        } else {
            writeMutex.withLock {
                remainWriteLength -= bytesWritten
                if (remainWriteLength < 0) remainWriteLength = 0
            }
            if (bytesWritten < data.size) {
                logger.warn("TUNTCPSocket {} partial write: {} of {} bytes written.", socketId, bytesWritten, data.size)
            }
        }
    }

    override suspend fun readData() {
        logger.debug("TUNTCPSocket {} readData() called by delegate.", socketId)
        reading = true
        readLengthTarget = null
        scanner = null
        checkAndProcessPendingReadData()
    }

    override suspend fun readDataTo(length: Int) {
        if (length <= 0) {
            val error = IllegalArgumentException("Length must be positive for readDataTo.")
            logger.error("TUNTCPSocket {} readDataTo(length): invalid length {}.", socketId, length, error)
            delegate?.get()?.didErrorOccur(error, this)
            return
        }
        logger.debug("TUNTCPSocket {} readDataTo(length: {}) called.", socketId, length)
        reading = true
        readLengthTarget = length
        scanner = null
        checkAndProcessPendingReadData()
    }

    override suspend fun readDataTo(delimiter: ByteArray) {
        readDataTo(delimiter, 0)
    }

    override suspend fun readDataTo(delimiter: ByteArray, maxLength: Int) {
        logger.debug("TUNTCPSocket {} readDataTo(delimiter, maxLength: {}) called.", socketId, maxLength)
        reading = true
        readLengthTarget = null
        scanner = StreamScanner(delimiter, maxLength)
        checkAndProcessPendingReadData()
    }

    // --- Internal Logic for Read Buffer Processing ---
    private suspend fun checkAndProcessPendingReadData() {
        if (!reading || pendingReadData.size() == 0) {
            logger.trace("TUNTCPSocket {}: checkAndProcessPendingReadData: not reading or no pending data.", socketId)
            return
        }

        val currentReadLengthTarget = readLengthTarget
        val currentScanner = scanner
        val availableData = pendingReadData.toByteArray() // Process a copy

        if (currentReadLengthTarget != null) {
            if (availableData.size >= currentReadLengthTarget) {
                val dataToReturn = availableData.copyOfRange(0, currentReadLengthTarget)
                val remaining = availableData.copyOfRange(currentReadLengthTarget, availableData.size)
                pendingReadData.reset()
                pendingReadData.write(remaining)

                readLengthTarget = null
                reading = false
                logger.debug("TUNTCPSocket {} didRead {} bytes (target length).", socketId, dataToReturn.size)
                delegate?.get()?.didRead(dataToReturn, this)
            } else {
                 logger.trace("TUNTCPSocket {}: Not enough data for target length {}. Have {}.", socketId, currentReadLengthTarget, availableData.size)
            }
        } else if (currentScanner != null) {
            val scanResult = currentScanner.addAndScan(availableData)

            if (scanResult != null) {
                this.scanner = null
                reading = false
                val foundPart = scanResult.first
                val remainderPart = scanResult.second

                pendingReadData.reset()
                if (remainderPart.isNotEmpty()) {
                    pendingReadData.write(remainderPart)
                }

                if (foundPart != null) {
                    logger.debug("TUNTCPSocket {} didRead {} bytes (delimiter found).", socketId, foundPart.size)
                    delegate?.get()?.didRead(foundPart, this)
                } else {
                    logger.warn("TUNTCPSocket {}: StreamScanner finished (e.g. maxLength) without finding delimiter. Data remaining in buffer: {}. This partial data is NOT delivered.", socketId, pendingReadData.size())
                }
            } else {
                 logger.trace("TUNTCPSocket {}: StreamScanner needs more data. Current buffer size: {}.", socketId, pendingReadData.size())
            }
        } else { // General readData()
            pendingReadData.reset()
            reading = false
            logger.debug("TUNTCPSocket {} didRead {} bytes (general read).", socketId, availableData.size)
            delegate?.get()?.didRead(availableData, this)
        }
    }

    private suspend fun checkWriteStatusAndCloseIfNeeded() {
        if (closeAfterWriting && writeMutex.withLock { remainWriteLength <= 0 } ) {
            logger.info("TUNTCPSocket {}: All pending writes completed and closeAfterWriting is true. Forcing disconnect.", socketId)
            forceDisconnect(null) // This will call stackInterface.closeTcp()
        }
    }

    // --- LibTun2SocksSocketCallbacks Implementation ---
    // These are called by JnaLibTun2Socks (from native C callbacks) on JNA's thread.
    // Dispatch to internalScope to ensure thread safety and proper context for delegate calls.

    override fun onConnected(socketId: Int) {
        if (this.socketId != socketId) return
        internalScope.launch {
            logger.info("TUNTCPSocket {} received onConnected from tun2socks.", socketId)
            _status = SocketStatus.ESTABLISHED // Update AdapterSocket status
            // AdapterSocket's didConnect also signals observer
            super.didConnect(this@TUNTCPSocket) // Call base AdapterSocket's didConnect
            // didBecomeReadyToForward is also typically called by AdapterSocket's didConnect
            // If not, call it explicitly:
            // delegate?.get()?.didBecomeReadyToForward(this@TUNTCPSocket)
            // observer?.signal(AdapterSocketEvent.ReadyForForward(this@TUNTCPSocket))
        }
    }

    override fun onDataReceived(socketId: Int, data: ByteArray, len: Int) {
        if (this.socketId != socketId) return
        internalScope.launch {
            val relevantData = if (len < data.size && len >= 0) data.copyOfRange(0, len) else data
            logger.debug("TUNTCPSocket {} received onDataReceived ({} bytes) from tun2socks.", socketId, relevantData.size)
            pendingReadData.write(relevantData)
            if (reading) {
                checkAndProcessPendingReadData()
            }
        }
    }

    override fun onRemoteClosed(socketId: Int) {
        if (this.socketId != socketId) return
        internalScope.launch {
            logger.info("TUNTCPSocket {} received onRemoteClosed from tun2socks.", socketId)
            // AdapterSocket's forceDisconnect handles state and delegate notification.
            forceDisconnect(IOException("Remote peer closed socketId $socketId (tun2socks onRemoteClosed)"))
        }
    }

    override fun onWritePossible(socketId: Int) {
        if (this.socketId != socketId) return
        internalScope.launch {
            logger.debug("TUNTCPSocket {} received onWritePossible from tun2socks.", socketId)
            val needsDelegateCall = writeMutex.withLock {
                if (remainWriteLength > 0) {
                    remainWriteLength = 0
                    true
                } else {
                    false
                }
            }
            if (needsDelegateCall) {
                logger.debug("TUNTCPSocket {}: Previous write operation considered complete due to onWritePossible.", socketId)
                delegate?.get()?.didWrite(null, this@TUNTCPSocket) // Data not available for this callback
            }
            checkWriteStatusAndCloseIfNeeded()
        }
    }

    override fun onError(socketId: Int, errorCode: Int) {
        if (this.socketId != socketId) return
        internalScope.launch {
            val error = IOException("TUNTCPSocket $socketId reported native error code from tun2socks: $errorCode")
            logger.error("TUNTCPSocket {} received onError from tun2socks. Code: {}", socketId, errorCode, error)
            // AdapterSocket's forceDisconnect handles state and delegate notification.
            forceDisconnect(error)
        }
    }
}
