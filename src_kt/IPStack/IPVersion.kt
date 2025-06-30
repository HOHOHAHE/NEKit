package com.example.nekit.IPStack

enum class IPVersion(val value: Int) {
    IPV4(4),
    IPV6(6);

    companion object {
        fun fromInt(value: Int) = entries.firstOrNull { it.value == value }
    }
}