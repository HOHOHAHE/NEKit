import java.util.concurrent.Semaphore

/**
 * This is just a wrapper as a work around since there is no way to change a passed-in value in a block.
 */
open class Box<T>(open var value: T)

/**
 * Atomic provides thread-safety to access a variable.
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
     * @param block The code to run with the variable wrapped in a `Box`.
     * @return Any value returned by the block.
     */
    open fun <U> withBox(block: (Box<T>) -> U): U {
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
