package com.example.nekit.Socket.AdapterSocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.ProxySocket.HTTPProxySocket
import com.example.nekit.RawSocket.RawSocketFactory
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference

class HTTPAdapter(
    val serverHost: String,
    val serverPort: Int,
    val auth: ((HTTPAdapter) -> (String, String))? = null
) : AdapterSocket() {

    private val logger = LoggerFactory.getLogger(HTTPAdapter::class.java)
    private var httpProxySocket: HTTPProxySocket? = null

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session)
        logger.info("Opening HTTP proxy connection for session: ${session.host}:${session.port}")

        val rawSocket = RawSocketFactory.getRawSocket()
        _rawSocket = rawSocket

        httpProxySocket = HTTPProxySocket(rawSocket, session)
        httpProxySocket?.delegate = WeakReference(this)
        
        // The HTTPProxySocket will handle the connection to the proxy server
        // and the subsequent CONNECT request.
        httpProxySocket?.openSocket()
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        httpProxySocket?.forceDisconnect(becauseOf)
        super.forceDisconnect(becauseOf)
    }
}