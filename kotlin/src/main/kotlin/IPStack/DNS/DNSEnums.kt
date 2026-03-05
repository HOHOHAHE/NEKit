package nekit.IPStack.DNS

// Placeholder for DNSEnums.kt based on usage in other files.
// This file should contain enums like DNSType, DNSMessageType, etc.

enum class DNSType(val rawValue: Int) {
    A(1),
    NS(2),
    MD(3),
    MF(4),
    CNAME(5),
    SOA(6),
    MB(7),
    MG(8),
    MR(9),
    NULL(10),
    WKS(11),
    PTR(12),
    HINFO(13),
    MINFO(14),
    MX(15),
    TXT(16),
    AAAA(28),
    SRV(33),
    OPT(41),
    AXFR(252),
    MAILB(253),
    MAILA(254),
    ALL(255);

    companion object {
        fun fromRawValue(rawValue: Int) = values().firstOrNull { it.rawValue == rawValue }
    }
}

enum class DNSMessageType(val rawValue: Int) {
    QUERY(0),
    RESPONSE(1);

    companion object {
        fun fromRawValue(rawValue: Int) = values().firstOrNull { it.rawValue == rawValue }
    }
}

enum class DNSReturnCode(val rawValue: Int) {
    NO_ERROR(0),
    FORMAT_ERROR(1),
    SERVER_FAILURE(2),
    NAME_ERROR(3),
    NOT_IMPLEMENTED(4),
    REFUSED(5);

    companion object {
        fun fromRawValue(rawValue: Int) = values().firstOrNull { it.rawValue == rawValue }
    }
}

enum class DNSClass(val rawValue: Int) {
    IN(1),
    CS(2),
    CH(3),
    HS(4),
    ANY(255);

    companion object {
        fun fromRawValue(rawValue: Int) = values().firstOrNull { it.rawValue == rawValue }
    }
}

// Assuming DNSMessage and DNSResource are classes, not enums, and will be defined elsewhere.
// This file is just for enums.
