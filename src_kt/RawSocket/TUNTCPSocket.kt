import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.io.IOException
import java.util.LinkedList // For pendingReadData if preferred over ByteArray concatenation

// Assuming RawTCPSocketProtocol.kt, RawTCPSocketDelegate.kt, IPAddress.kt, Port.kt,
// StreamScanner.kt, QueueFactory.kt (placeholders) are available.
// Assuming TSTCPSocketInterface and TSTCPSocketDelegate placeholders (from TCPStack.kt context) are available.

// --- Refined Placeholders for TSTCP (tun2socks socket) ---
// TODO: These interfaces require a JNI/JNA binding to a functional tun2socks native library.

interface TSTCPSocketDelegate {
    fun localDidClose(socket: TSTCPSocketInterface)
    fun socketDidReset(socket: TSTCPSocketInterface)
    fun socketDidAbort(socket: TSTCPSocketInterface)
    fun socketDidClose(socket: TSTCPSocketInterface) // General close
    fun didReadData(data: ByteArray, from: TSTCPSocketInterface)
    fun didWriteData(length: Int, from: TSTCPSocketInterface) // Reports how many bytes were written
}

interface TSTCPSocketInterface {
    var delegate: TSTCPSocketDelegate?

    val isConnected: Boolean
    val sourceIPAddressBytes: ByteArray // Raw bytes for IPv4 in_addr
    val sourcePortNetOrder: UShort // Network byte order
    val destinationIPAddressBytes: ByteArray // Raw bytes for IPv4 in_addr
    val destinationPortNetOrder: UShort // Network byte order

    fun writeData(data: ByteArray) // Asynchronous write
    fun close() // Close the tun2socks connection
    // Add any other methods from TSTCPSocket used by TUNTCPSocket
}

// Example Placeholder TSTCPSocket
// TODO: Replace with actual JNI/JNA implementation for tun2socks
class PlaceholderTSTCPSocket : TSTCPSocketInterface {
    override var delegate: TSTCPSocketDelegate? = null
    override val isConnected: Boolean get() = _isConnectedInternal
    private var _isConnectedInternal = true // Simulate initially connected for accepted socket

    // Dummy addresses
    override val sourceIPAddressBytes: ByteArray = IPAddress.parse("192.168.200.1")!!.addressBytes
    override val sourcePortNetOrder: UShort = Port(12345u).networkOrderValue
    override val destinationIPAddressBytes: ByteArray = IPAddress.parse("192.168.200.2")!!.addressBytes
    override val destinationPortNetOrder: UShort = Port(80u).networkOrderValue

    private val socketOpScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun writeData(data: ByteArray) {
        println("INFO: PlaceholderTSTCPSocket: writeData(${data.size} bytes) called.")
        socketOpScope.launch {
            delay(10) // Simulate write delay
            delegate?.didWriteData(data.size, this@PlaceholderTSTCPSocket)
        }
    }

    override fun close() {
        println("INFO: PlaceholderTSTCPSocket: close() called.")
        if (_isConnectedInternal) {
            _isConnectedInternal = false
            socketOpScope.launch {
                delay(10)
                delegate?.socketDidClose(this@PlaceholderTSTCPSocket)
            }
        }
    }

    // Method to simulate receiving data (for testing TUNTCPSocket)
    fun simulateReceiveData(data: ByteArray) {
        if (_isConnectedInternal) {
            socketOpScope.launch {
                delegate?.didReadData(data, this@PlaceholderTSTCPSocket)
            }
        }
    }
}
// --- End TSTCP Placeholders ---


/**
 * TCP socket implementation built upon a TSTCPSocket from a tun2socks stack.
 * Implements RawTCPSocketProtocol for interaction with the application.
 *
 * Note: Assumed to be accessed and have its delegate methods called on a consistent
 * coroutine dispatcher (provided by QueueFactory).
 */
