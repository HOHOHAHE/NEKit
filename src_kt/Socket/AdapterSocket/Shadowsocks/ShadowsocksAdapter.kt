import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.ref.WeakReference

// Assuming AdapterSocket.kt, RawTCPSocketProtocol.kt, ConnectSession.kt,
// ProtocolObfuscater.kt (with ShadowsocksProtocolObfuscaterBase),
// CryptoStreamProcessor.kt (with ShadowsocksCryptoProcessor),
// StreamObfuscater.kt (with ShadowsocksStreamObfuscaterBase) are available.
// Also, SocketStatus.kt, AdapterSocketEvent.kt, EventSource.kt, ObserverFactory.kt.

// --- Interfaces for component interaction (ensure consistency with other files) ---
// interface ShadowsocksAdapterProtocolFeedback { // Implemented by ShadowsocksAdapter
//     val port: Port
//     fun becomeReadyToForward()
//     fun output(data: ByteArray) // Data to send to raw socket
//     val rawSocket: RawTCPSocketProtocol?
// }
// interface ShadowsocksAdapterStreamFeedback { // Implemented by ShadowsocksAdapter
//     fun input(data: ByteArray) // Data to pass up to client/tunnel
// }
// interface CryptoProcessorStreamOutput { // Implemented by CryptoStreamProcessor
//     val key: ByteArray?
//     val writeIV: ByteArray?
//     fun output(data: ByteArray) // Data to pass to protocol obfuscater (then to network)
// }
// --- End Interfaces ---

/**
 * Adapter for connecting to a remote host through a Shadowsocks proxy.
 * It orchestrates protocol obfuscation, encryption/decryption, and stream obfuscation.
 */
