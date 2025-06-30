package com.example.nekit.Socket.ProxySocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer

class SOCKS5ProxySocket(
    rawSocket: RawTCPSocketProtocol,
    private val session: ConnectSession
) : ProxySocket(rawSocket) {

    private val logger = LoggerFactory.getLogger(SOCKS5ProxySocket::class.java)
    private var state = State.INITIAL

    private enum class State {
        INITIAL,
        GREETING,
        CONNECTING,
        FORWARDING
    }

    override fun openSocket() {
        super.openSocket()
        state = State.GREETING
        readData()
    }

    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        when (state) {
            State.GREETING -> handleGreeting(data)
            State.CONNECTING -> handleConnect(data)
            State.FORWARDING -> delegate?.get()?.didRead(data, this)
            else -> {}
        }
    }

    private fun handleGreeting(data: ByteArray) {
        // SOCKS5 greeting: VER | NMETHODS | METHODS
        if (data.size < 2 || data[0] != 0x05.toByte()) {
            forceDisconnect(IOException("Invalid SOCKS5 greeting."))
            return
        }
        // For simplicity, we only support NO AUTHENTICATION REQUIRED (0x00)
        val response = byteArrayOf(0x05, 0x00)
        write(response)
        state = State.CONNECTING
        readData()
    }

    private fun handleConnect(data: ByteArray) {
        // SOCKS5 connect request: VER | CMD | RSV | ATYP | DST.ADDR | DST.PORT
        if (data.size < 4 || data[0] != 0x05.toByte() || data[1] != 0x01.toByte()) {
            forceDisconnect(IOException("Invalid SOCKS5 connect request."))
            return
        }
        
        val requestSession = parseConnectRequest(data)
        this.session = requestSession
        delegate?.get()?.didReceive(requestSession, this)
    }

    private fun parseConnectRequest(data: ByteArray): ConnectSession {
        val buffer = ByteBuffer.wrap(data)
        buffer.position(4) // Skip VER, CMD, RSV
        val atyp = buffer.get()
        val host: String
        val port: Short

        when (atyp) {
            0x01.toByte() -> { // IPv4
                val ipBytes = ByteArray(4)
                buffer.get(ipBytes)
                host = ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03.toByte() -> { // Domain name
                val len = buffer.get().toInt() and 0xFF
                val domainBytes = ByteArray(len)
                buffer.get(domainBytes)
                host = String(domainBytes)
            }
            0x04.toByte() -> { // IPv6
                val ipBytes = ByteArray(16)
                buffer.get(ipBytes)
                // Simplified IPv6 parsing
                host = ipBytes.asList().chunked(2).joinToString(":") {
                    String.format("%02x%02x", it[0], it[1])
                }
            }
            else -> throw IOException("Unsupported address type in SOCKS5 request.")
        }
        port = buffer.short

        return ConnectSession(host = host, port = port)
    }

    override fun respondTo(adapter: AdapterSocket) {
        super.respondTo(adapter)
        // SOCKS5 success reply: VER | REP | RSV | ATYP | BND.ADDR | BND.PORT
        val response = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        write(response)
        state = State.FORWARDING
        delegate?.get()?.didBecomeReadyToForward(this)
    }
}
