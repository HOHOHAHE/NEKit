package nekit.Socket.AdapterSocket.Factory

import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.AdapterSocket
import nekit.Socket.AdapterSocket.SpeedAdapter

class SpeedAdapterFactory(
    val factories: List<AdapterFactory>,
    val testUrl: String
) : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return SpeedAdapter(
            factories = factories,
            testUrl = testUrl
        )
    }
}