class ShadowsocksAdapter(
    val serverHostAddress: String, // Renamed from host to avoid conflict with ConnectSession.host
    val serverProxyPort: Int,    // Renamed from port
    private val protocolObfuscater: ShadowsocksProtocolObfuscaterBase,
    private val cryptor: ShadowsocksCryptoProcessor,
    private val streamObfuscator: ShadowsocksStreamObfuscaterBase,
    initialRawSocket: RawTCPSocketProtocol? = RawSocketFactory.getRawSocket()
) : AdapterSocket(initialRawSocket), ShadowsocksAdapterProtocolFeedback, ShadowsocksAdapterStreamFeedback {

    private enum class State {
        IDLE,
        CONNECTING_TO_PROXY,    // Raw socket connecting to Shadowsocks server
        RAW_SOCKET_CONNECTED,   // Raw socket connected, Shadowsocks handshake (via obfuscators) starts
        FORWARDING,             // Shadowsocks handshake complete, data forwarding
        STOPPED
    }
    private var internalState: State = State.IDLE

    // `port` property for ShadowsocksAdapterProtocolFeedback
    // This should be the *target* port from the session, used by some obfuscators for Host header etc.
    // The `serverProxyPort` is the port of the Shadowsocks server itself.
    override val port: Port get() = if (::_session.isInitialized) Port(session.port.toUShort()) else Port(0u)


    init {
        println("INFO: ShadowsocksAdapter created for proxy $serverHostAddress:$serverProxyPort")

        // Setup the processing chain (A <-> B means A.output -> B.input, A.input <- B.output)
        // Local -> StreamObfuscater -> CryptoProcessor -> ProtocolObfuscater -> Network (ShadowsocksAdapter.writeRawData)
        // Local <- StreamObfuscater <- CryptoProcessor <- ProtocolObfuscater <- Network (ShadowsocksAdapter.didReadData)

        // Data from local app (write) goes into streamObfuscator.output()
        // Data to local app (input) comes from streamObfuscator.input() which calls this.input()

        // StreamObfuscater:
        // - input (from Crypto): data to be de-obfuscated (stream level) then passed to this.input() (ShadowsocksAdapter.input -> delegate.didRead)
        // - output (to Crypto): data from local app, to be obfuscated (stream level)
        streamObfuscator.inputStreamProcessorRef = WeakReference(this) // `this` implements ShadowsocksAdapterStreamFeedback
        streamObfuscator.outputStreamProcessorRef = WeakReference(cryptor) // `cryptor` implements CryptoProcessorStreamOutput (conceptually)

        // CryptoStreamProcessor:
        // - input (from ProtocolObfuscater): data to be decrypted, then passed to streamObfuscator.input()
        // - output (to ProtocolObfuscater): data from streamObfuscator (already stream-obfuscated), to be encrypted
        cryptor.inputStreamProcessorRef = WeakReference(streamObfuscator) // `streamObfuscator` implements StreamObfuscaterBase (conceptually for input)
        cryptor.outputStreamProcessorRef = WeakReference(protocolObfuscater) // `protocolObfuscator` implements ProtocolObfuscaterBase (conceptually for output)

        // ProtocolObfuscater:
        // - input (from RawSocket): data to be de-obfuscated (protocol level), then passed to cryptor.input()
        // - output (to RawSocket via this.output()): data from cryptor (already encrypted), to be obfuscated (protocol level)
        protocolObfuscater.inputStreamProcessorRef = WeakReference(cryptor) // `cryptor` implements CryptoStreamProcessor (conceptually for input)
        protocolObfuscater.outputStreamProcessorRef = WeakReference(this) // `this` implements ShadowsocksAdapterProtocolFeedback
    }

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session) // Sets this.session, observer, rawSocket.delegate

        if (isCancelled) {
            println("INFO: ShadowsocksAdapter: openSocketWith called on cancelled socket for $session")
            return
        }
        val currentRawSocket = rawSocket ?: run {
            System.err.println("ERROR: ShadowsocksAdapter: Raw socket is null.")
            _status = SocketStatus.CLOSED
            this.delegate?.get()?.didErrorOccur(IllegalStateException("Raw socket not available"), this)
            this.delegate?.get()?.didDisconnect(this)
            return
        }

        internalState = State.CONNECTING_TO_PROXY
        _status = SocketStatus.CONNECTING // From AdapterSocket perspective
        observer?.signal(AdapterSocketEvent.SocketOpened(this, session))

        println("INFO: ShadowsocksAdapter: Connecting to Shadowsocks server $serverHostAddress:$serverProxyPort for session $session")
        val connectionScope = CoroutineScope(Dispatchers.Default) // TODO: Use managed scope
        connectionScope.launch {
            try {
                currentRawSocket.connectTo(host = serverHostAddress, port = serverProxyPort)
                // Result handled by didConnect (from RawTCPSocketDelegate)
            } catch (e: Exception) {
                System.err.println("ERROR: ShadowsocksAdapter: Failed to connect to $serverHostAddress:$serverProxyPort: ${e.message}")
                handleConnectionFailure(e)
            }
        }
    }

    // Called by RawTCPSocketDelegate when physical connection to Shadowsocks server is established
    override fun didConnect(socket: RawTCPSocketProtocol) {
        // DO NOT call super.didConnect(socket) yet.
        // The connection to the Shadowsocks server is up, but the Shadowsocks protocol handshake
        // (handled by obfuscators) needs to complete before the adapter is truly "connected" for forwarding.
        println("INFO: ShadowsocksAdapter: Raw socket connected to $serverHostAddress:$serverProxyPort. Starting protocol obfuscater.")
        internalState = State.RAW_SOCKET_CONNECTED
        // _status remains SocketStatus.CONNECTING from AdapterSocket's view for now.
        // Let super.didConnect be called by becomeReadyToForward.

        // Start the protocol obfuscation layer (e.g., TLS handshake obfuscator might send data)
        protocolObfuscater.start()
        // If protocolObfuscater.start() does not require writes (like Origin),
        // it might call becomeReadyToForward() synchronously.
    }

    // Data received from raw socket (from remote server)
    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        super.didRead(data, from) // Signals AdapterSocketEvent.ReadData (observer only)
        println("DEBUG: ShadowsocksAdapter: Raw read ${data.size} bytes. Passing to protocolObfuscater.input")
        try {
            protocolObfuscater.input(data) // Decrypts, de-obfuscates, then calls this.input()
        } catch (e: Exception) {
            System.err.println("ERROR: ShadowsocksAdapter: Error processing received data: ${e.message}")
            handleConnectionFailure(e)
        }
    }

    // Data from local application to be sent to remote server
    override fun write(data: ByteArray) { // This is from SocketProtocol
        if (internalState != State.FORWARDING) {
            // TODO: Buffer data if not yet in forwarding state? Or is this an error?
            // Original code seems to pass it to streamObfuscator.output directly.
            println("WARN: ShadowsocksAdapter: write() called while not in FORWARDING state (current: $internalState). Data might be lost or handled by current obfuscation state.")
            // For some obfuscators (like HTTP), initial data might be part of handshake.
        }
        println("DEBUG: ShadowsocksAdapter: Application write ${data.size} bytes. Passing to streamObfuscator.output")
        streamObfuscator.output(data) // Encrypts, obfuscates, then calls this.output() (ShadowsocksAdapterProtocolFeedback)
    }

    // Called by ProtocolObfuscater after it has processed data from CryptoStreamProcessor (encrypted, protocol-obfuscated)
    // This is the final step before sending to raw socket.
    override fun output(data: ByteArray) { // Implements ShadowsocksAdapterProtocolFeedback.output
        println("DEBUG: ShadowsocksAdapter: Processed output ${data.size} bytes. Writing to raw socket.")
        // Call AdapterSocket's super.write, which calls rawSocket.write
        // Need to ensure `super.write` doesn't re-enter this adapter's `write` override.
        // The `AdapterSocket.write` calls `rawSocket?.write(data)`. This is correct.
        val writeScope = CoroutineScope(Dispatchers.Default) // TODO: Use managed scope
        writeScope.launch {
            try {
                super.write(data) // Call AdapterSocket's write method
            } catch (e: Exception) {
                System.err.println("ERROR: ShadowsocksAdapter: Error during raw socket write: ${e.message}")
                handleConnectionFailure(e)
            }
        }
    }

    // Called by StreamObfuscater after it has processed data from CryptoStreamProcessor (decrypted, stream-deobfuscated)
    // This is the final step before passing data to the application via delegate.
    override fun input(data: ByteArray) { // Implements ShadowsocksAdapterStreamFeedback.input
        println("DEBUG: ShadowsocksAdapter: Processed input ${data.size} bytes. Forwarding to delegate.")
        delegate?.get()?.didRead(data, this)
    }


    // Called by RawTCPSocketDelegate after data is physically written to raw socket
    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        super.didWrite(data, by) // Signals AdapterSocketEvent.WroteData

        // Notify protocol obfuscater, which might trigger further actions or state changes.
        protocolObfuscater.didWrite()

        if (internalState == State.FORWARDING) {
            delegate?.get()?.didWrite(data, this) // Notify overall delegate if in forwarding state
        }
    }

    // Called by obfuscater (e.g. Origin or TLS after its handshake) when ready for data forwarding.
    override fun becomeReadyToForward() { // Implements ShadowsocksAdapterProtocolFeedback.becomeReadyToForward
        println("INFO: ShadowsocksAdapter: Obfuscation layer ready. Transitioning to FORWARDING.")
        internalState = State.FORWARDING
        // Now call AdapterSocket's didConnect to signal that the adapter is fully established.
        // Pass this.rawSocket which should be the connected raw socket.
        super.didConnectWith(this.rawSocket!!) // Sets _status = .ESTABLISHED, calls delegate.didConnect(this)

        // Also call didBecomeReadyToForwardWith on our delegate
        // super.didConnectWith already calls delegate.didConnect(this).
        // The specific readyForForward is also useful.
        delegate?.get()?.didBecomeReadyToForwardWith(this)
    }

    private fun handleConnectionFailure(error: Throwable) {
        println("ERROR: ShadowsocksAdapter: Connection failure: ${error.message}")
        internalState = State.STOPPED
        // Use AdapterSocket's forceDisconnect for cleanup and delegate notification
        forceDisconnect(becauseOf = error)
    }

    override fun toString(): String {
        val sessionStr = if (::_session.isInitialized) session.toString() else "uninitialized"
        return "<${this::class.simpleName ?: "ShadowsocksAdapter"} proxy:$serverHostAddress:$serverProxyPort session:$sessionStr status:$status internalState:$internalState>"
    }

    // The EncryptMethod enum was defined in Swift but not used in this class.
    // It's likely used by the factory to choose CryptoAlgorithm for CryptoStreamProcessor.
    // enum class EncryptMethod(val rawValue: String) {
    //     AES128_CFB("AES-128-CFB"), AES192_CFB("AES-192-CFB"), AES256_CFB("AES-256-CFB")
    // }
}
