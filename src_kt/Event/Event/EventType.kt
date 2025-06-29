package com.example.nekit.Event.Event

/**
 * Base interface for all event types.
 *
 * In Swift, this protocol conformed to `CustomStringConvertible`, which implies
 * providing a `description: String`. In Kotlin, this is achieved by ensuring
 * implementing classes have a meaningful `toString()` method. Data classes and
 * enums often provide a suitable `toString()` automatically.
 */
interface EventType {
    // No specific methods or properties need to be declared here for `toString()`
    // as it's a member of `kotlin.Any`.
    // Implementers are expected to have a meaningful `toString()` representation.
}
