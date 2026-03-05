package nekit.Socket.AdapterSocket.Factory

import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.AdapterSocket

interface AdapterFactory {
    fun getAdapter(session: ConnectSession): AdapterSocket
}
