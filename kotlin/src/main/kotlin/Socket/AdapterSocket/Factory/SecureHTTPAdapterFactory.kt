package nekit.Socket.AdapterSocket.Factory

import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.AdapterSocket
import nekit.Socket.AdapterSocket.SecureHTTPAdapter
import nekit.Utils.HTTPAuthentication

class SecureHTTPAdapterFactory(
    val serverHost: String,
    val serverPort: Int,
    val auth: HTTPAuthentication?
) : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return SecureHTTPAdapter(
            serverHost = serverHost,
            serverPort = serverPort,
            auth = { _ -> auth?.let { Pair(it.username, it.password) } ?: Pair("", "") }
        )
    }
}