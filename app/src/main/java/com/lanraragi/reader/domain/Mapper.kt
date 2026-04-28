package com.lanraragi.reader.domain

/**
 * Single-method mapper between two model shapes.
 *
 * The codebase already has a handful of `fun X.toY()` extension
 * functions that perform domain ↔ entity ↔ DTO conversions. Those stay
 * — they read clean at call sites and avoid the "mapper bouncing"
 * service-locator pattern. [Mapper] adds an *injectable* face on top of
 * those extensions so:
 *
 * - Tests can substitute a fake mapping without rebuilding the entire
 *   model (e.g., a mapper that always returns a known fixture).
 * - Future converters that are not pure functions (e.g., needing a
 *   server-profile lookup or an LRU cache) can implement the same
 *   interface without changing call sites.
 * - The audit's L1 unification can compose `Mapper<Archive, Foo>` and
 *   `Mapper<Foo, Archive>` into a single canonical pair regardless of
 *   the underlying storage shape.
 *
 * Implementations should be pure where possible. Side effects belong
 * in repositories.
 */
fun interface Mapper<in S, out T> {
    fun map(input: S): T
}

/**
 * Convenience for the common case where mappers are bidirectional. The
 * two halves can be implemented separately (different lifetimes,
 * different testability needs) and bundled here for callers that want
 * "give me round-trip access" without managing two references.
 */
data class Bimapper<A, B>(
    val forward: Mapper<A, B>,
    val backward: Mapper<B, A>,
) {
    fun map(a: A): B = forward.map(a)
    fun reverse(b: B): A = backward.map(b)
}
