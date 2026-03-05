package nekit.Rule

import nekit.Utils.IPAddress
import nekit.Config.ConfigurationException

interface IPRange {
    operator fun contains(ip: IPAddress): Boolean

    companion object {
        fun fromString(rangeString: String): IPRange {
            // Placeholder implementation
            // This should parse strings like "192.168.1.0/24" or "10.0.0.1+10"
            // and return a concrete IPRange implementation (e.g., IPRangeCIDR, IPRangeStartLength)
            throw ConfigurationException.RuleParsingException("IPRange.fromString not implemented.")
        }
    }
}
