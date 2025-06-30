package com.example.nekit.Socket.AdapterSocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.ProxySocket.HTTPProxySocket
import com.example.nekit.RawSocket.RawSocketFactory
import com.example.nekit.RawSocket.TLSSocket
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference

class SecureHTTPAdapter(
    val serverHost: String,
    val serverPort: Int,
    val auth: ((SecureHTTPAdapter) -> Pair<String, String>)? = null
) : AdapterSocket() {

    private val logger = LoggerFactory.getLogger(SecureHTTPAdapter::class.java)
    private var httpProxySocket: HTTPProxySocket? = null

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session)
        logger.info("Opening Secure HTTP proxy connection for session: ${session.host}:${session.port}")

        val rawSocket = RawSocketFactory.getRawSocket(session!!)
        _rawSocket = rawSocket!!
        
        // Mark the session as requiring TLS for the proxy connection itself.
        val proxySession = session.copy(isTLS = true)
        (rawSocket as? com.example.nekit.RawSocket.TLSSocket)?.isClient = true

        httpProxySocket = HTTPProxySocket(rawSocket, proxySession)
        
        
        // The HTTPProxySocket will handle the secure connection to the proxy server
        // and the subsequent CONNECT request.
        httpProxySocket?.openSocket()
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        httpProxySocket?.forceDisconnect(becauseOf)
        super.forceDisconnect(becauseOf)
    }
}