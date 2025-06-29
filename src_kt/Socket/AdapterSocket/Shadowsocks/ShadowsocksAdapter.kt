package com.example.nekit.Socket.AdapterSocket.Shadowsocks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import java.io.IOException // For connection exceptions

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.RawSocket.RawSocketFactory
import com.example.nekit.Socket.SocketStatus
import com.example.nekit.Socket.AdapterSocket.AdapterSocket

/**
 * Adapter for connecting to a remote host through a Shadowsocks proxy.
 * It handles the Shadowsocks protocol, including obfuscation and encryption.
 */
open class ShadowsocksAdapter(
    val serverHost: String,
    val serverPort: Int,
    private val protocolObfuscater: ProtocolObfuscater,
    private val cryptor: CryptoStreamProcessor,
    private val streamObfuscator: StreamObfuscater,
    initialRawSocket: RawTCPSocketProtocol? = RawSocketFactory.getRawSocket()
) : AdapterSocket(initialRawSocket) {

    private val ssAdapterLogger = LoggerFactory.getLogger(ShadowsocksAdapter::class.java)

    // Managed CoroutineScope for the ShadowsocksAdapter lifecycle
    private val ssAdapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        ssAdapterLogger.info("Created for {}:{}. Obfuscators: {}, {}. Cryptor: {}", serverHost, serverPort, protocolObfuscater, streamObfuscator, cryptor)
    }

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session)
        val currentRawSocket = rawSocket ?: run {
            ssAdapterLogger.error("Raw socket is null in openSocketWith for session: {}. Cannot connect.", session)
            _status = SocketStatus.CLOSED
            this.delegate?.get()?.didDisconnect(this)
            return
        }
        if (isCancelled) {
            ssAdapterLogger.info("openSocketWith called on a cancelled socket for session: {}", session)
            return
        }

        _status = SocketStatus.CONNECTING
        ssAdapterLogger.info("Connecting to Shadowsocks server {}:{} for session {}", serverHost, serverPort, session)
        ssAdapterScope.launch {
            try {
                currentRawSocket.connectTo(serverHost, serverPort)
                // Upon connection, didConnect (from RawTCPSocketDelegate) will be called.
                // The Shadowsocks handshake/initial data exchange logic will proceed there.
            } catch (e: Exception) {
                ssAdapterLogger.error("Failed to connect to Shadowsocks proxy {}:{}: {}", serverHost, serverPort, e.message, e)
                handleConnectionFailure(e)
            }
        }
    }

    // RawTCPSocketDelegate methods (didConnect, didRead, didWrite, didDisconnect, didErrorOccur)
    // will be handled by AdapterSocket's base class, which then calls our overridden methods.

    override fun didConnect(socket: RawTCPSocketProtocol) {
        // Raw socket connected. Now, initiate Shadowsocks handshake/data exchange.
        ssAdapterLogger.info("Raw socket connected to Shadowsocks proxy. Preparing to send initial data.")
        // The Shadowsocks protocol typically involves sending the target address and port
        // encrypted immediately after the TCP connection is established.
        // The target address is from `session`.
        val targetAddress = session.host
        val targetPort = session.port.toUShort()

        // Construct the SOCKS5-like address type (ATYP) + address + port (ADDR + PORT)
        // This is not a full SOCKS5 handshake, but the address/port format is similar.
        val addressBytes: ByteArray
        val atypByte: Byte

        val parsedIp = Utils.IPAddress.parse(targetAddress)
        if (parsedIp != null) {
            if (parsedIp.isIPv4) {
                atypByte = 0x01 // IPv4
                addressBytes = parsedIp.addressBytes
            } else { // IPv6
                atypByte = 0x04 // IPv6
                addressBytes = parsedIp.addressBytes
            }
        } else { // Domain name
            atypByte = 0x03 // Domain name
            val domainBytes = targetAddress.toByteArray(Charsets.UTF_8)
            if (domainBytes.size > 255) {
                handleConnectionFailure(IOException("Domain name too long for Shadowsocks: ${domainBytes.size} bytes"))
                return
            }
            addressBytes = ByteArray(1 + domainBytes.size)
            addressBytes[0] = domainBytes.size.toByte()
            System.arraycopy(domainBytes, 0, addressBytes, 1, domainBytes.size)
        }

        val portBytes = ByteArray(2)
        ByteBuffer.wrap(portBytes).order(ByteOrder.BIG_ENDIAN).putShort(targetPort.toShort())

        // Combine ATYP + ADDR + PORT
        val initialDataPayload = ByteArray(1 + addressBytes.size + portBytes.size)
        val buffer = ByteBuffer.wrap(initialDataPayload)
        buffer.put(atypByte)
        buffer.put(addressBytes)
        buffer.put(portBytes)

        // Apply protocol obfuscation, then encryption, then stream obfuscation
        val pObfsData = protocolObfuscater.PObfs(initialDataPayload)
        val encryptedData = cryptor.encrypt(pObfsData)
        val sObfsData = streamObfuscator.SObfs(encryptedData)

        ssAdapterScope.launch {
            try {
                this@ShadowsocksAdapter.write(sObfsData)
                // After writing, we expect the server to be ready for forwarding.
                // In Shadowsocks, after sending the target address, the tunnel is typically established.
                ssAdapterLogger.info("Shadowsocks initial data sent. Tunnel established for session {}.", session)
                _status = SocketStatus.ESTABLISHED
                super.didConnect(socket) // Signal base AdapterSocket that raw socket connected
                delegate?.get()?.didConnect(this@ShadowsocksAdapter)
                delegate?.get()?.didBecomeReadyToForward(this@ShadowsocksAdapter)
            } catch (e: Exception) {
                ssAdapterLogger.error("Failed to send initial Shadowsocks data or establish tunnel: {}", e.message, e)
                handleConnectionFailure(e)
            }
        }
    }

    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        // Data received from Shadowsocks server. Process: stream de-obfuscation -> decryption -> protocol de-obfuscation
        if (internalState != State.FORWARDING) {
             ssAdapterLogger.warn("Received data in non-forwarding state: {}. Data size: {}. Ignoring.", internalState, data.size)
             return // Or handle as error
        }
        val iObfsData = streamObfuscator.IObfs(data)
        val decryptedData = cryptor.decrypt(iObfsData)
        val pIObfsData = protocolObfuscater.IObfs(decryptedData)

        super.didRead(pIObfsData, from) // Pass processed data to base AdapterSocket
    }

    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        // This is for data written by us (client to server).
        // If it's for initial handshake, internal state will be handled.
        // If it's for forwarding data, just pass to delegate.
        super.didWrite(data, by) // Signals observer
        if (internalState == State.FORWARDING) {
            delegate?.get()?.didWrite(data, this) // Forward to actual delegate
        }
    }

    private fun handleConnectionFailure(error: Throwable) {
        ssAdapterLogger.error("Shadowsocks connection or handshake failure: {}", error.message, error)
        ssAdapterScope.cancel("ShadowsocksAdapter connection failure") // Cancel scope on failure
        forceDisconnect(becauseOf = error) // Use AdapterSocket's forceDisconnect
    }

    override fun disconnect(becauseOf: Throwable?) {
        ssAdapterLogger.info("disconnect called for session {}. Error: {}", session, becauseOf?.message)
        ssAdapterScope.cancel("ShadowsocksAdapter disconnected") // Cancel all coroutines in this scope
        super.disconnect(becauseOf)
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        ssAdapterLogger.info("forceDisconnect called for session {}. Error: {}", session, becauseOf?.message)
        ssAdapterScope.cancel("ShadowsocksAdapter force-disconnected") // Cancel all coroutines in this scope
        super.forceDisconnect(becauseOf)
    }

    override fun toString(): String {
        val sessionStr = if (::_session.isInitialized) session.toString() else "uninitialized"
        return "<${this::class.simpleName ?: "ShadowsocksAdapter"} proxy:$serverHost:$serverPort session:$sessionStr status:$status>"
    }
}
