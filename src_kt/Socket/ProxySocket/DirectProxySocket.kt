package com.example.nekit.Socket.ProxySocket

import org.slf4j.LoggerFactory

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.SocketDelegate
import com.example.nekit.Event.Event.ProxySocketEvent
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port

/**
 * This ProxySocket implementation forwards data directly.
 * It is designed to work with scenarios like tun2socks where the accepted socket
 * already represents a connection to a specific original destination.
 */
class DirectProxySocket(
    clientRawSocket: RawTCPSocketProtocol,
    observe: Boolean = true
) : ProxySocket(clientRawSocket, observe) {

    private val directProxyLogger = LoggerFactory.getLogger(DirectProxySocket::class.java)

    private enum class Status { // Combined Read/Write status for simplicity in Kotlin
        INVALID,
        FORWARDING,
        STOPPED;

        // Not overriding toString unless specific format like Swift's lowercase is needed. Default is fine.
    }

    private var internalReadStatus: Status = Status.INVALID
    private var internalWriteStatus: Status = Status.INVALID

    // Expose descriptions if needed, matching Swift's public computed properties
    val readStatusDescription: String
        get() = internalReadStatus.name.lowercase() // .name gives "INVALID", etc.

    val writeStatusDescription: String
        get() = internalWriteStatus.name.lowercase()


    /**
     * Begins processing for the direct connection.
     * It derives a [ConnectSession] from the raw client socket's destination information
     * (assuming it represents the original target) and signals [SocketDelegate.didReceive]
     * to establish the outgoing leg (which will likely also be direct).
     */
    override fun openSocket() {
        super.openSocket() // Signals event, base class is ready

        if (isCancelled) return

        // For DirectProxySocket, the rawSocket is the client connection.
        // Its destinationIPAddress/Port are what the client *originally* intended to connect to
        // in a tun2socks scenario.
        val destIP = rawSocket.destinationIPAddress
        val destPort = rawSocket.destinationPort

        if (destIP != null && destPort != null) {
            // Create a ConnectSession representing this direct flow.
            // The "host" for ConnectSession here is the IP address string.
            this.session = ConnectSession.create(destIP.presentation, destPort.hostOrderValue.toInt(), fakeIPEnabled = false)
            // ^ Using ConnectSession.create factory, assuming fakeIPEnabled=false for direct scenario.

            if (this.session == null) {
                directProxyLogger.error("Failed to create ConnectSession from raw socket destination {}:{}.", destIP, destPort)
                forceDisconnect(becauseOf = IllegalStateException("Failed to create session for direct proxy."))
                return
            }

            directProxyLogger.info("Socket opened. Derived session: {}. Notifying delegate.", this.session)
            observer?.signal(ProxySocketEvent.ReceivedRequest(this.session!!, this))
            delegate?.get()?.didReceive(this.session!!, this)
        } else {
            directProxyLogger.error("Raw socket missing destination IP/Port. Cannot derive session. Local: {}:{}.", rawSocket.sourceIPAddress, rawSocket.sourcePort)
            forceDisconnect(becauseOf = IllegalStateException("Missing destination information on raw socket for direct proxy."))
        }
    }

    /**
     * Called by the Tunnel when the corresponding AdapterSocket (likely a DirectAdapter)
     * is ready to forward data. This DirectProxySocket now also transitions to forwarding state.
     *
     * @param adapter The AdapterSocket that is ready (typically a DirectAdapter).
     */
    override fun respondTo(adapter: AdapterSocket) {
        super.respondTo(adapter) // Signals event

        if (isCancelled) return

        directProxyLogger.info("Adapter {} for session {} is ready. Transitioning to forwarding state.", adapter, session)
        internalReadStatus = Status.FORWARDING
        internalWriteStatus = Status.FORWARDING

        // Update overall status if not already established by ProxySocket base.
        // ProxySocket base init sets status to ESTABLISHED. respondTo indicates ready for data flow.
        _status = SocketStatus.ESTABLISHED // Re-affirm or ensure.

        observer?.signal(ProxySocketEvent.ReadyForForward(this))
        delegate?.get()?.didBecomeReadyToForward(this)
    }

    /**
     * Called by the underlying raw socket (client connection) when data is read.
     * Forwards the data directly to this socket's delegate (e.g., Tunnel).
     */
    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        super.didRead(data, from) // Signals ProxySocketEvent.ReadData (observer only)

        if (internalReadStatus == Status.FORWARDING) {
            delegate?.get()?.didRead(data, this) // Forward to Tunnel
        } else {
            directProxyLogger.warn("Data read for session {} in non-forwarding state: {}. Data size: {}.", session, internalReadStatus, data.size)
            // Potentially buffer or drop, or error. For now, just logs.
        }
    }

    /**
     * Called by the underlying raw socket (client connection) when a write operation completes.
     * Forwards this event to this socket's delegate (e.g., Tunnel).
     */
    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        super.didWrite(data, by) // Signals ProxySocketEvent.WroteData (observer only)

        if (internalWriteStatus == Status.FORWARDING) {
            delegate?.get()?.didWrite(data, this) // Forward to Tunnel
        } else {
            // Write completed for a message not in forwarding state (e.g. if there was handshake for some direct types)
            // For pure DirectProxySocket, this is less likely unless state machine is more complex.
        }
    }

    override fun disconnect(becauseOf: Throwable?) {
        internalReadStatus = Status.STOPPED
        internalWriteStatus = Status.STOPPED
        super.disconnect(becauseOf)
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        internalReadStatus = Status.STOPPED
        internalWriteStatus = Status.STOPPED
        super.forceDisconnect(becauseOf)
    }
}
