import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.io.IOException
// Assuming RawTCPSocketProtocol.kt, RawTCPSocketDelegate.kt, IPAddress.kt, Port.kt,
// StreamScanner.kt, Opt.kt, QueueFactory.kt (placeholders) are available.
// TODO: Replace CocoaLumberjack DDLog with a Kotlin logging solution.

// --- Placeholders for Network.framework style connection ---
// TODO: These interfaces require a robust implementation using Java NIO, Netty, Ktor Client, etc.

enum class KotlinNWConnectionState {
    IDLE, // Not in NWConnection, but useful initial state
    SETUP, // preparing in NWConnection
    CONNECTING, // waiting in NWConnection (during TCP handshake, TLS handshake)
    READY, // connected in NWConnection
    FAILED,
    CANCELLED,
    DISCONNECTED // Not a direct NWConnection state, but result of local disconnect or peer close
}

interface KotlinNWConnectionEventDelegate {
    fun onStateUpdate(state: KotlinNWConnectionState, error: Throwable?)
    fun onDataReceived(data: ByteArray, isComplete: Boolean) // isComplete for message based protocols
    fun onWriteCompleted() // Or onBytesWritten(count: Int)
}

interface KotlinNWConnectionInterface : Closeable { // Closeable for cancelling/closing
    var eventDelegate: KotlinNWConnectionEventDelegate?
    val state: KotlinNWConnectionState

    fun start(tlsParameters: Map<String, Any>?, enableTLS: Boolean) // Initiates connection
    fun send(data: ByteArray, isComplete: Boolean) // isComplete for message based protocols
    fun receive(minimumIncompleteLength: Int, maximumLength: Int) // Triggers async read
    fun receiveMessage(completion: (data: ByteArray?, isComplete: Boolean, error: Throwable?) -> Unit) // Alternative read
    fun readLength(length: Int, completion: (data: ByteArray?, error: Throwable?) -> Unit) // Specific length read

    // For delimiter based read, the connection itself might not support it directly.
    // This is usually built on top of raw receive.
    // The NWTCPSocket will handle delimiter scanning using StreamScanner.

    fun cancel() // Similar to NWTCPConnection.cancel()
    override fun close() = cancel() // Map Closeable.close to cancel

    // Properties to get local/remote endpoint info after connection
    val localEndpointString: String? // e.g. "ip:port"
    val remoteEndpointString: String? // e.g. "ip:port"
}

// Placeholder factory for creating KotlinNWConnectionInterface instances
// This mimics RawSocketFactory.TunnelProvider?.createTCPConnection
object KotlinNWConnectionFactory {
    // In a real app, this might be configured with context needed to create connections (e.g. VPN context)
    // TODO: Implement this factory to return actual connection objects (NIO, Netty, etc.)
    fun createTCPConnection(
        host: String,
        port: Int,
        enableTLS: Boolean,
        tlsParameters: Map<String, Any>?,
        eventDelegate: KotlinNWConnectionEventDelegate? // The connection takes its delegate
    ): KotlinNWConnectionInterface? {
        println("INFO: KotlinNWConnectionFactory: Creating placeholder TCP connection to $host:$port (TLS: $enableTLS)")
        // Return a placeholder connection instance
        return PlaceholderNWConnection(host, port, enableTLS, tlsParameters, eventDelegate)
    }
}

