package nekit.Messages

import java.net.InetAddress
import java.net.UnknownHostException

import org.slf4j.LoggerFactory

import nekit.Utils.IPAddress
import nekit.Utils.Port
import nekit.IPStack.DNS.DNSServer
import nekit.Rule.Rule
import nekit.GeoIP.GeoIP

import nekit.Config.NetworkInterfaceType

data class ConnectSession(
    var host: String,
    val port: Int,
    var isTLS: Boolean = false,
    val requestedHost: String? = null,
    val fakeIPEnabled: Boolean = false,
    var interfaceType: NetworkInterfaceType = NetworkInterfaceType.DEFAULT
) {
    private val logger = LoggerFactory.getLogger(ConnectSession::class.java)

    var matchedRule: Rule? = null
    var error: Throwable? = null
    var errorSource: EventSource? = null
    var disconnectedBy: EventSource? = null

    val ipAddress: String by lazy {
        logger.info("Lazily resolving IP for host '{}'", host)
        if (IPAddress.parse(this.host)?.isIP == true) {
            this.host
        } else {
            try {
                InetAddress.getByName(this.host).hostAddress
            } catch (e: UnknownHostException) {
                logger.error("Failed to resolve {}: {}", this.host, e.message, e)
                this.host
            }
        }
    }

    val country: String? by lazy {
        logger.info("Lazily looking up country for IP '{}'", ipAddress)
        GeoIP.lookUp(this.ipAddress)
    }

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

    override fun toString(): String {
        return "<ConnectSession host:$host port:$port>"
    }
}
