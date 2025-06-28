package Opts

/**
 * Object holding global constants and configuration values for the application.
 */
object Opt {
    /**
     * Maximum read data size for NWTCPSocket (placeholder, as NWTCPSocket is Apple-specific).
     * Value: 128 * 1024 = 131072 bytes.
     */
    const val MAX_NWTCPSOCKET_READ_DATA_SIZE: Int = 128 * 1024 // Changed to screaming snake case

    /**
     * Maximum length for scanning, e.g., finding the end of an HTTP header.
     * Apache's default is 8KB (8192). This value is 8912.
     */
    const val MAX_NWTCPSCAN_LENGTH: Int = 8912 // Changed to screaming snake case

    /**
     * Time-To-Live (TTL) for DNS fake IP address records, in seconds.
     * Default: 300 seconds (5 minutes).
     * Note: The placeholder Opt in DNSServer.kt used 30 for this. This value (300) is from Opt.swift.
     * This should be consolidated during review. For now, using value from Opt.swift.
     */
    const val DNS_FAKE_IP_TTL: Int = 300 // Changed to screaming snake case

    /**
     * Lifetime for pending DNS sessions, in seconds.
     * Default: 10 seconds.
     * Note: The placeholder Opt in DNSServer.kt used 60 for this. This value (10) is from Opt.swift.
     */
    const val DNS_PENDING_SESSION_LIFETIME: Int = 10 // Changed to screaming snake case

    /**
     * Timeout for considering a UDP socket active, in seconds.
     * Default: 300 seconds (5 minutes).
     */
    const val UDP_SOCKET_ACTIVE_TIMEOUT: Int = 300 // Changed to screaming snake case

    /**
     * Interval for checking active UDP sockets, in seconds.
     * Default: 60 seconds (1 minute).
     */
    const val UDP_SOCKET_ACTIVE_CHECK_INTERVAL: Int = 60 // Changed to screaming snake case

    /**
     * Maximum length for an HTTP content block.
     * Default: 10240 bytes.
     * Note: The placeholder Opt in HTTPStreamScanner.kt used 8192 for this.
     */
    const val MAX_HTTP_CONTENT_BLOCK_LENGTH: Int = 10240 // Changed to screaming snake case

    /**
     * Default delay for the RejectAdapter, in milliseconds.
     * Default: 300 milliseconds.
     */
    const val REJECT_ADAPTER_DEFAULT_DELAY: Int = 300 // Changed to screaming snake case

    /**
     * Timeout for DNS queries, in seconds.
     * Default: 1 second.
     */
    const val DNS_TIMEOUT: Int = 1 // Changed to screaming snake case

    /**
     * Interval for forward reading operations, in milliseconds.
     * Default: 50 milliseconds.
     */
    const val FORWARD_READ_INTERVAL: Int = 50 // Changed to screaming snake case
}
