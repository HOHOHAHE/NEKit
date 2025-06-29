package com.example.nekit.Event

/**
 * A generic interface for objects that can emit events.
 * @param T The type of event that this source can emit.
 */
interface EventSource<T> {
    /**
     * Registers an observer to receive events from this source.
     * @param observer The observer to register.
     */
    fun addObserver(observer: Observer<T>)

    /**
     * Unregisters an observer from receiving events from this source.
     * @param observer The observer to unregister.
     */
    fun removeObserver(observer: Observer<T>)

    /**
     * Signals an event to all registered observers.
     * @param event The event to signal.
     */
    fun signal(event: T)
}
