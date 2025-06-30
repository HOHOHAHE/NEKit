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
import kotlinx.coroutines.yield
import org.slf4j.LoggerFactory

open class Tunnel(
    val proxySocket: ProxySocket
) : SocketDelegate {

    private val logger = LoggerFactory.getLogger(this::class.java)
    private var adapterSocket: AdapterSocket? = null
    private var _cancelled = false
    private var readySignal = 0
    
    private var _status: TunnelStatus = TunnelStatus.INVALID
    val status: TunnelStatus
        get() = _status
        
    val isCancelled: Boolean
        get() = _cancelled
        
    val isClosed: Boolean
        get() = proxySocket.isDisconnected && (adapterSocket?.isDisconnected ?: true)

    var delegate: TunnelDelegate? = null

    init {
        proxySocket.delegate = WeakReference(this)
    }

    fun openTunnel() {
        if (_cancelled) return
        
        proxySocket.openSocket()
        _status = TunnelStatus.READING_REQUEST
        logger.info("Tunnel opened with status: {}", _status)
    }

    fun close() {
        if (_cancelled) return
        
        _cancelled = true
        _status = TunnelStatus.CLOSING
        
        if (!proxySocket.isDisconnected) {
            proxySocket.disconnect()
        }
        adapterSocket?.let { adapter ->
            if (!adapter.isDisconnected) {
                adapter.disconnect()
            }
        }
    }
    
    fun forceClose() {
        if (_cancelled) return
        
        _cancelled = true
        _status = TunnelStatus.CLOSING
        
        if (!proxySocket.isDisconnected) {
            proxySocket.forceDisconnect()
        }
        adapterSocket?.let { adapter ->
            if (!adapter.isDisconnected) {
                adapter.forceDisconnect()
            }
        }
    }

    // SocketDelegate methods
    override fun didConnect(socket: SocketProtocol) {
        if (_cancelled) return
        
        logger.info("Tunnel: Adapter socket connected: {}", socket)
        // 不在這裡調用respondTo，讓didBecomeReadyToForward處理
    }

    override fun didDisconnect(socket: SocketProtocol) {
        logger.info("Tunnel: Socket disconnected: {}", socket)
        if (!_cancelled) {
            close()
        }
        checkStatus()
    }

    override fun didRead(data: ByteArray, from: SocketProtocol) {
        if (from == proxySocket) {
            // observer?.signal(.proxySocketReadData(data, from: socket, on: self))
            logger.trace("ProxySocket read {} bytes", data.size)
            
            if (_cancelled) {
                return
            }
            GlobalScope.launch(Dispatchers.IO) {
                adapterSocket?.write(data)
            }
        } else if (from == adapterSocket) {
            // observer?.signal(.adapterSocketReadData(data, from: socket, on: self))
            logger.trace("AdapterSocket read {} bytes", data.size)
            
            if (_cancelled) {
                return
            }
            GlobalScope.launch(Dispatchers.IO) {
                proxySocket.write(data)
            }
        }
    }

    override fun didWrite(data: ByteArray?, by: SocketProtocol) {
        if (_cancelled) return
        
        if (by == proxySocket) {
            logger.trace("ProxySocket wrote {} bytes, triggering AdapterSocket read", data?.size ?: 0)
            // 對應Swift版本的延遲讀取，避免過快的數據轉發
            GlobalScope.launch(Dispatchers.IO) {
                // 使用與Swift版本相同的50微秒延遲
                // Kotlin協程最小延遲為1毫秒，使用yield()來實現更小的延遲
                yield()
                adapterSocket?.readData()
            }
        } else if (by == adapterSocket) {
            logger.trace("AdapterSocket wrote {} bytes, triggering ProxySocket read", data?.size ?: 0)
            // ProxySocket端立即讀取
            GlobalScope.launch(Dispatchers.IO) {
                proxySocket.readData()
            }
        }
    }

    override fun didBecomeReadyToForward(socket: SocketProtocol) {
        if (_cancelled) return
        
        readySignal++
        logger.info("Tunnel: Socket ready to forward: {}, readySignal: {}", socket, readySignal)
        
        // 先處理readySignal邏輯
        if (readySignal == 2) {
            _status = TunnelStatus.FORWARDING
            GlobalScope.launch(Dispatchers.IO) {
                proxySocket.readData()
                adapterSocket?.readData()
            }
        }
        
        // 模擬Swift的defer行為：在方法結束時執行respondTo
        // 只有當socket是AdapterSocket時才調用respondTo
        if (socket is AdapterSocket) {
            proxySocket.respondTo(socket)
        }
    }

    override fun didReceive(session: ConnectSession, from: ProxySocket) {
        if (_cancelled) return
        
        _status = TunnelStatus.WAITING_TO_BE_READY
        logger.info("Tunnel: Received session: {} from {}, status: {}", session, from, _status)
        
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
    
    private fun checkStatus() {
        if (isClosed) {
            _status = TunnelStatus.CLOSED
            logger.info("Tunnel status changed to CLOSED")
            delegate?.tunnelDidClose(this)
        }
    }
}

interface TunnelDelegate {
    fun tunnelDidClose(tunnel: Tunnel)
}