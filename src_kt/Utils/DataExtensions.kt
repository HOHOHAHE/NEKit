package com.example.nekit.Utils

import java.nio.ByteBuffer



/**
 * Provides access to the underlying bytes of the ByteArray.
 * In Swift, `withUnsafeRawPointer` gives raw memory access. This Kotlin version
 * provides the ByteArray itself to a lambda, which is the closest safe equivalent
 * for "raw byte access" on the JVM.
 *
 * For more structured access, especially for reading/writing primitive types
 * with specific endianness, consider using `ByteBuffer.wrap(this)`.
 *
 * @param R The return type of the block.
 * @param block A lambda that takes the ByteArray and returns a value of type R.
 * @return The result of the block.
 */
inline fun <R> ByteArray.withBytes(block: (ByteArray) -> R): R {
    return block(this)
}

/**
 * Provides access to a ByteBuffer wrapping the ByteArray.
 * This is useful for more structured byte operations, like reading/writing
 * primitive data types with specific endianness.
 *
 * @param R The return type of the block.
 * @param block A lambda that takes a ByteBuffer wrapping the ByteArray and returns a value of type R.
 * @return The result of the block.
 */
inline fun <R> ByteArray.withByteBuffer(block: (ByteBuffer) -> R): R {
    return block(ByteBuffer.wrap(this))
}

// The original Swift code was:
// extension Data {
//    func withUnsafeRawPointer<ResultType>(_ body: (UnsafeRawPointer) throws -> ResultType) rethrows -> ResultType {
//        return try self.withUnsafeBytes { (ptr: UnsafePointer<Int8>) -> ResultType in
//            let rawPtr = UnsafeRawPointer(ptr)
//            return try body(rawPtr)
//        }
//    }
// }
// A direct translation of UnsafeRawPointer is not possible or safe in Kotlin/JVM.
// The functions above offer safe alternatives for working with byte data.
