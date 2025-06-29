package com.example.nekit.Event
// TODO: Replace CocoaLumberjackSwift with a Kotlin logging framework (e.g., SLF4J + Logback/Log4j2)
// For now, using println for basic logging.

// --- Placeholders for classes/enums from other files/modules ---
// These should be replaced with actual imports once those files are translated.

import org.slf4j.LoggerFactory
import com.example.nekit.Tunnel.Tunnel // Corrected import
import com.example.nekit.ProxyServer.ProxyServer // Corrected import
import com.example.nekit.Socket.ProxySocket.ProxySocket // Corrected import
import com.example.nekit.Socket.AdapterSocket.AdapterSocket // Corrected import
import com.example.nekit.Rule.RuleManager // Corrected import
import com.example.nekit.Event.Observer // Corrected import
import com.example.nekit.Event.ObserverFactory // Corrected import
import com.example.nekit.Event.Event.TunnelEvent // Corrected import
import com.example.nekit.Event.Event.ProxySocketEvent // Corrected import
import com.example.nekit.Event.Event.AdapterSocketEvent // Corrected import
import com.example.nekit.Event.Event.ProxyServerEvent // Corrected import
import com.example.nekit.Event.Event.RuleMatchEvent // Corrected import

// --- End Placeholders ---


open class DebugObserverFactory : ObserverFactory() {
    // public override init() {} // Kotlin provides default constructor if no primary is defined

    override fun getObserverForTunnel(tunnel: Tunnel): Observer<TunnelEvent>? {
        return DebugTunnelObserver()
    }

    override fun getObserverForProxyServer(server: ProxyServer): Observer<ProxyServerEvent>? {
        return DebugProxyServerObserver()
    }

    override fun getObserverForProxySocket(socket: ProxySocket): Observer<ProxySocketEvent>? {
        return DebugProxySocketObserver()
    }

    override fun getObserverForAdapterSocket(socket: AdapterSocket): Observer<AdapterSocketEvent>? {
        return DebugAdapterSocketObserver()
    }

    override fun getObserverForRuleManager(manager: RuleManager): Observer<RuleMatchEvent>? {
        return DebugRuleManagerObserver()
    }
}

open class DebugTunnelObserver : Observer<TunnelEvent>() {
    private val logger = LoggerFactory.getLogger(DebugTunnelObserver::class.java)
    override fun signal(event: TunnelEvent) {
        val message = event.toString() // logger will handle formatting
        when (event) {
            TunnelEvent.RECEIVED_REQUEST,
            TunnelEvent.CLOSED ->
                logger.info(message)
            TunnelEvent.OPENED,
            TunnelEvent.CONNECTED_TO_REMOTE,
            TunnelEvent.UPDATING_ADAPTER_SOCKET ->
                logger.debug(message) // VERBOSE -> debug
            else -> // .closeCalled, .forceCloseCalled, .receivedReadySignal, etc.
                logger.trace(message) // DEBUG -> trace
        }
    }
}

open class DebugProxySocketObserver : Observer<ProxySocketEvent>() {
    private val logger = LoggerFactory.getLogger(DebugProxySocketObserver::class.java)
    override fun signal(event: ProxySocketEvent) {
        val message = event.toString()
        when (event) {
            ProxySocketEvent.ERROR_OCCURED ->
                logger.error(message)
            ProxySocketEvent.DISCONNECTED,
            ProxySocketEvent.RECEIVED_REQUEST ->
                logger.info(message)
            ProxySocketEvent.SOCKET_OPENED,
            ProxySocketEvent.ASKED_TO_RESPONSE_TO,
            ProxySocketEvent.READY_FOR_FORWARD ->
                logger.debug(message) // VERBOSE -> debug
            else -> // .disconnectCalled, .forceDisconnectCalled, .readData, .wroteData
                logger.trace(message) // DEBUG -> trace
        }
    }
}

open class DebugAdapterSocketObserver : Observer<AdapterSocketEvent>() {
    private val logger = LoggerFactory.getLogger(DebugAdapterSocketObserver::class.java)
    override fun signal(event: AdapterSocketEvent) {
        val message = event.toString()
        when (event) {
            AdapterSocketEvent.ERROR_OCCURED ->
                logger.error(message)
            AdapterSocketEvent.DISCONNECTED,
            AdapterSocketEvent.CONNECTED ->
                logger.info(message)
            AdapterSocketEvent.SOCKET_OPENED,
            AdapterSocketEvent.READY_FOR_FORWARD ->
                logger.debug(message) // VERBOSE -> debug
            else -> // .disconnectCalled, .forceDisconnectCalled, .readData, .wroteData
                logger.trace(message) // DEBUG -> trace
        }
    }
}

open class DebugProxyServerObserver : Observer<ProxyServerEvent>() {
    private val logger = LoggerFactory.getLogger(DebugProxyServerObserver::class.java)
    override fun signal(event: ProxyServerEvent) {
        val message = event.toString()
        when (event) {
            ProxyServerEvent.STARTED,
            ProxyServerEvent.STOPPED ->
                logger.info(message)
            ProxyServerEvent.NEW_SOCKET_ACCEPTED,
            ProxyServerEvent.TUNNEL_CLOSED ->
                logger.debug(message) // VERBOSE -> debug
            // else -> logger.trace(message) // No original DEBUG cases
        }
    }
}

open class DebugRuleManagerObserver : Observer<RuleMatchEvent>() {
    private val logger = LoggerFactory.getLogger(DebugRuleManagerObserver::class.java)
    override fun signal(event: RuleMatchEvent) {
        val message = event.toString()
        when (event) {
            RuleMatchEvent.RULE_DID_NOT_MATCH, RuleMatchEvent.DNS_RULE_MATCHED ->
                logger.debug(message) // VERBOSE -> debug
            RuleMatchEvent.RULE_MATCHED ->
                logger.info(message)
            // else -> logger.trace(message) // No original DEBUG cases
        }
    }
}
