package com.example.nekit.ProxyServer

import com.example.nekit.Event.Event.ProxyServerEvent
import com.example.nekit.Event.Observer
import com.example.nekit.Event.ObserverFactory
import com.example.nekit.GlobalInitializer.GlobalInitializer
import com.example.nekit.Socket.ProxySocket.ProxySocket
import com.example.nekit.Tunnel.Tunnel
import com.example.nekit.Tunnel.TunnelDelegate
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

open class ProxyServer(
    val address: IPAddress?,
    val port: Port
) : TunnelDelegate {

    private val logger = LoggerFactory.getLogger(this::class.java)
    val type: String = this::class.simpleName ?: "ProxyServer"

    var observer: Observer<ProxyServerEvent>? = null
        private set

    protected val tunnels: MutableList<Tunnel> = mutableListOf()
    protected val tunnelsMutex = Mutex()

    override fun toString(): String {
        return "<$type address:$address port:$port>"
    }

    init {
        this.observer = ObserverFactory.getObserverForProxyServer(this)
    }

    @Throws(Exception::class)
    open suspend fun start() {
        tunnelsMutex.withLock {
            GlobalInitializer.initialize()
            observer?.signal(ProxyServerEvent.Started(this))
            logger.info("Started on {}:{}", address, port)
        }
    }

    open suspend fun stop() {
        logger.info("Stopping...")
        val tunnelsToClose: List<Tunnel>
        tunnelsMutex.withLock {
            tunnelsToClose = ArrayList(tunnels)
            tunnels.clear()
        }

        for (tunnel in tunnelsToClose) {
            try {
                tunnel.forceClose()
            } catch (e: Exception) {
                logger.error("Error force closing tunnel {}: {}", tunnel, e.message, e)
            }
        }

        tunnelsMutex.withLock {
            observer?.signal(ProxyServerEvent.Stopped(this))
        }
        logger.info("Stopped.")
    }

    open suspend fun didAcceptNewSocket(socket: ProxySocket) {
        logger.info("Accepted new socket: {}", socket)
        observer?.signal(ProxyServerEvent.NewSocketAccepted(socket, this))

        val tunnel = Tunnel(socket)
        tunnel.delegate = this

        tunnelsMutex.withLock {
            tunnels.add(tunnel)
        }

        try {
            tunnel.openTunnel()
        } catch (e: Exception) {
            logger.error("Error opening tunnel for {}: {}", socket, e.message, e)
            tunnelsMutex.withLock {
                tunnels.remove(tunnel)
            }
        }
    }

    override fun tunnelDidClose(tunnel: Tunnel) {
        logger.info("Tunnel closed: {}", tunnel)
        observer?.signal(ProxyServerEvent.TunnelClosed(this))

        CoroutineScope(Dispatchers.Default).launch {
            tunnelsMutex.withLock {
                tunnels.remove(tunnel)
            }
        }
    }
}
