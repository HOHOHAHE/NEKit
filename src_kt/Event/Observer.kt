package com.example.nekit.Event
import com.example.nekit.Event.EventType // Corrected import


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