// Basic placeholder implementation for KotlinNWConnectionInterface
// TODO: This needs a full NIO or library-based implementation.
class PlaceholderNWConnection(
    private val host: String, private val port: Int, private val enableTLSOnConnect: Boolean,
    private val tlsSettingsOnConnect: Map<String, Any>?,
    override var eventDelegate: KotlinNWConnectionEventDelegate?
) : KotlinNWConnectionInterface {
    private var _state: KotlinNWConnectionState = KotlinNWConnectionState.IDLE
    override val state: KotlinNWConnectionState get() = _state
    private val connScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isTlsActive = false

    override fun start(tlsParameters: Map<String, Any>?, enableTLS: Boolean) { // Params passed here again for flexibility
        if (_state != KotlinNWConnectionState.IDLE) {
            System.err.println("WARN: PlaceholderNWConnection: Connection already started or starting.")
            return
        }
        println("INFO: PlaceholderNWConnection: Starting connection to $host:$port (TLS: $enableTLS)")
        _state = KotlinNWConnectionState.CONNECTING
        eventDelegate?.onStateUpdate(_state, null)

        connScope.launch {
            delay(100) // Simulate network latency
            if (Math.random() > 0.1) { // Simulate success
                _state = KotlinNWConnectionState.READY
                println("INFO: PlaceholderNWConnection: Connected to $host:$port.")
                eventDelegate?.onStateUpdate(_state, null)
                if (enableTLS) {
                    println("INFO: PlaceholderNWConnection: Starting TLS handshake (simulated)...")
                    delay(50)
                    isTlsActive = true
                    println("INFO: PlaceholderNWConnection: TLS handshake successful (simulated).")
                    // In real NWConnection, state might go through .preparing (for TLS) then .ready again
                    // For simplicity, assume delegate?.didConnect happens after TLS if enabled.
                }
            } else {
                _state = KotlinNWConnectionState.FAILED
                println("ERROR: PlaceholderNWConnection: Connection failed to $host:$port (simulated).")
                eventDelegate?.onStateUpdate(_state, IOException("Connection failed (simulated)"))
            }
        }
    }

    override fun send(data: ByteArray, isComplete: Boolean) {
        if (_state != KotlinNWConnectionState.READY) { System.err.println("ERROR: PlaceholderNWConnection: Not connected, cannot send."); return }
        println("INFO: PlaceholderNWConnection: Sending ${data.size} bytes (isComplete: $isComplete).")
        connScope.launch { delay(10); eventDelegate?.onWriteCompleted() } // Simulate async write completion
    }

    override fun receive(minimumIncompleteLength: Int, maximumLength: Int) {
        if (_state != KotlinNWConnectionState.READY) { System.err.println("ERROR: PlaceholderNWConnection: Not connected, cannot receive."); return }
        println("INFO: PlaceholderNWConnection: receive(min:$minimumIncompleteLength, max:$maximumLength) requested.")
        // Simulate async data arrival
        connScope.launch {
            delay(200)
            // val dummyData = "Hello from placeholder".toByteArray()
            // eventDelegate?.onDataReceived(dummyData, true)
            // To test scanner, we might need to send partial data or specific delimiters
            // For now, this receive will trigger a readCallback with null data in NWTCPSocket if not handled
             eventDelegate?.onDataReceived(ByteArray(0), true) // Simulate an empty read or EOF
        }
    }

    override fun receiveMessage(completion: (data: ByteArray?, isComplete: Boolean, error: Throwable?) -> Unit) {
        // This is an alternative to eventDelegate.onDataReceived, often used by NWConnection
        if (_state != KotlinNWConnectionState.READY) { completion(null, false, IOException("Not connected")); return }
        println("INFO: PlaceholderNWConnection: receiveMessage requested.")
        connScope.launch {
            delay(200)
            // val dummyData = "Message data".toByteArray()
            // completion(dummyData, true, null)
            completion(ByteArray(0), true, null) // Simulate empty read or EOF for now
        }
    }

    override fun readLength(length: Int, completion: (data: ByteArray?, error: Throwable?) -> Unit) {
        if (_state != KotlinNWConnectionState.READY) { completion(null, IOException("Not connected")); return }
        println("INFO: PlaceholderNWConnection: readLength $length requested.")
        connScope.launch {
            delay(200)
            // val dummyData = ByteArray(length) { 'a'.code.toByte() }
            // completion(dummyData, null)
            completion(ByteArray(length), null) // Simulate reading 'length' zero bytes
        }
    }


    override fun cancel() {
        println("INFO: PlaceholderNWConnection: cancel() called.")
        if (_state != KotlinNWConnectionState.CANCELLED && _state != KotlinNWConnectionState.FAILED) {
            val oldState = _state
            _state = KotlinNWConnectionState.CANCELLED
            connScope.coroutineContext.cancelChildren() // Cancel ongoing operations
            if (oldState == KotlinNWConnectionState.READY) { // Only call disconnect if it was connected
                 eventDelegate?.onStateUpdate(_state, null) // State update first
                 // eventDelegate?.onDisconnected(null) // Then specific disconnect if delegate has it
            } else if (oldState != KotlinNWConnectionState.IDLE) { // if it was connecting or setup
                 eventDelegate?.onStateUpdate(_state, null)
            }
        }
    }

    override val localEndpointString: String? get() = if (state == KotlinNWConnectionState.READY) "127.0.0.1:54321" else null
    override val remoteEndpointString: String? get() = if (state == KotlinNWConnectionState.READY) "$host:$port" else null
}

