package com.example.nekit.IPStack.Native

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Memory
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference // Though not directly used for JNA Callbacks storage here

// Assuming LibTun2Socks.kt interfaces are in this package or imported
// import com.example.nekit.IPStack.Native.LibTun2SocksStackCallbacks
// import com.example.nekit.IPStack.Native.LibTun2SocksSocketCallbacks
// import com.example.nekit.IPStack.Native.LibTun2SocksStackInterface


// --- JNA Callback Interfaces ---

// For LibTun2SocksStackCallbacks
interface WritePacketCallback : Callback {
    // fun invoke(protocol: Int, packet: Pointer?, len: Int): Int // Original plan
    // Assuming packet content is more useful than protocol int alone for a generic write
    fun invoke(packet: Pointer?, len: Int): Int // Simplified: packet data, length
}

interface OnTcpSocketCreatedCallback : Callback {
    // Returns a handle or structure for socket callbacks, or 0/null if not handled.
    // For simplicity, let's assume it doesn't directly return the JNA socket callbacks structure,
    // but signals Kotlin, and Kotlin then registers callbacks for that socketId if needed.
    // Or, if the C API expects it to return a pointer to a struct of socket callbacks:
    // fun invoke(socketId: Int, context: Pointer?): Pointer? // Pointer to a struct of LibTun2SocksSocketCallbacks_JNA
    // For now, let's assume a simpler model where Kotlin side manages this mapping.
    fun invoke(socketId: Int, context: Pointer?) // Context is likely unused if flow starts from tun2socks
}

interface OnUdpSocketCreatedCallback : Callback {
    fun invoke(socketId: Int, context: Pointer?)
}

// For LibTun2SocksSocketCallbacks
// These would be registered per socketId if the C API works that way.
interface OnConnectedCallback : Callback {
    fun invoke(socketId: Int)
}
interface OnDataReceivedCallback : Callback {
    fun invoke(socketId: Int, data: Pointer?, len: Int)
}
interface OnRemoteClosedCallback : Callback {
    fun invoke(socketId: Int)
}
interface OnWritePossibleCallback : Callback {
    fun invoke(socketId: Int)
}
interface OnSocketErrorCallback : Callback { // Renamed from OnError to avoid conflict
    fun invoke(socketId: Int, errorCode: Int)
}

// --- JNA Library Interface for tun2socks ---
interface Tun2SocksLibrary : Library {
    companion object {
        // Placeholder library name. This needs to be the actual name of the compiled tun2socks library.
        private const val LIB_NAME = "tun2socks" // Or "liblwip.so", "libtun2socks.dylib", etc.
        val INSTANCE: Tun2SocksLibrary by lazy {
            try {
                Native.load(LIB_NAME, Tun2SocksLibrary::class.java) as Tun2SocksLibrary
            } catch (e: UnsatisfiedLinkError) {
                LoggerFactory.getLogger(Tun2SocksLibrary::class.java)
                    .error("Failed to load native tun2socks library '{}'. Tun2socks functionality will not work. Error: {}", LIB_NAME, e.message)
                throw e
            }
        }
    }

    // Native function declarations matching hypothetical C API
    // Initialization with stack-level callbacks
    fun init_stack(
        writePacketCb: WritePacketCallback?,
        onTcpSocketCreatedCb: OnTcpSocketCreatedCallback?,
        onUdpSocketCreatedCb: OnUdpSocketCreatedCallback?
        // Add other global callbacks if any (e.g., general stack errors)
    ): Int // Returns 0 on success, error code otherwise

    fun start_stack(): Int // Returns 0 on success
    fun stop_stack()
    fun input_packet_to_stack(packet: Pointer?, len: Int)

    // TCP socket operations
    fun connect_tcp_socket(socketId: Int, host: String?, port: Int): Int // 0 success
    fun write_tcp_data(socketId: Int, data: Pointer?, len: Int): Int // bytes written or error
    fun close_tcp_socket(socketId: Int): Int // 0 success

    // Function to register callbacks for a specific socket_id
    // This is crucial if callbacks are per-socket and not global.
    fun register_socket_callbacks(
        socketId: Int,
        onConnectedCb: OnConnectedCallback?,
        onDataReceivedCb: OnDataReceivedCallback?,
        onRemoteClosedCb: OnRemoteClosedCallback?,
        onWritePossibleCb: OnWritePossibleCallback?,
        onErrorCb: OnSocketErrorCallback?
    ): Int // 0 on success
}


