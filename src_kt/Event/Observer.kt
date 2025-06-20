// Placeholder for EventType interface, which should be in Event/EventType.kt
// For now, defining it here to make Observer generic constraint valid.
interface EventType {
    // This interface can be expanded based on the actual EventType.swift definition.
    // For example, it might enforce a name or timestamp.
}


/**
 * A generic observer class that can receive signals (events).
 *
 * @param T The type of event this observer can handle. Must conform to [EventType].
 */
open class Observer<T : EventType> {
    /**
     * Default constructor.
     */
    constructor()

    /**
     * Called to signal an event to the observer.
     * Subclasses should override this method to handle specific events.
     * The base implementation does nothing.
     *
     * @param event The event of type [T] that occurred.
     */
    open fun signal(event: T) {
        // Base implementation does nothing. Meant to be overridden.
    }
}
