package net.kimptoc.introspect.collector.t3

/**
 * Raw `dumpsys sensorservice` output (spec §3 T3), the first T3 signal
 * shipped: smaller and lower-risk than `batterystats`, and directly
 * applicable to the step-counter debugging use spec §8 calls out — the
 * dump exposes whether the step sensor is batching, its FIFO depth, and
 * which clients are registered, which is usually where hourly step
 * counting goes wrong.
 *
 * No parsing yet - stores the raw dump text only, capped, per spec §3 T3's
 * "wrap every parser in try/catch, store the raw text alongside the
 * parsed result" and spec §7's storage-growth caution (this is the
 * "real space consumer" signal spec §7 names by name). Runs hourly, not
 * this app's usual 30 minutes: a full dump repeated too often is exactly
 * the storage growth spec §7 warns against, and sensor client/FIFO state
 * doesn't change on a sub-hour cadence in normal use.
 */
class SensorServiceCollector : DumpsysCollector() {
    override val id = "sensorservice"
    override val service = "sensorservice"
    override val intervalMs = 60 * 60 * 1000L
    override val maxChars = 20_000
}
