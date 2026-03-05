package nekit.Event
// TODO: Replace CocoaLumberjackSwift with a Kotlin logging framework (e.g., SLF4J + Logback/Log4j2)
// For now, using println for basic logging.

// --- Placeholders for classes/enums from other files/modules ---
// These should be replaced with actual imports once those files are translated.

import org.slf4j.LoggerFactory
import nekit.Tunnel.Tunnel // Corrected import
import nekit.ProxyServer.ProxyServer // Corrected import
import nekit.Socket.ProxySocket.ProxySocket // Corrected import
import nekit.Socket.AdapterSocket.AdapterSocket // Corrected import
import nekit.Rule.RuleManager // Corrected import
import nekit.Event.Observer // Corrected import
import nekit.Event.ObserverFactory // Corrected import
import nekit.Event.Event.TunnelEvent // Corrected import
import nekit.Event.Event.ProxySocketEvent // Corrected import
import nekit.Event.Event.AdapterSocketEvent // Corrected import
import nekit.Event.Event.ProxyServerEvent // Corrected import
import nekit.Event.Event.RuleMatchEvent // Corrected import

// --- End Placeholders ---


object DebugObserverFactory {
    // public override init() {} // Kotlin provides default constructor if no primary is defined

    fun getObserverForTunnel(tunnel: Tunnel): Observer<TunnelEvent>? {
        return DebugTunnelObserver()
    }

    fun getObserverForProxyServer(server: ProxyServer): Observer<ProxyServerEvent>? {
        return DebugProxyServerObserver()
    }

    fun getObserverForProxySocket(socket: ProxySocket): Observer<ProxySocketEvent>? {
        return DebugProxySocketObserver()
    }

    fun getObserverForAdapterSocket(socket: AdapterSocket): Observer<AdapterSocketEvent>? {
        return DebugAdapterSocketObserver()
    }

    fun getObserverForRuleManager(manager: RuleManager): Observer<RuleMatchEvent> = DebugRuleManagerObserver()
}

open class DebugTunnelObserver : Observer<TunnelEvent> {
    private val logger = LoggerFactory.getLogger(DebugTunnelObserver::class.java)
    override fun signal(event: TunnelEvent) {
        val message = event.toString() // logger will handle formatting
        when (event) {
            is TunnelEvent.ReceivedRequest,
            is TunnelEvent.TunnelClosed ->
                logger.info(message)
            is TunnelEvent.Opened,
            is TunnelEvent.ConnectedToRemote,
            is TunnelEvent.UpdatingAdapterSocket ->
                logger.debug(message) // VERBOSE -> debug
            else -> // Catch-all for other TunnelEvent types
                logger.trace(message) // DEBUG -> trace
        }
    }
}

open class DebugProxySocketObserver : Observer<ProxySocketEvent> {
    private val logger = LoggerFactory.getLogger(DebugProxySocketObserver::class.java)
    override fun signal(event: ProxySocketEvent) {
        val message = event.toString()
        when (event) {
            is ProxySocketEvent.ErrorOccurred ->
                logger.error(message)
            is ProxySocketEvent.Disconnected,
            is ProxySocketEvent.ReceivedRequest ->
                logger.info(message)
            is ProxySocketEvent.SocketOpened,
            is ProxySocketEvent.AskedToResponseTo,
            is ProxySocketEvent.ReadyForForward ->
                logger.debug(message) // VERBOSE -> debug
            else -> // Catch-all for other ProxySocketEvent types
                logger.trace(message) // DEBUG -> trace
        }
    }
}

open class DebugAdapterSocketObserver : Observer<AdapterSocketEvent> {
    private val logger = LoggerFactory.getLogger(DebugAdapterSocketObserver::class.java)
    override fun signal(event: AdapterSocketEvent) {
        val message = event.toString()
        when (event) {
            is AdapterSocketEvent.ErrorOccurred ->
                logger.error(message)
            is AdapterSocketEvent.Disconnected,
            is AdapterSocketEvent.Connected ->
                logger.info(message)
            is AdapterSocketEvent.SocketOpened,
            is AdapterSocketEvent.ReadyForForward ->
                logger.debug(message) // VERBOSE -> debug
            else -> // Catch-all for other AdapterSocketEvent types
                logger.trace(message) // DEBUG -> trace
        }
    }
}

open class DebugProxyServerObserver : Observer<ProxyServerEvent> {
    private val logger = LoggerFactory.getLogger(DebugProxyServerObserver::class.java)
    override fun signal(event: ProxyServerEvent) {
        val message = event.toString()
        when (event) {
            is ProxyServerEvent.Started,
            is ProxyServerEvent.Stopped ->
                logger.info(message)
            is ProxyServerEvent.NewSocketAccepted,
            is ProxyServerEvent.TunnelClosed ->
                logger.debug(message) // VERBOSE -> debug
            else -> // Ensure 'when' is exhaustive
                logger.trace(message)
        }
    }
}

open class DebugRuleManagerObserver : Observer<RuleMatchEvent> {
    private val logger = LoggerFactory.getLogger(DebugRuleManagerObserver::class.java)
    override fun signal(event: RuleMatchEvent) {
        val message = event.toString()
        when (event) {
            is RuleMatchEvent.RuleDidNotMatch, is RuleMatchEvent.DnsRuleMatched ->
                logger.debug(message) // VERBOSE -> debug
            is RuleMatchEvent.RuleMatched ->
                logger.info(message)
            else -> // Ensure 'when' is exhaustive
                logger.trace(message)
        }
    }
}
