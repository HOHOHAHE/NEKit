package nekit.Rule

// Assuming AllRule.kt is available in this package.
// Assuming DirectAdapterFactory.kt (placeholder from Config) is available.

import nekit.Socket.AdapterSocket.Factory.DirectAdapterFactory

/**
 * A rule that matches every request and directs it through a `DirectAdapterFactory`.
 * This is effectively a specialized version of [AllRule].
 */
open class DirectRule : AllRule(DirectAdapterFactory()) { // Call superclass constructor directly

    /**
     * Provides a string representation of the DirectRule.
     */
    override fun toString(): String {
        return "<${this::class.simpleName ?: "DirectRule"}>"
    }

    // No need for an explicit constructor if the superclass call is simple like this
    // and no other initialization is needed. Kotlin provides default constructor.
    // If there were other init logic:
    // constructor() : super(DirectAdapterFactory()) {
    //     // other init logic
    // }
}
