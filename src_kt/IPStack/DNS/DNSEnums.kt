/**
 * DNS Record Types (QTYPE values).
 * Reference: IANA DNS Parameters: https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4
 */
@Suppress("SpellCheckingInspection") // For some less common type names
enum class DNSType(val rawValue: UShort) {
    INVALID(0u),
    A(1u),
    NS(2u),
    MD(3u), // Obsolete, use MX
    MF(4u), // Obsolete, use MX
    CNAME(5u),
    SOA(6u),
    MB(7u),   // Experimental
    MG(8u),   // Experimental
    MR(9u),   // Experimental
    NULL(10u), // Null RR
    WKS(11u), // Well Known Service (obsolete by SRV)
    PTR(12u),
    HINFO(13u),
    MINFO(14u),
    MX(15u),
    TXT(16u),
    RP(17u),  // Responsible Person
    AFSDB(18u),
    X25(19u),
    ISDN(20u),
    RT(21u),  // Route Through
    NSAP(22u),
    NSAP_PTR(23u), // nsap-ptr
    SIG(24u),
    KEY(25u),
    PX(26u),
    GPOS(27u),
    AAAA(28u),
    LOC(29u),
    NXT(30u), // Obsolete
    EID(31u), // Obsolete
    NIMLOC(32u), // Obsolete
    SRV(33u),
    ATMA(34u), // Obsolete
    NAPTR(35u),
    KX(36u),
    CERT(37u),
    A6(38u), // Obsolete
    DNAME(39u),
    SINK(40u), // Obsolete
    OPT(41u), // Pseudo-record type for EDNS
    APL(42u),
    DS(43u), // Delegation Signer
    SSHFP(44u), // SSH Public Key Fingerprint
    IPSECKEY(45u), // Swift enum did not have this, but 46 is RRSIG
    RRSIG(46u),
    NSEC(47u),
    DNSKEY(48u),
    DHCID(49u), // Swift enum did not have this
    NSEC3(50u), // Swift enum did not have this
    NSEC3PARAM(51u), // Swift enum did not have this
    TLSA(52u), // Swift enum did not have this
    // Many more types exist...
    TKEY(249u),
    TSIG(250u),
    IXFR(251u),
    AXFR(252u),
    MAILB(253u), // A request for mailbox-related records (MB, MG or MR)
    MAILA(254u), // A request for mail agent RRs (Obsolete - see MX)
    ANY(255u);   // A request for all records (*)

    companion object {
        private val map = entries.associateBy(DNSType::rawValue)
        fun fromRawValue(rawValue: UShort): DNSType? = map[rawValue]
    }
}

/**
 * DNS Message Type (Query or Response).
 * Part of the DNS header's QR field (1 bit).
 */
enum class DNSMessageType(val rawValue: UByte) {
    QUERY(0u),
    RESPONSE(1u);

    companion object {
        private val map = entries.associateBy(DNSMessageType::rawValue)
        fun fromRawValue(rawValue: UByte): DNSMessageType? = map[rawValue]
    }
}

/**
 * DNS Return Code (RCODE).
 * Part of the DNS header (4 bits).
 */
enum class DNSReturnCode(val rawValue: UByte) { // Renamed from DNSReturnStatus for clarity (RCODE)
    SUCCESS(0u),         // NoError - No Error
    FORMAT_ERROR(1u),    // FormErr - Format Error
    SERVER_FAILURE(2u),  // ServFail - Server Failure
    NAME_ERROR(3u),      // NXDomain - Non-Existent Domain
    NOT_IMPLEMENTED(4u), // NotImp - Not Implemented
    REFUSED(5u);         // Refused - Query Refused
    // Other RCODEs exist (6-15)

    companion object {
        private val map = entries.associateBy(DNSReturnCode::rawValue)
        fun fromRawValue(rawValue: UByte): DNSReturnCode? = map[rawValue]
    }
}

/**
 * DNS Class values (QCLASS values).
 * Typically IN (Internet).
 */
enum class DNSClass(val rawValue: UShort) {
    IN(1u), // Internet
    CS(2u), // CSNET class (Obsolete)
    CH(3u), // CHAOS class
    HS(4u), // Hesiod [Dyer 87]
    ANY(255u); // Any class (QCLASS only)


    companion object {
        private val map = entries.associateBy(DNSClass::rawValue)
        fun fromRawValue(rawValue: UShort): DNSClass? = map[rawValue]
    }
}
