package com.example.nekit.Socket.AdapterSocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.ProxySocket.SOCKS5ProxySocket
import com.example.nekit.RawSocket.RawSocketFactory
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference

class SOCKS5Adapter(
    val serverHost: String,
    val serverPort: Int
) : AdapterSocket() {

    private val logger = LoggerFactory.getLogger(SOCKS5Adapter::class.java)
    private var socks5ProxySocket: SOCKS5ProxySocket? = null

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session)
        logger.info("Opening SOCKS5 proxy connection for session: ${session.host}:${session.port}")

        val rawSocket = RawSocketFactory.currentFactory.getRawTCPSocket(session!!)
        _rawSocket = rawSocket

        socks5ProxySocket = SOCKS5ProxySocket(rawSocket, session)
        

        // The SOCKS5ProxySocket will handle the connection to the proxy server
        // and the subsequent SOCKS5 handshake.
        socks5ProxySocket?.openSocket()
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        socks5ProxySocket?.forceDisconnect(becauseOf)
        super.forceDisconnect(becauseOf)
    }
}