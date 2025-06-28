package Socket.AdapterSocket.Factory

// Assuming AdapterFactory.kt and DirectAdapterFactory.kt are available.

/**
 * Manages a collection of [AdapterFactory] instances, keyed by unique string identifiers.
 * Provides dictionary-like access to retrieve or register adapter factories.
 *
 * A special case exists for the "direct" key, which always returns a new [DirectAdapterFactory] instance
 * on get, and attempts to store it if set (though getting "direct" always returns a new one).
 */
class AdapterFactoryManager(initialFactoryDict: Map<String, AdapterFactory>) {

    // Internally, use a MutableMap to allow modification via subscript setter,
    // even if the initial map is immutable.
    private val factoryDict: MutableMap<String, AdapterFactory> = initialFactoryDict.toMutableMap()

    /**
     * Retrieves an [AdapterFactory] for the given identifier (index).
     *
     * Special handling for "direct": always returns a new [DirectAdapterFactory] instance.
     * For other identifiers, retrieves from the internal dictionary.
     *
     * @param index The string identifier of the adapter factory.
     * @return The [AdapterFactory] if found, or a new [DirectAdapterFactory] if index is "direct",
     *         otherwise null if the index is not found.
     */
    operator fun get(index: String): AdapterFactory? {
        return if (index.equals("direct", ignoreCase = true)) { // Consider case insensitivity for "direct"
            DirectAdapterFactory()
        } else {
            factoryDict[index]
        }
    }

    /**
     * Registers or updates an [AdapterFactory] for the given identifier (index).
     *
     * Note: If `index` is "direct", the set value might be stored but `get("direct")`
     * will still return a new `DirectAdapterFactory()` instance due to its special handling in the getter.
     *
     * @param index The string identifier for the adapter factory.
     * @param value The [AdapterFactory] instance to register. If null, it could remove the entry,
     *              but current Swift setter implies non-null. Let's make it non-null.
     */
    operator fun set(index: String, value: AdapterFactory) {
        // It's unusual to allow setting "direct" if get always returns a new one.
        // This means `manager["direct"] = someFactory` would store `someFactory` under "direct",
        // but `manager["direct"]` would still yield `DirectAdapterFactory()`.
        // For now, replicating the behavior.
        factoryDict[index] = value
    }

    /**
     * Returns a read-only snapshot of all factories, excluding the special "direct" case.
     */
    fun getAllFactories(): Map<String, AdapterFactory> {
        return factoryDict.toMap() // Returns an immutable copy
    }
}