// --- End Placeholders ---


/**
 * TCP socket implementation using an underlying Network.framework style connection
 * (represented by KotlinNWConnectionInterface, abstracting NWTCPConnection).
 *
 * Note: This class is not inherently thread-safe. Callbacks are dispatched to a provided dispatcher.
 */
class NWTCPSocket : RawTCPSocketProtocol, KotlinNWConnectionEventDelegate {

    private var connection: KotlinNWConnectionInterface? = null
    override var delegate: WeakReference<RawTCPSocketDelegate?>? = null

    // State flags
    private var writePending = false
    private var closeAfterWriting = false
    private var cancelled = false // User called disconnect/forceDisconnect

    // For delimiter-based reading
    private var scanner: StreamScanner? = null
    private var scanning: Boolean = false
    private var readDataPrefix: ByteArray? = null // Buffer for data from previous scan

    // Dispatcher for delegate callbacks, from QueueFactory
    private val callbackDispatcher: CoroutineDispatcher = QueueFactory.executionScope.coroutineContext[CoroutineDispatcher] ?: Dispatchers.Default
    private val internalScope = CoroutineScope(callbackDispatcher + SupervisorJob()) // For managing internal tasks like KVO mapping

    override val isConnected: Boolean
        get() = connection?.state == KotlinNWConnectionState.READY

    // Original Swift code returned nil for these.
    // A better implementation would get them from the underlying connection object.
    override val sourceIPAddress: IPAddress? get() = connection?.localEndpointString?.let { IPAddress.parse(it.substringBeforeLast(':')) }
    override val sourcePort: Port? get() = connection?.localEndpointString?.let { it.substringAfterLast(':', "").toUShortOrNull()?.let { p -> Port(p) } }
    override val destinationIPAddress: IPAddress? get() = connection?.remoteEndpointString?.let { IPAddress.parse(it.substringBeforeLast(':')) }
    override val destinationPort: Port? get() = connection?.remoteEndpointString?.let { it.substringAfterLast(':', "").toUShortOrNull()?.let { p -> Port(p) } }


    @Throws(IOException::class) // Or specific connection error
    override suspend fun connectTo(host: String, port: Int, enableTLS: Boolean, tlsSettings: Map<String, Any>?) {
        if (connection != null && connection?.state != KotlinNWConnectionState.CANCELLED && connection?.state != KotlinNWConnectionState.FAILED) {
            System.err.println("WARN: NWTCPSocket: connectTo called on an already existing or active connection.")
            // Potentially close existing or throw error. For now, let it try to create new.
            // connection?.cancel()
        }
        cancelled = false
        closeAfterWriting = false

        // In Swift, this came from RawSocketFactory.TunnelProvider.createTCPConnection
        // Assuming KotlinNWConnectionFactory serves a similar role.
        val newConnection = KotlinNWConnectionFactory.createTCPConnection(
            host, port, enableTLS, tlsSettings, this // Pass self as event delegate
        ) ?: run {
            // This case happened in Swift if TunnelProvider was nil (e.g., extension stopped)
            System.err.println("ERROR: NWTCPSocket: Failed to create TCP connection (KotlinNWConnectionFactory returned null).")
            throw IOException("Failed to create TCP connection instance.")
        }

        this.connection = newConnection
        // KVO on "state" in Swift is replaced by onStateUpdate callback from KotlinNWConnectionEventDelegate.
        // The connection starts itself, and state updates will trigger delegate calls.
        newConnection.start(tlsSettings, enableTLS) // Pass TLS settings again if start method needs them.
    }

    override fun disconnect() {
        if (cancelled) return
        cancelled = true // Mark user intent to disconnect

        if (connection == null || connection?.state == KotlinNWConnectionState.CANCELLED || connection?.state == KotlinNWConnectionState.FAILED) {
            // Already effectively disconnected or never connected, signal immediately
            internalScope.launch { delegate?.get()?.didDisconnect(this@NWTCPSocket) }
        } else {
            closeAfterWriting = true
            checkWriteStatusAndDisconnectIfNeeded()
        }
    }

