import org.slf4j.LoggerFactory

import Messages.ConnectSession
import RawSocket.RawSocketFactory
import Socket.AdapterSocket.AdapterSocket
import Socket.AdapterSocket.Shadowsocks.ProtocolObfuscater
import Socket.AdapterSocket.Shadowsocks.CryptoStreamProcessor
import Socket.AdapterSocket.Shadowsocks.StreamObfuscater
import Socket.AdapterSocket.Shadowsocks.ShadowsocksAdapterNested // Import the nested object
import Socket.AdapterSocket.Shadowsocks.ShadowsocksAdapter // Import the actual adapter
import Crypto.CryptoAlgorithm // Assuming CryptoAlgorithm is in Crypto package


/**
 * Factory for creating [ShadowsocksAdapter] instances.
 * Extends [ServerAdapterFactory] and holds factories for Shadowsocks-specific components.
 */
open class ShadowsocksAdapterFactory(
    serverHost: String,
    serverPort: Int,
    private val protocolObfuscaterFactory: ShadowsocksAdapterNested.ProtocolObfuscaterFactory,
    private val cryptorFactory: ShadowsocksAdapterNested.CryptoStreamProcessorFactory,
    private val streamObfuscaterFactory: ShadowsocksAdapterNested.StreamObfuscaterFactory
) : ServerAdapterFactory(serverHost, serverPort) {

    /**
     * Creates and returns a [ShadowsocksAdapter].
     * The adapter is configured with components built by the stored sub-factories
     * (protocol obfuscater, cryptor, stream obfuscater) and a new raw socket.
     *
     * @param session The connect session for which the adapter is being created.
     * @return A new [ShadowsocksAdapter] instance.
     */
    override fun getAdapterFor(session: ConnectSession): AdapterSocket {
        val protocolObfuscater = protocolObfuscaterFactory.build()
        val cryptor = cryptorFactory.build()
        val streamObfuscator = streamObfuscaterFactory.build(forSession = session) // Pass session if needed by factory

        val rawSocket = RawSocketFactory.getRawSocket()

        return ShadowsocksAdapter(
            serverHost = this.serverHost,
            serverPort = this.serverPort,
            protocolObfuscater = protocolObfuscater,
            cryptor = cryptor,
            streamObfuscator = streamObfuscator,
            initialRawSocket = rawSocket
        )
    }
}
