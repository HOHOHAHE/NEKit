package nekit.Utils

import java.math.BigInteger


class UInt128(val high: ULong, val low: ULong) : Comparable<UInt128> {

    companion object {
        val ZERO = UInt128(0uL, 0uL)
        val MAX = UInt128(ULong.MAX_VALUE, ULong.MAX_VALUE)

        fun fromUnparsedString(string: String): UInt128? {
            return try {
                val value = BigInteger(string)
                if (value < BigInteger.ZERO || value > BigInteger("1").shiftLeft(128).subtract(BigInteger.ONE)) {
                    null
                } else {
                    val high = value.shiftRight(64).toLong().toULong()
                    val low = value.and(BigInteger("FFFFFFFFFFFFFFFF", 16)).toLong().toULong()
                    UInt128(high, low)
                }
            } catch (e: NumberFormatException) {
                null
            }
        }
    }

    operator fun plus(other: UInt128): UInt128 {
        val newLow = low + other.low
        val carry = if (newLow < low) 1uL else 0uL
        val newHigh = high + other.high + carry
        return UInt128(newHigh, newLow)
    }

    override fun compareTo(other: UInt128): Int {
        val highCompare = high.compareTo(other.high)
        if (highCompare != 0) {
            return highCompare
        }
        return low.compareTo(other.low)
    }

    fun toBigInteger(): BigInteger {
        return BigInteger(high.toString()).shiftLeft(64).add(BigInteger(low.toString()))
    }

    fun toByteArray(): ByteArray {
        val bigInt = toBigInteger()
        val bytes = bigInt.toByteArray()
        if (bytes.size == 17 && bytes[0] == 0.toByte()) {
            return bytes.copyOfRange(1, 17)
        }
        if (bytes.size < 16) {
            val padded = ByteArray(16)
            System.arraycopy(bytes, 0, padded, 16 - bytes.size, bytes.size)
            return padded
        }
        return bytes
    }
}