    override fun forceDisconnect() {
        if (cancelled && connection?.state == KotlinNWConnectionState.CANCELLED) return // Already cancelled
        cancelled = true
        closeAfterWriting = false // Ensure immediate cancel regardless of pending writes

        if (connection == null || connection?.state == KotlinNWConnectionState.CANCELLED || connection?.state == KotlinNWConnectionState.FAILED) {
            internalScope.launch { delegate?.get()?.didDisconnect(this@NWTCPSocket) }
        } else {
            connection?.cancel() // This should trigger onStateUpdate -> .CANCELLED
        }
    }

    @Throws(IOException::class)
    override suspend fun write(data: ByteArray) {
        if (cancelled) throw IOException("Socket cancelled, cannot write.")
        if (connection?.state != KotlinNWConnectionState.READY) throw IOException("Socket not connected, cannot write.")

        if (data.isEmpty()) {
            internalScope.launch { // Dispatch to fulfill async expectation and thread context
                delegate?.get()?.didWrite(data, this@NWTCPSocket)
            }
            return
        }
        writePending = true
        connection?.send(data, true) // Assuming isComplete=true for raw TCP stream data part
    }

    override fun readData() {
        if (cancelled || connection?.state != KotlinNWConnectionState.READY) return
        // NWTCPConnection.readMinimumLength(1, maximumLength: Opt.MAX...)
        // Map to our KotlinNWConnectionInterface.receive(min, max)
        connection?.receive(1, Opt.MAX_NWTCPSOCKET_READ_DATA_SIZE) // Opt const from Opt.kt
    }

    override fun readDataTo(length: Int) {
        if (cancelled || connection?.state != KotlinNWConnectionState.READY) return
        if (length <= 0) throw IllegalArgumentException("Length must be positive.")
        // NWTCPConnection.readLength(length) { data, error -> ... }
        // This was a specific method in Swift. If KotlinNWConnectionInterface has it:
        connection?.readLength(length) { data, error -> // This is a callback style, map to delegate
            handleReadCallback(data, error)
        }
        // If KotlinNWConnectionInterface only has general receive(), then NWTCPSocket
        // would need to buffer and manage reading specific length.
        // PlaceholderNWConnection has readLength.
    }

    override fun readDataTo(delimiter: ByteArray) {
        readDataTo(delimiter, 0) // maxLength 0 means use default from Opt
    }

    override fun readDataTo(delimiter: ByteArray, maxLength: Int) {
        if (cancelled || connection?.state != KotlinNWConnectionState.READY) return

        var effectiveMaxLength = maxLength
        if (effectiveMaxLength == 0) {
            effectiveMaxLength = Opt.MAX_NWTCPSCAN_LENGTH // From Opt.kt
        }
        scanner = StreamScanner(delimiter, effectiveMaxLength) // Assuming StreamScanner.kt from Utils
        scanning = true
        // Start reading, data will come via onDataReceived, which then uses the scanner.
        readData()
    }

    // Implementation of KotlinNWConnectionEventDelegate
    override fun onStateUpdate(state: KotlinNWConnectionState, error: Throwable?) {
        internalScope.launch { // Ensure delegate calls are on the correct dispatcher
            when (state) {
                KotlinNWConnectionState.READY -> {
                    // This state means TCP handshake is done. If TLS was enabled,
                    // it implies TLS is also done (if startTLS was part of connection.start logic).
                    // The original Swift code had separate didConnect (for TCP) and socketDidSecure (for TLS).
                    // If KotlinNWConnectionInterface provides distinct TLS completion, handle that.
                    // For now, READY means fully connected.
                    delegate?.get()?.didConnect(this@NWTCPSocket)
                }
                KotlinNWConnectionState.DISCONNECTED, // Not a direct NWConnection state, but for our purpose
                KotlinNWConnectionState.FAILED,
                KotlinNWConnectionState.CANCELLED -> {
                    cancelled = true // Mark as cancelled internally on any terminal state
                    val currentDelegate = delegate?.get()
                    delegate = null // Clear delegate on final disconnection
                    currentDelegate?.didDisconnect(this@NWTCPSocket)
                }
                else -> { /* Connecting, Setup states - internal to connection, no RawTCPDelegate call */ }
            }
        }
    }

