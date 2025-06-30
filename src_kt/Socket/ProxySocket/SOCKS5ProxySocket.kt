package com.example.nekit.Socket.ProxySocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.SocketStatus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer

class SOCKS5ProxySocket(
    rawSocket: RawTCPSocketProtocol,
    session: ConnectSession
) : ProxySocket(rawSocket) {

    override var session: ConnectSession? = session

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
        GlobalScope.launch { readData() }
    }

    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        val buffer = ByteBuffer.wrap(data)
        while (buffer.hasRemaining()) {
            when (state) {
                State.GREETING -> {
                    if (handleGreeting(buffer)) {
                        state = State.CONNECTING
                    } else {
                        return // Not enough data, wait for more
                    }
                }
                State.CONNECTING -> {
                    if (handleConnect(buffer)) {
                        state = State.FORWARDING
                    } else {
                        return // Not enough data, wait for more
                    }
                }
                State.FORWARDING -> {
                    val remainingData = ByteArray(buffer.remaining())
                    buffer.get(remainingData)
                    delegate?.get()?.didRead(remainingData, this)
                    return // All remaining data is for forwarding
                }
                else -> return
            }
        }
    }

    private fun handleGreeting(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() < 2) return false // VER, NMETHODS
        val ver = buffer.get()
        val nMethods = buffer.get()
        if (ver != 0x05.toByte() || buffer.remaining() < nMethods) {
            forceDisconnect(IOException("Invalid SOCKS5 greeting."))
            return false
        }
        buffer.position(buffer.position() + nMethods) // Skip methods

        val response = byteArrayOf(0x05, 0x00) // NO AUTH
        GlobalScope.launch { write(response) }
        return true
    }

    private fun handleConnect(buffer: ByteBuffer): Boolean {
        val initialPosition = buffer.position()
        if (buffer.remaining() < 4) return false // VER, CMD, RSV, ATYP

        val ver = buffer.get()
        val cmd = buffer.get()
        buffer.get() // Skip RSV
        val atyp = buffer.get()

        if (ver != 0x05.toByte() || cmd != 0x01.toByte()) {
            forceDisconnect(IOException("Invalid SOCKS5 connect request."))
            return false
        }

        val host: String
        when (atyp) {
            0x01.toByte() -> { // IPv4
                if (buffer.remaining() < 4) {
                    buffer.position(initialPosition)
                    return false
                }
                val ipBytes = ByteArray(4)
                buffer.get(ipBytes)
                host = ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03.toByte() -> { // Domain
                if (buffer.remaining() < 1) {
                    buffer.position(initialPosition)
                    return false
                }
                val len = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < len) {
                    buffer.position(initialPosition)
                    return false
                }
                val domainBytes = ByteArray(len)
                buffer.get(domainBytes)
                host = String(domainBytes)
            }
            0x04.toByte() -> { // IPv6
                if (buffer.remaining() < 16) {
                    buffer.position(initialPosition)
                    return false
                }
                val ipBytes = ByteArray(16)
                buffer.get(ipBytes)
                host = ipBytes.asList().chunked(2).joinToString(":") {
                    String.format("%02x%02x", it[0], it[1])
                }
            }
            else -> {
                forceDisconnect(IOException("Unsupported address type in SOCKS5 request: $atyp"))
                return false
            }
        }

        if (buffer.remaining() < 2) { // Port
            buffer.position(initialPosition)
            return false
        }
        val port = buffer.short.toInt() and 0xFFFF

        val requestSession = ConnectSession(host = host, port = port)
        this.session = requestSession
        delegate?.get()?.didReceive(requestSession, this)
        return true
    }

    override fun respondTo(adapter: AdapterSocket) {
        super.respondTo(adapter)
        // SOCKS5 success reply: VER | REP | RSV | ATYP | BND.ADDR | BND.PORT
        val response = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        GlobalScope.launch { 
            write(response)
            _status = SocketStatus.ESTABLISHED
        }
        state = State.FORWARDING
        delegate?.get()?.didBecomeReadyToForward(this)
    }
}
