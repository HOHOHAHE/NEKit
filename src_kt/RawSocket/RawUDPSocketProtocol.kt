package com.example.nekit.RawSocket

import java.lang.ref.WeakReference
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port

interface RawUDPSocketProtocol {
    var delegate: WeakReference<RawUDPSocketDelegate?>?
    val isConnected: Boolean
    val sourceIPAddress: IPAddress?
    val sourcePort: Port?
    val destinationIPAddress: IPAddress?
    val destinationPort: Port?

    fun connect()
    fun disconnect()
    fun write(data: ByteArray)
    fun bind(host: String?, port: Int) // Add bind function
    val localAddress: IPAddress? // Add localAddress property
    fun send(data: ByteArray, destinationHost: String, destinationPort: Int) // Add send function
    
    // Optional callback to receive datagrams with source information (for SOCKS5 UDP Relay, etc.)
    var onDatagramReceived: ((data: ByteArray, sourceAddress: IPAddress, sourcePort: Port) -> Unit)?
}