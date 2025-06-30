package com.example.nekit.Tunnel

/**
 * Represents the current status of a Tunnel.
 * Based on the Swift version's TunnelStatus enum.
 */
enum class TunnelStatus {
    /** The tunnel is just created but never started. */
    INVALID,
    
    /** The tunnel is reading the initial request from proxy socket. */
    READING_REQUEST,
    
    /** The tunnel is waiting for both sockets to be ready to forward data. */
    WAITING_TO_BE_READY,
    
    /** The tunnel is actively forwarding data between sockets. */
    FORWARDING,
    
    /** The tunnel is in the process of closing. */
    CLOSING,
    
    /** The tunnel is completely closed. */
    CLOSED
}