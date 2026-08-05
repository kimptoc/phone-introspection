package net.kimptoc.introspect.collector

/**
 * Capability tier a collector belongs to, per spec §2. Each tier degrades
 * gracefully to the one below; a fresh install with zero setup still
 * collects T0.
 */
enum class Tier {
    T0, T1, T2, T3, T4
}
