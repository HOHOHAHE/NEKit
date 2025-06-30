package com.example.nekit.Socket.AdapterSocket

import com.example.nekit.Socket.SocketStatus

enum class SocketStatus {
    INVALID,
    CONNECTING,
    ESTABLISHED,
    FORWARDING, // Added FORWARDING state
    DISCONNECTING,
    CLOSED
}
