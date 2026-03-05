package nekit.Rule

enum class DNSSessionMatchType {
    DOMAIN, IP
}

enum class DNSSessionMatchResult {
    REAL, FAKE, UNKNOWN, PASS
}