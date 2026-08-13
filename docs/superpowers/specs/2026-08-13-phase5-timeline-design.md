# Phase 5 (UI): Timeline view — design

**Status**: approved, pending implementation plan
**Source**: spec.md §6, Phase 5 — "Timeline view aligning app sessions against thermal state and battery drain. Until then, export and analyse externally."

## Purpose

A general-purpose visual data browser for the `samples` table, not a
narrowly optimized single-workflow view. The primary ask from spec.md
(app sessions + thermal state + battery drain, aligned on a shared
time axis) is the core, with Doze/screen-on state added as a fourth
signal given how central Doze engagement has been to this project's
drain investigations (STATUS.md, repeatedly).

## Architecture

- New `TimelineActivity` in a new `net.kimptoc.introspect.timeline`
  package, launched via a new button on `MainActivity`. Matches the
  app's existing single-Activity-per-screen pattern — no
  Fragments/Navigation-component overhead, consistent with how
  `MainActivity` itself is structured.
- New dependency: [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart)
  (View-based, matches this app's classic-View UI — no Compose
  anywhere in the codebase currently). Chosen over hand-rolling a
  Canvas `View` to avoid re-implementing axis scaling, pan/zoom, and
  tap-marker handling from scratch.

## Data flow

`TimelineRepository` (new) exposes suspend query functions against
`SampleDao` (extended with new range-scoped queries), one per signal:

- **Battery**: `collector_id='battery' AND key='level_pct'`
- **Thermal**: `collector_id='thermal' AND key='status'`
- **Doze/screen**: `collector_id='doze' AND key IN ('device_idle','screen_on')`
- **App sessions**: derived, not a direct query — paired from
  `usage_events`' `activity_resumed`/`activity_paused`/
  `activity_stopped` rows (each resume paired with the next
  pause/stop for the same package). This mirrors how sessions have
  been traced manually throughout this project (e.g. the 1Password
  slow-unlock investigation in STATUS.md), and gives real session
  boundaries rather than `UsageForegroundCollector`'s coarser 30-min
  aggregate buckets.

Every query is scoped to `(startMs, endMs)` — never loads the full
table (400K+ rows and growing). Range picker: last 24h (default) /
3 days / 7 days / all time, plus MPAndroidChart's built-in pinch-zoom
and drag-to-pan for fine navigation within the loaded range.

**Downsampling for wide ranges**: "all time" against a
multi-hundred-thousand-row table would be slow to query and to
render. Downsample *in SQL* (bucket by a truncated timestamp,
one representative point per bucket) rather than loading everything
and thinning client-side — keeps both the query and the chart render
bounded regardless of how large the dataset grows.

## Rendering

A `LineChart` (battery % as the continuous line, the one truly
analog signal) with thermal state, Doze/screen state, and app
sessions drawn as colored horizontal bands sharing the same time
axis, panned/zoomed in sync with the battery line.

Tapping/dragging on the chart shows a marker view with the exact
timestamp, battery %, thermal state, Doze/screen state, and
foreground app at that x-position — looked up from the already-loaded
range data in memory, not a fresh query per tap.

## Edge cases

- No data in the selected range → a plain "no data in this range"
  state, not an empty/broken-looking chart.
- Usage access not granted → app-session band is empty; reuse the
  existing `UsageAccess.isGranted()` check to show a hint (consistent
  with `MainActivity`'s existing "Grant usage access" flow) rather
  than silently looking broken.
- Consistent with the rest of the app's "missing sample, not a crash"
  philosophy (spec §3): a missing signal is an empty/labeled gap,
  never a crash.

## Testing

On-device verification, this project's established pattern: build,
install, open the Timeline screen, check each range (24h/3d/7d/all)
renders real data without freezing, confirm tap-marker values match
what's actually in the DB for that timestamp, and check the
empty-range and no-usage-access states explicitly.

## Explicitly out of scope for v1

- T2/T3 signal overlays (battery attribution, logcat, dumpsys
  captures) — the four signals above are the full v1 scope; more
  overlays can be added later once the core view works.
- Editing/annotating the timeline, exporting a rendered image, or any
  interaction beyond tap-for-values and pan/zoom.
