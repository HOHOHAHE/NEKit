package nekit.Socket.AdapterSocket.Factory

import nekit.Socket.AdapterSocket.DirectAdapter

import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.AdapterSocket

class DirectAdapterFactory : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return DirectAdapter()
    }
}
