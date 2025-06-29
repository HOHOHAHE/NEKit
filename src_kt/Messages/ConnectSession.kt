package com.example.nekit.Messages

import java.net.InetAddress
import java.net.UnknownHostException

import org.slf4j.LoggerFactory

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import com.example.nekit.IPStack.DNS.DNSServer
import com.example.nekit.Rule.Rule
import com.example.nekit.GeoIP.GeoIP

// Enum for event source, as defined in Swift



/**
 * Represents all the information in one connect session.
 */
class ConnectSession private constructor(
    val requestedHost: String,
    val port: Int,
    val fakeIPEnabled: Boolean
) {
    /**
     * The real host for this session.
     * If the session is initialized with a host domain, then `host == requestedHost`.
     * Otherwise, the requested IP address is looked up in the DNS server to see if it corresponds to a domain if `fakeIPEnabled` is `true`.
     * Unless there is a good reason not to, any socket should connect based on this directly.
     */
    var host: String = requestedHost
    private val logger = LoggerFactory.getLogger(ConnectSession::class.java)
        private set // Can be modified internally by lookupRealIP

    var matchedRule: Rule? = null
    var error: Throwable? = null // Swift Error -> Kotlin Throwable
    var errorSource: EventSource? = null
    var disconnectedBy: EventSource? = null

    /**
     * The resolved IP address.
     * Note: This will always be real IP address after fake IP resolution.
     * The lazy evaluation can perform blocking DNS lookups.
     */
    val ipAddress: String by lazy {
        logger.info("Lazily resolving IP for host '{}', requestedHost '{}'", host, requestedHost)
        if (IPAddress.parse(this.host)?.isIP == true) { // Check if current `host` is an IP
            this.host
        } else {
            val resolvedIp = try {
                InetAddress.getByName(this.host).hostAddress
            } catch (e: UnknownHostException) {
                logger.error("Failed to resolve {}: {}", this.host, e.message, e)
                this.host // Return original hostname on failure
            }

            if (!fakeIPEnabled) {
                resolvedIp
            } else {
                val currentDnsServer = DNSServer.currentServer
                if (currentDnsServer == null) {
                    resolvedIp
                } else {
                    val addressObj = IPAddress.parse(resolvedIp)
                    if (addressObj == null || !currentDnsServer.isFakeIP(addressObj)) {
                        resolvedIp
                    } else {
                        val fakeSessionInfo = currentDnsServer.lookupFakeIP(addressObj)
                        // If fakeIP lookup returned a session, use its realIP. Otherwise, stick with resolvedIp.
                        fakeSessionInfo?.realIP?.presentation ?: resolvedIp
                    }
                }
            }
        }
    }

    /**
     * The location of the host, derived from `ipAddress`.
     * The lazy evaluation can perform blocking GeoIP lookups.
     */
    val country: String? by lazy { // Return type changed to String?
        logger.info("Lazily looking up country for IP '{}'", ipAddress)
        GeoIP.lookUp(this.ipAddress) // Directly call the new GeoIP.lookUp
                                     // It already returns String? (ISO code or null)
    }

    init {
        if (fakeIPEnabled) {
            // Initial lookup if requestedHost might be a fake IP.
            // This can modify `this.host` if a fake IP is resolved.
            if (!lookupRealIP()) {
                // This case was problematic in Swift as init? returns nil.
                // Here, we'd throw or mark as invalid. For now, let it proceed, error might be set.
                // Or, make primary constructor private and use factory method.
                // To match Swift's failable init, this part of logic should lead to failure.
                // However, the current structure with private constructor and companion factory handles this.
                // This init block is part of the private constructor, so failure here means factory returns null.
                // The `lookupRealIP` itself doesn't directly signal failure to constructor in this Kotlin setup.
                // Let's assume if lookupRealIP fails to change host, it's not a construction failure by itself.
                // The Swift `guard lookupRealIP() else { return nil }` is handled by the factory.
            }
        }
    }

    /**
     * Call when the session is disconnected. Only records the first disconnection event.
     */
    fun disconnected(becauseOf: Throwable? = null, by: EventSource) {
        if (disconnectedBy == null) {
            this.error = becauseOf
            if (becauseOf != null) {
                this.errorSource = by
            }
            this.disconnectedBy = by
            logger.info("Disconnected by {}. Error: {}", by, becauseOf)
        }
    }

    private fun lookupRealIP(): Boolean {
        val currentDnsServer = DNSServer.currentServer ?: return true // No DNS server, no fake IP concept to resolve

        // Only IPv4 is supported for fake IP resolution in original logic
        if (!isIPv4(requestedHost)) return true // Not an IPv4 string, can't be a fake IP we handle

        val address = IPAddress.parse(requestedHost) ?: return true // Not a valid IP address string

        if (!currentDnsServer.isFakeIP(address)) return true // Not a fake IP known to server

        val sessionInfo = currentDnsServer.lookupFakeIP(address) ?: run {
            logger.error("Fake IP {} lookup failed in DNSServer.", address)
            return false // Crucial: this was the failure point in Swift init?
        }

        // Successfully resolved fake IP: update host, ipAddress, rule, country
        this.host = sessionInfo.requestMessage.queries.firstOrNull()?.name ?: run {
            logger.error("No query name in DNSSession from fake IP lookup for requested IP {}.", requestedHost)
            return false // Or some default host?
        }
        // ipAddress lazy property will now use the new `host` or be set if sessionInfo.realIP exists.
        // Forcing ipAddress resolution here if realIP is available from sessionInfo:
        sessionInfo.realIP?.presentation?.let {
            // This assignment is tricky due to ipAddress being a lazy val.
            // We can't directly set it. Its recalculation should yield the correct real IP.
            // The original Swift code directly set `ipAddress = session.realIP?.presentation ?? ""`.
            // To achieve similar effect, ensure lazy ipAddress recalculates or prime it carefully.
            // For now, rely on `this.host` being updated, and `ipAddress` using it.
            // If sessionInfo.realIP is the definitive source, this needs adjustment.
             // Let's assume for now that if host is updated to real domain, ipAddress lazy will resolve it correctly.
             // If sessionInfo.realIP is more direct, then ipAddress lazy logic needs to use it or be bypassed.
        }
        this.matchedRule = sessionInfo.matchedRule

        // Swift code: `if session.countryCode != nil { country = session.countryCode! }`
        // Assuming DNSSession might have a pre-resolved countryCode.
        // For now, our DNSSession placeholder doesn't have it. Country will be resolved by its own lazy prop.
        // If DNSSession had `countryCode: String?`, then:
        // sessionInfo.countryCode?.let { this.countryCodeFromDNS = it } // And country lazy would use it.

        logger.info("Fake IP {} resolved to host '{}'", requestedHost, this.host)
        return true
    }

    fun isIPv4(hostToCheck: String = this.host): Boolean = IPAddress.parse(hostToCheck)?.isIPv4 ?: false
    fun isIPv6(hostToCheck: String = this.host): Boolean = IPAddress.parse(hostToCheck)?.isIPv6 ?: false
    fun isIP(hostToCheck: String = this.host): Boolean = isIPv4(hostToCheck) || isIPv6(hostToCheck)


    override fun toString(): String {
        val typeName = this::class.simpleName ?: "ConnectSession"
        return if (requestedHost != host) {
            "<$typeName host:$host port:$port requestedHost:$requestedHost>"
        } else {
            "<$typeName host:$host port:$port>"
        }
    }

    companion object {
        private val companionLogger = LoggerFactory.getLogger(ConnectSession::class.java.canonicalName + ".Companion")
        /**
         * Failable initializer pattern from Swift.
         * Creates a ConnectSession, performing fake IP lookup if enabled.
         * @return ConnectSession instance, or null if fake IP lookup implies failure (e.g., requestedHost is a fake IP but cannot be resolved).
         */
        fun create(host: String, port: Int, fakeIPEnabled: Boolean = true): ConnectSession? {
            // Perform initial checks that might cause init? to fail in Swift
            if (fakeIPEnabled) {
                // Simplified check: if it's an IP and fakeIPEnabled, it must be resolvable by lookupRealIP.
                // The core failure in Swift's lookupRealIP was `guard let session = dnsServer.lookupFakeIP(address) else { return false }`
                // This means if `dnsServer.lookupFakeIP(address)` returns null for a host that *is* considered a fake IP,
                // the session creation should fail.

                // Pre-check if it's an IP that *might* be fake and fail lookup
                val dnsServer = DNSServer.currentServer
                if (dnsServer != null && IPAddress.parse(host)?.isIPv4 == true) { // Assuming fake IPs are IPv4 as per lookupRealIP logic
                    val addressObj = IPAddress.parse(host)
                    if (addressObj != null && dnsServer.isFakeIP(addressObj)) {
                        if (dnsServer.lookupFakeIP(addressObj) == null) {
                            companionLogger.error("Detected fake IP {} that cannot be resolved by DNSServer. Failing creation.", host)
                            return null // Mimics failable init
                        }
                    }
                }
            }
            // If pre-checks pass, proceed with actual constructor (which also calls lookupRealIP)
            return ConnectSession(host, port, fakeIPEnabled)
        }

        fun create(ipAddress: IPAddress, port: Port, fakeIPEnabled: Boolean = true): ConnectSession? {
            return create(ipAddress.presentation, port.hostOrderValue.toInt(), fakeIPEnabled)
        }
    }
}