class TUNTCPSocket(
    private val tsSocket: TSTCPSocketInterface
) : RawTCPSocketProtocol, TSTCPSocketDelegate {

    override var delegate: WeakReference<RawTCPSocketDelegate?>? = null

    private var reading: Boolean = false
    private var pendingReadData: ByteArray = ByteArray(0) // Buffer for data from TSTCPSocket
    private var readLengthTarget: Int? = null // For readDataTo(length:)
    private var scanner: StreamScanner? = null // For readDataTo(delimiter:)

    private var remainWriteLength: Int = 0
    private var closeAfterWriting: Boolean = false

    // Dispatcher for all internal logic and delegate calls, serializing access.
    private val socketDispatcher: CoroutineDispatcher = QueueFactory.executionScope.coroutineContext[CoroutineDispatcher] ?: Dispatchers.Default
    private val internalScope = CoroutineScope(socketDispatcher + SupervisorJob())


    init {
        tsSocket.delegate = this
    }

    // --- RawTCPSocketProtocol Implementation ---
    override val isConnected: Boolean
        get() = tsSocket.isConnected

    override val sourceIPAddress: IPAddress?
        get() = IPAddress.fromBytes(tsSocket.sourceIPAddressBytes, IPAddress.Family.IPv4) // Assuming IPv4 fromInAddr
    override val sourcePort: Port?
        get() = Port(tsSocket.sourcePortNetOrder) // Port constructor takes network order

    override val destinationIPAddress: IPAddress?
        get() = IPAddress.fromBytes(tsSocket.destinationIPAddressBytes, IPAddress.Family.IPv4)
    override val destinationPort: Port?
        get() = Port(tsSocket.destinationPortNetOrder)

    @Throws(UnsupportedOperationException::class)
    override suspend fun connectTo(host: String, port: Int, enableTLS: Boolean, tlsSettings: Map<String, Any>?) {
        // TUNTCPSockets are typically accepted by the TCPStack (from tun2socks), not actively connected.
        throw UnsupportedOperationException("TUNTCPSocket cannot initiate connections actively.")
    }

    override fun disconnect() {
        internalScope.launch {
            if (!isConnected) {
                delegate?.get()?.didDisconnect(this@TUNTCPSocket)
            } else {
                closeAfterWriting = true
                checkWriteStatusAndCloseIfNeeded()
            }
        }
    }

    override fun forceDisconnect() {
        internalScope.launch {
            if (!isConnected) {
                // If already disconnected, still notify delegate to ensure consistent event flow
                // if disconnect() wasn't the source of the disconnection state.
                delegate?.get()?.didDisconnect(this@TUNTCPSocket)
            } else {
                tsSocket.close() // This should trigger TSTCPSocketDelegate.socketDidClose
            }
        }
    }

    @Throws(IOException::class)
    override suspend fun write(data: ByteArray) {
        if (!isConnected) throw IOException("Socket not connected.")
        // The Swift version set remainWriteLength = data.count before calling tsSocket.writeData.
        // This implies that only one write can be outstanding.
        // If a previous write was pending (remainWriteLength > 0), this might be an issue.
        // For now, directly replicate:
        internalScope.launch { // Ensure tsSocket calls are on its expected context if any
            // TODO: Review thread safety if write() can be called concurrently before didWriteData clears remainWriteLength
            stateMutex.withLock { // Assuming a shared mutex for critical sections like this
                 remainWriteLength = data.size
            }
            tsSocket.writeData(data)
        }
    }
    // Companion object for mutex if needed across instances, or instance member if per-instance
    companion object {
        private val stateMutex = Mutex() // Example if shared state needs protection this way
    }


    override fun readData() {
        internalScope.launch {
            reading = true
            readLengthTarget = null
            scanner = null
            checkAndProcessPendingReadData()
        }
    }

    override fun readDataTo(length: Int) {
        if (length <= 0) throw IllegalArgumentException("Length must be positive.")
        internalScope.launch {
            reading = true
            readLengthTarget = length
            scanner = null
            checkAndProcessPendingReadData()
        }
    }

    override fun readDataTo(delimiter: ByteArray) {
        readDataTo(delimiter, 0) // maxLength 0 means use Opt.MAXNWTCPScanLength via StreamScanner default
    }

    override fun readDataTo(delimiter: ByteArray, maxLength: Int) {
        internalScope.launch {
            reading = true
            readLengthTarget = null
            var effectiveMaxLength = maxLength
            if (effectiveMaxLength == 0 && StreamScanner::class.java.constructors.any { it.parameterCount == 2 }) { // Check if Opt is available
                 // effectiveMaxLength = Opt.MAXNWTCPScanLength // Default from Opt if scanner uses it
                 // For now, if StreamScanner handles default max length internally, this is fine.
            }
            scanner = StreamScanner(delimiter, effectiveMaxLength) // Assuming StreamScanner handles maxLength=0 as "use default" or "no limit within reason"
            checkAndProcessPendingReadData()
        }
    }

    // --- Internal Logic ---
    private suspend fun checkAndProcessPendingReadData() { // Must be called from internalScope (socketDispatcher)
        if (!reading || pendingReadData.isEmpty()) {
            // Not requesting a read, or no data to process.
            // If reading=true and pendingReadData is empty, it means we are waiting for TSTCP's didReadData.
            return
        }

        val currentReadLengthTarget = readLengthTarget
        val currentScanner = scanner

        if (currentReadLengthTarget != null) { // Read specific length
            if (pendingReadData.size >= currentReadLengthTarget) {
                val dataToReturn = pendingReadData.copyOfRange(0, currentReadLengthTarget)
                pendingReadData = pendingReadData.copyOfRange(currentReadLengthTarget, pendingReadData.size)

                readLengthTarget = null
                reading = false
                delegate?.get()?.didRead(dataToReturn, this)
            }
            // Else, not enough data yet, wait for more from TSTCPSocket.
        } else if (currentScanner != null) { // Read with scanner (delimiter)
            // StreamScanner.addAndScan might return:
            // Pair(foundData, remainderData) -> success
            // Pair(null, allScannedData) -> error (e.g. maxLength)
            // null -> not enough data yet
            val scanResult = currentScanner.addAndScan(pendingReadData)

            if (scanResult != null) { // Scanner found something or hit a limit
                this.scanner = null // Reset scanner
                reading = false
                val foundPart = scanResult.first
                val remainderPart = scanResult.second

                if (foundPart != null) { // Pattern found
                    pendingReadData = if (remainderPart.isNotEmpty()) remainderPart else ByteArray(0)
                    delegate?.get()?.didRead(foundPart, this)
                } else { // Error from scanner (e.g., maxLength without delimiter)
                    pendingReadData = remainderPart // Store what was scanned (errorData)
                    // What to do? Original Swift just returns. Delegate is not called with error here.
                    // This might mean the data is kept in pendingReadData for next read call.
                    // Or, it could be an error state. For now, keep data and stop 'reading' state.
                    System.err.println("WARN: TUNTCPSocket: StreamScanner finished (e.g. maxLength) without finding delimiter. Data remaining: ${pendingReadData.size}")
                    // To match Swift behavior of just stopping, we don't call delegate.didRead or error.
                    // The next readData() call would process this pendingReadData.
                }
            }
            // Else, scanner needs more data, wait for more from TSTCPSocket.
            // pendingReadData is already updated by scanner.addAndScan modifying its internal buffer.
            // No, StreamScanner.addAndScan in Kotlin was made to take ByteArray and return new Pair.
            // So, if scanResult is null, means pendingReadData was not enough for scanner to make a decision.
            // It's already up-to-date.
        } else { // General readData() call, no specific length or delimiter
            val dataToReturn = pendingReadData
            pendingReadData = ByteArray(0)
            reading = false
            delegate?.get()?.didRead(dataToReturn, this)
        }
    }

    private suspend fun checkWriteStatusAndCloseIfNeeded() { // Must be called from internalScope
        if (closeAfterWriting && remainWriteLength <= 0) {
            forceDisconnect() // Calls tsSocket.close()
        }
    }

    // --- TSTCPSocketDelegate Implementation ---
    // These are called by the underlying TSTCPSocketInterface implementation.
    // Ensure they run on the socketDispatcher.

    override fun localDidClose(socket: TSTCPSocketInterface) {
        internalScope.launch {
            println("INFO: TUNTCPSocket: localDidClose from TSTCPSocket. Initiating disconnect.")
            disconnect()
        }
    }

    override fun socketDidReset(socket: TSTCPSocketInterface) {
        internalScope.launch {
            println("INFO: TUNTCPSocket: socketDidReset from TSTCPSocket.")
            delegate?.get()?.didErrorOccur(IOException("Socket reset by peer."), this@TUNTCPSocket)
            tsSocket.close() // Ensure underlying socket is closed
            delegate?.get()?.didDisconnect(this@TUNTCPSocket)
            delegate = null
        }
    }

    override fun socketDidAbort(socket: TSTCPSocketInterface) {
        internalScope.launch {
             println("INFO: TUNTCPSocket: socketDidAbort from TSTCPSocket.")
            delegate?.get()?.didErrorOccur(IOException("Socket aborted."), this@TUNTCPSocket)
            tsSocket.close()
            delegate?.get()?.didDisconnect(this@TUNTCPSocket)
            delegate = null
        }
    }

    override fun socketDidClose(socket: TSTCPSocketInterface) { // General close from TSTCP
        internalScope.launch {
            println("INFO: TUNTCPSocket: socketDidClose from TSTCPSocket.")
            val currentDelegate = delegate?.get()
            delegate = null // Clear delegate first
            currentDelegate?.didDisconnect(this@TUNTCPSocket)
        }
    }

    override fun didReadData(data: ByteArray, from: TSTCPSocketInterface) {
        internalScope.launch {
            pendingReadData += data
            if (reading) { // Only process if a read operation was requested
                checkAndProcessPendingReadData()
            }
        }
    }

    override fun didWriteData(length: Int, from: TSTCPSocketInterface) {
        internalScope.launch {
            var needsDelegateCall = false
            stateMutex.withLock { // Protect remainWriteLength
                if (remainWriteLength > 0) { // Check if there was a pending write operation
                    remainWriteLength -= length
                    if (remainWriteLength <= 0) {
                        remainWriteLength = 0 // Ensure it's not negative
                        needsDelegateCall = true
                    }
                }
            }
            if (needsDelegateCall) {
                delegate?.get()?.didWrite(null, this@TUNTCPSocket) // Data not available for this callback in Swift
            }
            checkWriteStatusAndCloseIfNeeded()
        }
    }
}
