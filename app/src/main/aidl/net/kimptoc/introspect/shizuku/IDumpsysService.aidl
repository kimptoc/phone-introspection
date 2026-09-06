package net.kimptoc.introspect.shizuku;

interface IDumpsysService {

    void destroy() = 16777114; // Reserved: Shizuku server calls this to tear the service down.

    String dumpsys(String service, in String[] args, int timeoutMs, int maxChars, out boolean[] truncated) = 1;

    // System-wide process count via `ps -A` (spec §3 T3's "Real process list").
    // Returns the count as a decimal string, or "ERROR ..." with the same
    // contract as dumpsys(). The raw listing itself (~100KB per call) never
    // crosses the Binder: only the count has downstream (Timeline) value, and
    // storing the raw text at this collector's cadence would grow ~10MB/day
    // (spec §7's storage caution), unlike dumpsys()'s deliberately-capped
    // raw-text captures.
    String processCount(int timeoutMs) = 2;
}