// --- JNA Implementation of LibTun2SocksStackInterface ---
class JnaLibTun2Socks : LibTun2SocksStackInterface {
    private val logger = LoggerFactory.getLogger(JnaLibTun2Socks::class.java)
    private val nativeLib = Tun2SocksLibrary.INSTANCE

    // Store Kotlin callbacks
    private var kotlinStackCallbacks: LibTun2SocksStackCallbacks? = null

    // Store JNA callback instances to prevent GC
    private var jnaWritePacketCallback: WritePacketCallback? = null
    private var jnaOnTcpSocketCreatedCallback: OnTcpSocketCreatedCallback? = null
    private var jnaOnUdpSocketCreatedCallback: OnUdpSocketCreatedCallback? = null

    // Map to store JNA callbacks for individual sockets (socketId -> SocketJnaCallbacks)
    private data class SocketJnaCallbacks(
        val onConnected: OnConnectedCallback? = null,
        val onDataReceived: OnDataReceivedCallback? = null,
        val onRemoteClosed: OnRemoteClosedCallback? = null,
        val onWritePossible: OnWritePossibleCallback? = null,
        val onError: OnSocketErrorCallback? = null
    )
    private val activeSocketJnaCallbacks = mutableMapOf<Int, SocketJnaCallbacks>()


    private fun copyJnaPointerToByteArray(ptr: Pointer?, length: Int): ByteArray {
        if (ptr == null || length <= 0) return ByteArray(0)
        val buffer = ByteArray(length)
        ptr.read(0, buffer, 0, length)
        return buffer
    }

    override fun init(callbacks: LibTun2SocksStackCallbacks) {
        this.kotlinStackCallbacks = callbacks

        this.jnaWritePacketCallback = WritePacketCallback { packetPtr, len ->
            val data = copyJnaPointerToByteArray(packetPtr, len)
            // Assuming protocol is embedded or not needed for this specific writePacket
            this.kotlinStackCallbacks?.writePacket(0 /* protocol placeholder */, data, len) ?: -1
        }

        this.jnaOnTcpSocketCreatedCallback = OnTcpSocketCreatedCallback { socketId, _ ->
            logger.debug("Native tun2socks: TCP socket {} created.", socketId)
            val kotlinSocketCallbacks = this.kotlinStackCallbacks?.onTcpSocketCreated(socketId, null)
            if (kotlinSocketCallbacks != null) {
                registerJnaSocketCallbacks(socketId, kotlinSocketCallbacks)
            } else {
                logger.warn("No Kotlin socket callbacks provided for TCP socketId {}", socketId)
                // Optionally, tell native code to immediately close/reject this socket if no handler.
            }
        }

        this.jnaOnUdpSocketCreatedCallback = OnUdpSocketCreatedCallback { socketId, _ ->
            logger.debug("Native tun2socks: UDP socket {} created.", socketId)
            val kotlinSocketCallbacks = this.kotlinStackCallbacks?.onUdpSocketCreated(socketId, null)
            if (kotlinSocketCallbacks != null) {
                registerJnaSocketCallbacks(socketId, kotlinSocketCallbacks)
            } else {
                logger.warn("No Kotlin socket callbacks provided for UDP socketId {}", socketId)
            }
        }

        val result = nativeLib.init_stack(
            this.jnaWritePacketCallback,
            this.jnaOnTcpSocketCreatedCallback,
            this.jnaOnUdpSocketCreatedCallback
        )
        if (result == 0) {
            logger.info("Native tun2socks stack initialized successfully.")
        } else {
            logger.error("Failed to initialize native tun2socks stack. Error code: {}", result)
            throw RuntimeException("Failed to initialize native tun2socks stack. Code: $result")
        }
    }

