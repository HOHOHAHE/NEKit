package com.example.nekit.Tunnel

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.ProxyServer.ProxyServer
import com.example.nekit.Socket.ProxySocket.ProxySocket
import com.example.nekit.Socket.SocketDelegate
import com.example.nekit.Socket.SocketProtocol
import com.example.nekit.Event.Event.TunnelEvent
import com.example.nekit.Event.ObserverFactory
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Rule.RuleManager
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

open class Tunnel(
    val proxySocket: ProxySocket
) : SocketDelegate {

    private val logger = LoggerFactory.getLogger(this::class.java)
    private var adapterSocket: AdapterSocket? = null
    private var isClosed = false

    var delegate: TunnelDelegate? = null

    init {
        proxySocket.delegate = WeakReference(this)
    }

    fun openTunnel() {
        proxySocket.openSocket()
    }

    fun forceClose() {
        isClosed = true
        adapterSocket?.forceDisconnect()
        proxySocket.forceDisconnect()
        delegate?.tunnelDidClose(this)
    }

    // SocketDelegate methods
    override fun didConnect(socket: SocketProtocol) {
        logger.info("Tunnel: Adapter socket connected: {}", socket)
        proxySocket.respondTo(socket as AdapterSocket)
    }

    override fun didDisconnect(socket: SocketProtocol) {
        logger.info("Tunnel: Socket disconnected: {}", socket)
        if (!isClosed) {
            forceClose()
        }
    }

    override fun didRead(data: ByteArray, from: SocketProtocol) {
        GlobalScope.launch(Dispatchers.IO) {
            if (from == proxySocket) {
                adapterSocket?.write(data)
            } else if (from == adapterSocket) {
                proxySocket.write(data)
            }
        }
    }

    override fun didWrite(data: ByteArray?, by: SocketProtocol) {
        GlobalScope.launch(Dispatchers.IO) {
            if (by == proxySocket) {
                adapterSocket?.readData()
            } else if (by == adapterSocket) {
                proxySocket.readData()
            }
        }
    }

    override fun didBecomeReadyToForward(socket: SocketProtocol) {
        logger.info("Tunnel: Socket ready to forward: {}", socket)
        GlobalScope.launch(Dispatchers.IO) {
            if (socket == proxySocket) {
                adapterSocket?.readData()
            } else if (socket == adapterSocket) {
                proxySocket.readData()
            }
        }
    }

    override fun didReceive(session: ConnectSession, from: ProxySocket) {
        logger.info("Tunnel: Received session: {} from {}", session, from)
        val manager = RuleManager.currentManager
        val factory = manager?.match(session)
        val adapter = factory?.getAdapter(session)
        this.adapterSocket = adapter
        adapter?.delegate = WeakReference(this)
        adapter?.openSocketWith(session)
    }

    override fun updateAdapter(newAdapter: AdapterSocket) {
        logger.info("Tunnel: Updating adapter to: {}", newAdapter)
        val oldAdapter = adapterSocket
        adapterSocket = newAdapter
        oldAdapter?.forceDisconnect()
        // Further logic to connect the new adapter is needed here
    }

    override fun didErrorOccur(error: Throwable, on: SocketProtocol) {
        logger.error("Tunnel: Error on socket {}: {}", on, error.message)
        forceClose()
    }
}

interface TunnelDelegate {
    fun tunnelDidClose(tunnel: Tunnel)
}