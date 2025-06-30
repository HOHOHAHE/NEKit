package com.example.nekit.Socket.ProxySocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.SocketStatus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException

class SOCKS5ProxySocket(
    rawSocket: RawTCPSocketProtocol
) : ProxySocket(rawSocket) {

    override var session: ConnectSession? = null
        public set

    private val logger = LoggerFactory.getLogger(SOCKS5ProxySocket::class.java)
    private var state = State.INITIAL

    private enum class State {
        INITIAL,
        GREETING,
        READING_METHODS,
        CONNECTING,
        READING_IPV4,
        READING_IPV6,
        READING_DOMAIN_LENGTH,
        READING_DOMAIN,
        READING_PORT,
        SENDING_RESPONSE,
        FORWARDING
    }

    override fun openSocket() {
        super.openSocket()
        state = State.GREETING
        // Start by reading exactly 2 bytes for version and number of methods
        GlobalScope.launch { rawSocket.readDataTo(2) }
    }

    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        when (state) {
            State.GREETING -> {
                handleGreeting(data)
            }
            State.READING_METHODS -> {
                handleMethods(data)
            }
            State.CONNECTING -> {
                handleConnect(data)
            }
            State.READING_IPV4 -> {
                handleIPv4Address(data)
            }
            State.READING_IPV6 -> {
                handleIPv6Address(data)
            }
            State.READING_DOMAIN_LENGTH -> {
                handleDomainLength(data)
            }
            State.READING_DOMAIN -> {
                handleDomain(data)
            }
            State.READING_PORT -> {
                handlePort(data)
            }
            State.FORWARDING -> {
                delegate?.get()?.didRead(data, this)
            }
            else -> return
        }
    }

    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        super.didWrite(data, by)
        when (state) {
            State.SENDING_RESPONSE -> {
                state = State.FORWARDING
                _status = SocketStatus.ESTABLISHED
                delegate?.get()?.didBecomeReadyToForward(this)
            }
            State.FORWARDING -> {
                delegate?.get()?.didWrite(data, this)
            }
            else -> return
        }
    }

    private fun handleGreeting(data: ByteArray) {
        // Should receive exactly 2 bytes: version and number of methods
        if (data.size < 2) {
            forceDisconnect(IOException("Invalid SOCKS5 greeting: insufficient data"))
            return
        }
        
        val version = data[0]
        val nMethods = data[1].toInt() and 0xFF
        
        if (version != 0x05.toByte()) {
            forceDisconnect(IOException("Invalid SOCKS5 version: $version"))
            return
        }
        
        if (nMethods <= 0) {
            forceDisconnect(IOException("Invalid number of methods: $nMethods"))
            return
        }
        
        // Read the methods
        GlobalScope.launch { rawSocket.readDataTo(nMethods) }
        state = State.READING_METHODS
    }
    
    private fun handleMethods(data: ByteArray) {
        // TODO: check for 0x00 in read data
        val response = byteArrayOf(0x05, 0x00) // NO AUTH
        GlobalScope.launch { 
            write(response)
            // After sending response, read connect header (4 bytes)
            rawSocket.readDataTo(4)
        }
        state = State.CONNECTING
    }

    private fun handleConnect(data: ByteArray) {
        // Should receive exactly 4 bytes: VER, CMD, RSV, ATYP
        if (data.size < 4) {
            forceDisconnect(IOException("Invalid SOCKS5 connect header: insufficient data"))
            return
        }
        
        val version = data[0]
        val cmd = data[1]
        // Skip RSV (data[2])
        val atyp = data[3]
        
        if (version != 0x05.toByte() || cmd != 0x01.toByte()) {
            forceDisconnect(IOException("Invalid SOCKS5 connect request: version=$version, cmd=$cmd"))
            return
        }
        
        // Based on address type, read the appropriate amount of data
        when (atyp) {
            0x01.toByte() -> { // IPv4 - read 4 bytes for IP
                state = State.READING_IPV4
                GlobalScope.launch { rawSocket.readDataTo(4) }
            }
            0x03.toByte() -> { // Domain - read 1 byte for length first
                state = State.READING_DOMAIN_LENGTH
                GlobalScope.launch { rawSocket.readDataTo(1) }
            }
            0x04.toByte() -> { // IPv6 - read 16 bytes for IP
                state = State.READING_IPV6
                GlobalScope.launch { rawSocket.readDataTo(16) }
            }
            else -> {
                forceDisconnect(IOException("Unsupported address type in SOCKS5 request: $atyp"))
            }
        }
    }
    
    private var destinationHost: String? = null
    private var targetPort: Int? = null
    
    private fun handleIPv4Address(data: ByteArray) {
        if (data.size < 4) {
            forceDisconnect(IOException("Invalid IPv4 address data"))
            return
        }
        destinationHost = data.joinToString(".") { (it.toInt() and 0xFF).toString() }
        state = State.READING_PORT
        GlobalScope.launch { rawSocket.readDataTo(2) }
    }
    
    private fun handleIPv6Address(data: ByteArray) {
        if (data.size < 16) {
            forceDisconnect(IOException("Invalid IPv6 address data"))
            return
        }
        destinationHost = data.asList().chunked(2).joinToString(":") {
            String.format("%02x%02x", it[0], it[1])
        }
        state = State.READING_PORT
        GlobalScope.launch { rawSocket.readDataTo(2) }
    }
    
    private fun handleDomainLength(data: ByteArray) {
        if (data.isEmpty()) {
            forceDisconnect(IOException("Invalid domain length data"))
            return
        }
        val domainLength = data[0].toInt() and 0xFF
        state = State.READING_DOMAIN
        GlobalScope.launch { rawSocket.readDataTo(domainLength) }
    }
    
    private fun handleDomain(data: ByteArray) {
        destinationHost = String(data)
        state = State.READING_PORT
        GlobalScope.launch { rawSocket.readDataTo(2) }
    }
    
    private fun handlePort(data: ByteArray) {
        if (data.size < 2) {
            forceDisconnect(IOException("Invalid port data"))
            return
        }
        targetPort = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        
        val host = destinationHost
        val port = targetPort
        if (host != null && port != null) {
            val requestSession = ConnectSession(host = host, port = port)
            this.session = requestSession
            // 不要立即进入FORWARDING状态，等待respondTo被调用
            delegate?.get()?.didReceive(requestSession, this)
        } else {
            forceDisconnect(IOException("Missing host or port information"))
        }
    }

    override fun respondTo(adapter: AdapterSocket) {
        super.respondTo(adapter)
        // SOCKS5 success reply: VER | REP | RSV | ATYP | BND.ADDR | BND.PORT
        val response = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        state = State.SENDING_RESPONSE
        GlobalScope.launch { 
            write(response)
        }
    }
}