    private fun registerJnaSocketCallbacks(socketId: Int, kotlinCallbacks: LibTun2SocksSocketCallbacks) {
        val onConnectedJna = OnConnectedCallback { sid -> if (sid == socketId) kotlinCallbacks.onConnected(sid) }
        val onDataReceivedJna = OnDataReceivedCallback { sid, dataPtr, len ->
            if (sid == socketId) kotlinCallbacks.onDataReceived(sid, copyJnaPointerToByteArray(dataPtr, len), len)
        }
        val onRemoteClosedJna = OnRemoteClosedCallback { sid -> if (sid == socketId) kotlinCallbacks.onRemoteClosed(sid) }
        val onWritePossibleJna = OnWritePossibleCallback { sid -> if (sid == socketId) kotlinCallbacks.onWritePossible(sid) }
        val onErrorJna = OnSocketErrorCallback { sid, errCode -> if (sid == socketId) kotlinCallbacks.onError(sid, errCode) }

        val jnaCallbacks = SocketJnaCallbacks(
            onConnected = onConnectedJna,
            onDataReceived = onDataReceivedJna,
            onRemoteClosed = onRemoteClosedJna,
            onWritePossible = onWritePossibleJna,
            onError = onErrorJna
        )
        activeSocketJnaCallbacks[socketId] = jnaCallbacks // Store to prevent GC

        val result = nativeLib.register_socket_callbacks(
            socketId,
            onConnectedJna,
            onDataReceivedJna,
            onRemoteClosedJna,
            onWritePossibleJna,
            onErrorJna
        )
        if (result == 0) {
            logger.info("Socket callbacks registered for socketId {}.", socketId)
        } else {
            logger.error("Failed to register socket callbacks for socketId {}. Error code: {}", socketId, result)
            activeSocketJnaCallbacks.remove(socketId) // Clean up if registration failed
        }
    }


    override fun start(): Boolean {
        logger.info("Starting native tun2socks stack...")
        val result = nativeLib.start_stack()
        if (result == 0) {
            logger.info("Native tun2socks stack started successfully.")
            return true
        } else {
            logger.error("Failed to start native tun2socks stack. Error code: {}", result)
            return false
        }
    }

    override fun stop() {
        logger.info("Stopping native tun2socks stack...")
        nativeLib.stop_stack()
        logger.info("Native tun2socks stack stopped.")
        // Clear stored callbacks
        kotlinStackCallbacks = null
        jnaWritePacketCallback = null
        jnaOnTcpSocketCreatedCallback = null
        jnaOnUdpSocketCreatedCallback = null
        activeSocketJnaCallbacks.clear()
    }

    override fun inputPacket(packet: ByteArray, len: Int) {
        if (len <= 0 || packet.isEmpty()) {
            logger.warn("inputPacket called with empty or invalid length data.")
            return
        }
        logger.trace("Sending {} bytes to native tun2socks stack (input_packet_to_stack)", len)
        val nativePacket = Memory(len.toLong())
        nativePacket.write(0, packet, 0, len)
        nativeLib.input_packet_to_stack(nativePacket, len)
    }

    override fun connectTcp(socketId: Int, host: String, port: Int): Boolean {
        logger.info("Requesting native tun2socks to connect TCP socketId {} to {}:{}", socketId, host, port)
        val result = nativeLib.connect_tcp_socket(socketId, host, port)
        if (result != 0) {
            logger.error("Native connect_tcp_socket for socketId {} failed. Error code: {}", socketId, result)
            return false
        }
        return true
    }

    override fun writeData(socketId: Int, data: ByteArray, len: Int): Int {
        if (len <= 0 || data.isEmpty()) {
            logger.warn("writeData called with empty or invalid length data for socketId {}.", socketId)
            return 0
        }
        logger.trace("Writing {} bytes to native tun2socks for socketId {}", len, socketId)
        val nativeData = Memory(len.toLong())
        nativeData.write(0, data, 0, len)
        val bytesWritten = nativeLib.write_tcp_data(socketId, nativeData, len)
        if (bytesWritten < 0) {
            logger.error("Native write_tcp_data for socketId {} failed. Error code: {}", socketId, bytesWritten)
        } else if (bytesWritten < len) {
            logger.warn("Partial write for socketId {}: wrote {} of {} bytes.", socketId, bytesWritten, len)
        }
        return bytesWritten
    }

    override fun closeTcp(socketId: Int) {
        logger.info("Requesting native tun2socks to close TCP socketId {}", socketId)
        val result = nativeLib.close_tcp_socket(socketId)
        if (result != 0) {
            logger.error("Native close_tcp_socket for socketId {} failed. Error code: {}", socketId, result)
        }
        activeSocketJnaCallbacks.remove(socketId) // Clean up stored JNA callbacks for this socket
    }
}
