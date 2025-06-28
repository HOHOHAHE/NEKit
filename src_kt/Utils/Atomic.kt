import java.util.concurrent.Semaphore

/**
 * A generic container class used by `Atomic` to allow modification of the wrapped value
 * within the `withBox` lambda.
 * Note: This class is final and primarily for use with `Atomic`.
 */
class Box<T>(var value: T) // Made final, `open var value` becomes `var value`

/**
 * Atomic provides thread-safety to access a variable by ensuring that
 * reads, writes, and modify operations (via `withBox`) are serialized.
 */
open class Atomic<T>(value: T) {
    private var _value: Box<T> = Box(value)
    private val semaphore: Semaphore = Semaphore(1)

    /**
     * The thread-safe variable.
     */
    open var value: T
        get() {
            return withLock {
                this._value.value
            }
        }
        set(newValue) {
            withLock {
                this._value = Box(newValue)
            }
        }

    /**
     * The provides a scheme to access and change the underlying variable in a block.
     *
     * The variable can be accessed with `Box<T>.value` as:
     *
     * ```
     * val atomic = Atomic(listOf(1,2,3))
     * atomic.withBox { arrayBox ->
     *    arrayBox.value = arrayBox.value + 4 // Assuming immutable lists, create new list
     *    arrayBox.value.sum()
     * }
     * ```
     *
     * **Warning:** If `Atomic.value` is reassigned by another thread while this `Box` instance
     * is being used, the `Box` instance within this block will refer to the *old* value container.
     * Subsequent direct assignments to `Atomic.value` will not affect the `Box` instance passed to this block.
     * However, modifications made to mutable objects *inside* the `Box` (e.g., adding to a mutable list)
     * will still be thread-safe due to the lock.
     *
     * @param block The code to run with the variable wrapped in a `Box`.
     * @return Any value returned by the block.
     */
    open fun <U> withBox(block: (Box<T>) -> U): U {
        // The current _value (Box instance) is passed to the block.
        // If another thread calls `set(newValue)`, `this._value` in the Atomic instance
        // will point to a *new* Box instance. The block here will continue to operate
        // on the Box instance that was current when withBox was entered.
        // This is generally fine for modifying the *contents* of the value T if T is mutable,
        // or for reassigning `box.value` if T is immutable and the user understands this Box
        // is a snapshot of the container at the time of the call.
        return withLock {
            block(this._value)
        }
    }

    private fun <U> withLock(block: () -> U): U {
        semaphore.acquire()
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }
}
