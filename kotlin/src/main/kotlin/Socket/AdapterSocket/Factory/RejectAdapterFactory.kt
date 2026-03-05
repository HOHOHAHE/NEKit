package nekit.Socket.AdapterSocket.Factory

import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.AdapterSocket
import nekit.Socket.AdapterSocket.RejectAdapter

class RejectAdapterFactory(
    val delay: Int
) : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return RejectAdapter(delay)
    }
}