    override fun onDataReceived(data: ByteArray, isComplete: Boolean) {
        internalScope.launch {
            if (cancelled && data.isEmpty()) { // Potentially EOF due to cancel
                 // Delegate might have already been cleared if cancelled led to onStateUpdate(.CANCELLED)
                delegate?.get()?.didDisconnect(this@NWTCPSocket)
                return@launch
            }
            if (cancelled) return@launch


            val dataToProcess = consumeReadDataPrefix(data)

            if (scanning) {
                val currentScanner = scanner
                if (currentScanner == null) { // Should not happen if scanning is true
                    scanning = false
                    delegate?.get()?.didRead(dataToProcess, this@NWTCPSocket)
                    return@launch
                }

                // StreamScanner.addAndScan returns: Pair(foundData, remainderData)? OR Pair(null, errorData) OR null
                val scanResult = currentScanner.addAndScan(dataToProcess)

                if (scanResult == null) { // Pattern not found yet, need more data
                    if (currentScanner.finished) { // MaxLength reached without finding pattern
                        System.err.println("ERROR: NWTCPSocket: StreamScanner reached max length without finding delimiter.")
                        // Deliver what we have, or error, or disconnect. Original code implies just stopping scan.
                        this.scanner = null
                        this.scanning = false
                        // What to deliver? Original Swift just returns if matchData is null.
                        // This implies the partial data (up to maxLength) is lost or implicitly handled.
                        // For robustness, we could send what was scanned if scanner stores it.
                        // Or, signal an error/timeout.
                        // For now, assume if null, just wait for more data by re-reading.
                        readData() // Request more data
                    } else {
                         readData() // Request more data
                    }
                } else {
                    val foundPart = scanResult.first
                    val remainderPart = scanResult.second

                    this.scanner = null // Reset scanner
                    this.scanning = false

                    if (foundPart != null) { // Pattern found
                        this.readDataPrefix = if (remainderPart.isNotEmpty()) remainderPart else null
                        delegate?.get()?.didRead(foundPart, this@NWTCPSocket)
                    } else { // Error from scanner (e.g. maxLength exceeded and it returned Pair(null, allData))
                        // This case is tricky. StreamScanner returns Pair(null, errorData) for error.
                        // The original Swift code: `guard let matchData = match else { return }`
                        // This means if scanner returns (nil, someData) indicating error, it just stops.
                        // It doesn't deliver the errorData as a read.
                        // This might lead to data loss or stall.
                        // For now, mimicking that: if foundPart is null, we don't call didRead.
                        // A more robust solution might involve an error callback or delivering partial data.
                         System.err.println("ERROR: NWTCPSocket: StreamScanner finished with error or no match within limit.")
                         // Potentially disconnect or signal error to delegate.
                         forceDisconnect() // Example: disconnect on scan error
                    }
                }
            } else { // Not scanning for delimiter
                delegate?.get()?.didRead(dataToProcess, this@NWTCPSocket)
            }
        }
    }

    override fun onWriteCompleted() {
        internalScope.launch {
            writePending = false
            // Original Swift `didWrite(data: data, ...)` but data was optional there from some contexts.
            // Here, assuming `data` is not available for this simple ack, pass null.
            delegate?.get()?.didWrite(null, this@NWTCPSocket)
            checkWriteStatusAndDisconnectIfNeeded()
        }
    }
    // End KotlinNWConnectionEventDelegate

    private fun consumeReadDataPrefix(newData: ByteArray): ByteArray {
        val prefix = readDataPrefix
        readDataPrefix = null // Consume it
        return if (prefix != null) {
            prefix + newData
        } else {
            newData
        }
    }

    private fun checkWriteStatusAndDisconnectIfNeeded() {
        if (closeAfterWriting && !writePending) {
            connection?.cancel() // This should trigger state update to CANCELLED
        }
    }

    // deinit in Swift used to removeObserver.
    // With KotlinNWConnectionInterface, explicit close/cancel on connection should handle resource cleanup.
    // If NWTCPSocket itself holds resources that need explicit release, implement Closeable.
    // For now, relying on connection.cancel() and scope cancellation.
}
