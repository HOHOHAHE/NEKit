package nekit.Socket.AdapterSocket.Factory

import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.AdapterSocket
import nekit.Socket.AdapterSocket.SOCKS5Adapter

class SOCKS5AdapterFactory(
    val serverHost: String,
    val serverPort: Int
) : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return SOCKS5Adapter(
            serverHost = serverHost,
            serverPort = serverPort
        )
    }
}
