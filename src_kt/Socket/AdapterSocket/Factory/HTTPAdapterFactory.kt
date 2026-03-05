package nekit.Socket.AdapterSocket.Factory

import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.AdapterSocket
import nekit.Socket.AdapterSocket.HTTPAdapter
import nekit.Utils.HTTPAuthentication

class HTTPAdapterFactory(
    val serverHost: String,
    val serverPort: Int,
    val auth: HTTPAuthentication?
) : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return HTTPAdapter(
            serverHost = serverHost,
            serverPort = serverPort,
            auth = { _ -> auth?.let { Pair(it.username, it.password) } ?: Pair("", "") }
        )
    }
}
