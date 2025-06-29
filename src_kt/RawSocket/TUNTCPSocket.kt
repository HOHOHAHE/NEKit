package com.example.nekit.RawSocket

import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.io.IOException
import java.io.ByteArrayOutputStream // For pendingReadData buffer
import kotlinx.coroutines.sync.Mutex // For writeMutex

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
// import com.example.nekit.Messages.ConnectSession


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
) : AdapterSocket(initialRawSocket = null, observe = observe), // AdapterSocket's rawSocket is null here
    LibTun2SocksSocketCallbacks {

    private val logger = LoggerFactory.getLogger(TUNTCPSocket::class.java)

    // State for managing reads
    private var reading: Boolean = false
    private val pendingReadData = ByteArrayOutputStream() // Buffer for data from tun2socks
    private var readLengthTarget: Int? = null
    private var scanner: StreamScanner? = null // Assuming StreamScanner.kt from Utils

    // State for managing writes
    private var remainWriteLength: Int = 0
    private var closeAfterWriting: Boolean = false
    private val writeMutex = Mutex() // Protects remainWriteLength

    // Coroutine scope for this socket's operations and delegate calls
    private val internalScope: CoroutineScope = customScope ?: CoroutineScope(
        (QueueFactory.getProcessingDispatcher() ?: Dispatchers.Default) + SupervisorJob()
    )

    init {
        logger.info("TUNTCPSocket created for socketId: {}. Observer enabled: {}", socketId, observe)
        // The AdapterSocket constructor (super) handles observer setup.
        // Association of this TUNTCPSocket instance with JNA callbacks for this socketId
        // is handled by JnaLibTun2Socks when onTcpSocketCreated calls it.
        // Initial status is SocketStatus.INVALID (from AdapterSocket).
        // It will be updated by LibTun2SocksSocketCallbacks.onConnected.
    }

    // --- RawTCPSocketProtocol Implementation ---

    override val rawSocket: RawTCPSocketProtocol?
        get() {
            logger.warn("TUNTCPSocket (socketId: {}) does not have a direct 'rawSocket' property in the traditional sense; operations are via tun2socks. Returning null.", socketId)
            return null
        }

    override val isConnected: Boolean // Based on AdapterSocket status
        get() = _status == SocketStatus.ESTABLISHED || _status == SocketStatus.CONNECTING // CONNECTING because onConnected sets ESTABLISHED.

    // IP/Port properties: These represent the *target* destination, derived from the ConnectSession
    // that the Tunnel will set on this AdapterSocket via openSocketWith().
    override val sourceIPAddress: IPAddress? // This is effectively the client's IP as seen by the proxy system
        get() = if (super.rawSocket?.sourceIPAddress != null) super.rawSocket?.sourceIPAddress else null // Should be null for TUNTCPSocket

    override val sourcePort: Port? // Client's port
        get() = if (super.rawSocket?.sourcePort != null) super.rawSocket?.sourcePort else null


    override val destinationIPAddress: IPAddress? // Target destination IP
        get() = if (::_session.isInitialized && session != null) IPAddress.parse(session!!.host) else {
            logger.trace("destinationIPAddress accessed for socketId {} before session initialization, returning null.", socketId)
            null
        }
    override val destinationPort: Port? // Target destination port
        get() = if (::_session.isInitialized && session != null) Port(session!!.port.toUShort()) else {
            logger.trace("destinationPort accessed for socketId {} before session initialization, returning null.", socketId)
            null
        }

    @Throws(UnsupportedOperationException::class)
    override suspend fun connectTo(host: String, port: Int, enableTLS: Boolean, tlsSettings: Map<String, Any>?) {
        logger.error("connectTo called on TUNTCPSocket (socketId: {}). This is not supported; connections are managed by tun2socks based on TUN packets.", socketId)
        throw UnsupportedOperationException("TUNTCPSocket cannot initiate connections actively via connectTo. It represents a connection managed by tun2socks.")
    }

    // openSocketWith is inherited from AdapterSocket.
    // It sets `this.session`. Crucially, it expects `this.rawSocket` to be non-null to set its delegate.
    // Since `this.rawSocket` is null, we must override it or ensure the base version handles null rawSocket gracefully.
    // The base `AdapterSocket.openSocketWith` has a null check, which will lead to it erroring out.
    // For TUNTCPSocket, `openSocketWith` is more about associating the `ConnectSession` (target)
    // with this `socketId` which is already "connecting" or "connected" from tun2socks' perspective.
    override fun openSocketWith(session: ConnectSession) {
        if (isCancelled) {
            logger.warn("TUNTCPSocket {}: openSocketWith called on a cancelled socket for session: {}", socketId, session)
            return
        }
        this.session = session // Set the session from AdapterSocket
        logger.info("TUNTCPSocket {}: Associated with session: {}. Current status: {}", socketId, session, _status)
        // Do NOT call super.openSocketWith(session) as it assumes a non-null rawSocket for its primary logic.
        // Instead, if status is already ESTABLISHED (from onConnected), we might be ready to forward.
        // Or, we wait for onConnected if it hasn't happened yet.
        // Observer signal from AdapterSocket.openSocketWith is not called here.
        // If needed: observer?.signal(AdapterSocketEvent.SocketOpened(this, session))

        // If tun2socks has already called onConnected, then _status would be ESTABLISHED.
        // We can then signal ready for forward.
        if (_status == SocketStatus.ESTABLISHED) {
             internalScope.launch {
                logger.info("TUNTCPSocket {}: Was already connected by tun2socks. Signaling ready for forward for session: {}", socketId, session)
                delegate?.get()?.didBecomeReadyToForward(this@TUNTCPSocket)
                observer?.signal(AdapterSocketEvent.ReadyForForward(this@TUNTCPSocket))
             }
        } else {
            // Still waiting for onConnected from tun2socks.
            _status = SocketStatus.CONNECTING // Indicate we are in the process of setting up.
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
            if (_status == SocketStatus.CLOSED && _cancelled) { // Use AdapterSocket's _cancelled
                 logger.debug("TUNTCPSocket {}: Already force-closed and cancelled.", socketId)
                return@launch
            }

            // Call AdapterSocket's state update for cancellation and session notification
            super.forceDisconnect(becauseOf) // This sets _cancelled, _status, notifies session/observer

            stackInterface.closeTcp(socketId) // Tell tun2socks to close the native socket

            // If the native close doesn't trigger onRemoteClosed or onError immediately,
            // we might need to finalize the state here.
            // However, usually the callback from native layer (onRemoteClosed/onError) should trigger didDisconnect.
            // For safety, if status isn't CLOSED after a short delay, force it.
            // This is tricky; best to rely on consistent callback behavior.
            // If stackInterface.closeTcp is synchronous and guarantees no more callbacks:
            if (_status != SocketStatus.CLOSED) { // If callbacks didn't make it closed yet
                 logger.warn("TUNTCPSocket {}: Forcing status to CLOSED locally after stackInterface.closeTcp, if callbacks don't follow.", socketId)
                 // This manual call to didDisconnect is from AdapterSocket's perspective for its delegate
                 this@TUNTCPSocket.delegate?.get()?.didDisconnect(this@TUNTCPSocket)
            }
        }
    }

    @Throws(IOException::class)
    override suspend fun write(data: ByteArray) {
        if (_status != SocketStatus.ESTABLISHED && _status != SocketStatus.FORWARDING) { // Status from AdapterSocket
            throw IOException("TUNTCPSocket $socketId not connected or ready for writes (status: $_status).")
        }
        if (data.isEmpty()) return

        writeMutex.withLock {
            if (remainWriteLength > 0) {
                logger.warn("TUNTCPSocket {} write called while previous write ({} bytes) still pending. This new write ({} bytes) might be dropped or cause issues without proper buffering/flow control.", socketId, remainWriteLength, data.size)
                // Consider throwing IOException("Previous write pending for socket $socketId")
            }
            remainWriteLength = data.size
        }

        logger.debug("TUNTCPSocket {} writing {} bytes to tun2socks.", socketId, data.size)
        val bytesWritten = stackInterface.writeData(socketId, data, data.size)

        if (bytesWritten < 0) { // Error
            val error = IOException("tun2socks writeData failed for socket $socketId, code: $bytesWritten")
            logger.error("TUNTCPSocket {} failed to write data. Error code: {}", socketId, bytesWritten, error)
            writeMutex.withLock { remainWriteLength = 0 } // Reset pending write count on error
            delegate?.get()?.didErrorOccur(error, this)
            forceDisconnect(error)
            throw error
        } else { // Includes bytesWritten == 0 (native buffer full) or partial write
            writeMutex.withLock {
                remainWriteLength -= bytesWritten
                if(remainWriteLength < 0) remainWriteLength = 0
            }
            if (bytesWritten < data.size) {
                logger.warn("TUNTCPSocket {} partial write: {} of {} bytes written. Waiting for onWritePossible for remainder.", socketId, bytesWritten, data.size)
                // Need to buffer remainder and wait for onWritePossible. This part is complex and not fully implemented here.
                // For now, the data is considered partially sent to native layer.
            }
            // If bytesWritten == data.size, onWritePossible should eventually call delegate.didWrite(null, this)
            // If bytesWritten == 0 and no error, it means native buffer is full. Wait for onWritePossible.
            // The current contract of RawTCPSocketProtocol.write is suspend until fully written or error.
            // This JNA bridge for writeData is not fully suspending for the *entire* logical write if native layer buffers.
            // onWritePossible is key to confirm full send.
            // For now, we assume if bytesWritten > 0, it's "accepted" by tun2socks.
            // The delegate.didWrite should ideally be called when tun2socks confirms actual send or readiness for more.
            // This will be triggered by onWritePossible.
        }
    }

    override fun readData() {
        internalScope.launch {
            logger.debug("TUNTCPSocket {} readData() called by delegate.", socketId)
            reading = true
            readLengthTarget = null
            scanner = null
            checkAndProcessPendingReadData()
        }
    }

    override fun readDataTo(length: Int) {
        if (length <= 0) {
            logger.error("TUNTCPSocket {} readDataTo(length): length must be positive, got {}.", socketId, length)
            delegate?.get()?.didErrorOccur(IllegalArgumentException("Length must be positive for readDataTo."), this)
            return
        }
        internalScope.launch {
            logger.debug("TUNTCPSocket {} readDataTo(length: {}) called.", socketId, length)
            reading = true
            readLengthTarget = length
            scanner = null
            checkAndProcessPendingReadData()
        }
    }

    override fun readDataTo(delimiter: ByteArray) {
        readDataTo(delimiter, 0) // Default maxLength for scanner (Opt.MAX_SCAN_LENGTH typically)
    }

    override fun readDataTo(delimiter: ByteArray, maxLength: Int) {
        internalScope.launch {
            logger.debug("TUNTCPSocket {} readDataTo(delimiter, maxLength: {}) called.", socketId, maxLength)
            reading = true
            readLengthTarget = null
            scanner = StreamScanner(delimiter, maxLength)
            checkAndProcessPendingReadData()
        }
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
            super.didConnect(this) // Call base AdapterSocket's didConnect
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
