package nekit.Crypto

import nekit.Crypto.CryptoAlgorithm.*
import java.security.SecureRandom
import java.security.MessageDigest
import java.io.ByteArrayOutputStream

object CryptoHelper {
    val infoDictionary: Map<CryptoAlgorithm, Pair<Int, Int>> = mapOf(
        AES128_CFB to (16 to 16),
        AES192_CFB to (24 to 16),
        AES256_CFB to (32 to 16),
        CHACHA20 to (32 to 8),
        SALSA20 to (32 to 8),
        RC4_MD5 to (16 to 16)
    )

    fun getKeyLength(methodType: CryptoAlgorithm): Int {
        return infoDictionary[methodType]!!.first
    }

    fun getIVLength(methodType: CryptoAlgorithm): Int {
        return infoDictionary[methodType]!!.second
    }

    fun getIV(methodType: CryptoAlgorithm): ByteArray {
        val IV = ByteArray(getIVLength(methodType))
        SecureRandom().nextBytes(IV)
        return IV
    }

    fun getKey(password: String, methodType: CryptoAlgorithm): ByteArray {
        val keyLength = getKeyLength(methodType)
        val ivLength = getIVLength(methodType) // This is used in Swift, so keep it for consistency

        val passwordData = password.toByteArray(Charsets.UTF_8)
        var md5Result = md5Hash(passwordData)

        val result = ByteArrayOutputStream()
        result.write(md5Result)

        while (result.size() < keyLength + ivLength) { // Generate enough for key and IV
            val temp = ByteArrayOutputStream()
            temp.write(md5Result)
            temp.write(passwordData)
            md5Result = md5Hash(temp.toByteArray())
            result.write(md5Result)
        }

        // Return only the key part as per Swift's implementation
        return result.toByteArray().copyOfRange(0, keyLength)
    }

        fun getShadowsocksKeyAndIv(password: ByteArray, methodType: CryptoAlgorithm): Pair<ByteArray, ByteArray> {
        val keyLength = getKeyLength(methodType)
        val ivLength = getIVLength(methodType)

        var md5Result = md5Hash(password)

        val result = ByteArrayOutputStream()
        result.write(md5Result)

        while (result.size() < keyLength + ivLength) {
            val temp = ByteArrayOutputStream()
            temp.write(md5Result)
            temp.write(password)
            md5Result = md5Hash(temp.toByteArray())
            result.write(md5Result)
        }

        val fullData = result.toByteArray()
        val key = fullData.copyOfRange(0, keyLength)
        val iv = fullData.copyOfRange(keyLength, keyLength + ivLength)
        return Pair(key, iv)
    }

    private fun md5Hash(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(data)
    }
}