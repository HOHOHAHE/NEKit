package nekit.Socket.AdapterSocket

import nekit.Messages.ConnectSession
import nekit.Socket.ProxySocket.HTTPProxySocket
import nekit.RawSocket.protocol.RawSocketFactory
import nekit.RawSocket.core.TLSSocket
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

        val rawSocket = RawSocketFactory.getRawTCPSocket(session!!)
        _rawSocket = rawSocket!!
        
        // Mark the session as requiring TLS for the proxy connection itself.
        val proxySession = session.copy(isTLS = true)
        (rawSocket as? nekit.RawSocket.core.TLSSocket)?.isClient = true

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