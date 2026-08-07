# Status

## Phase 1 (T0) — shipped 2026-08-05

Deployed to the S25 Ultra and verified on-device: builds, installs, launches,
foreground service starts, battery/thermal/boot/doze samples land in Room,
CSV export opens the SAF picker cleanly. Commit `e66731c` on `main`.

Decisions made beyond `spec.md` (which left them open):

- App name **Introspect**, package `net.kimptoc.introspect`.
- `minSdk 30` — floor set jointly by `PowerManager.getThermalHeadroom()` and
  `ActivityManager.getHistoricalProcessExitReasons()`. `compileSdk`/`targetSdk 36`.
- Toolchain: AGP 8.13.2, Kotlin 2.1.20, Room 2.7.0-beta01. (AGP 9.0.1 was tried
  first but its new built-in-Kotlin integration conflicts with KSP, and the
  classic Kotlin plugin path isn't compatible with AGP 9's extension internals
  yet — see commit history if this needs revisiting once the toolchain matures.)

One bug found and fixed via on-device testing: Android 15+ enforces
edge-to-edge by default, which left the top of `MainActivity`'s layout
rendered behind the status bar. Fixed with a `WindowInsetsCompat` listener.

## Now: soak test

**Started 2026-08-05. Review ~2026-08-12.**

Per spec §6, Phase 1 is meant to run untouched for about a week before
adding anything — that's what surfaces this specific phone's real
background-survival behaviour (Samsung's doze/app-standby aggression)
rather than a guess.

Checklist for the review:

- [x] Confirm "Exempt from battery optimisation" was granted on-device
      (without it the service likely dies well before a week is up).
      Confirmed via `dumpsys deviceidle whitelist` on 2026-08-05.
- [ ] Export CSV and check for gaps in the `doze`/`boot` samples —
      unexplained gaps mean the service got killed, not that anything is
      broken.
- [ ] Check the `exit_reason` collector for anything recorded.

### Finding: boot-restart works, but Samsung delays delivery ~14 min (2026-08-05)

Rebooted the phone to test `BootReceiver` end to end. Result: it works —
foreground service restarted, notification posted, `boot` collector picked
up the new boot timestamp — but `ACTION_BOOT_COMPLETED` wasn't delivered to
the app until **~14 minutes after boot actually completed** (confirmed via
`dumpsys activity broadcasts` timestamps vs `uptime`), not immediately as
on stock Android.

This is the risk spec §7 predicted ("Samsung background restrictions"),
now measured rather than assumed. The delay coincided with the phone being
unlocked and adb reconnecting, which may have nudged Samsung into flushing
deferred broadcasts — a phone left untouched after reboot could plausibly
take longer. Not a blocker for the soak test, but explains why any
post-reboot gap in the data isn't necessarily a bug.

### Finding: overnight drain correlates with Doze essentially never engaging (2026-08-06)

User reported the battery seemed to drain quickly. Pulled the full dataset
(43k samples, 08-05 11:23 → 08-06 12:03) and checked the discharge segments:

| Window | Duration | Drop | Rate | screen_on | device_idle |
|---|---|---|---|---|---|
| Overnight 22:23–01:54 | 3.5h | 73%→35% | -10.8%/hr | true 282/283 | false 283/283 |
| Pre-dawn 07:08–09:00 | 1.9h | 94%→65% | -15.5%/hr | true 60% | false 177/177 |
| Morning 09:23–11:52 | 2.5h | 68%→22% | -18.6%/hr | true 78% | false 254/260 |

Across the full 24h dataset, `device_idle` (Doze) was `true` in only 72 of
2,546 samples, and 66 of those were one cluster on the afternoon of 08-05.
From 22:00 that night through the next morning, Doze essentially never
engaged, tracking almost exactly with the screen reporting "interactive"
nearly continuously over the same stretch.

Ruled out as an app-caused problem: `current_now_ua` during these windows
averages 0.6–1.0A, consistent with normal screen-on draw rather than a
runaway wakelock/CPU spike (which would look more like 1.5A+ sustained). No
unusual `exit_reason` entries, no service interruptions, no thermal spikes
in these windows. Introspect itself holds no wake locks.

What T0 can't answer: *which* app or activity kept the screen interactive
overnight — that needs per-app foreground time, i.e. Phase 2 (T1). If this
pattern repeats before 08-12, it's a real argument for starting T1 sooner
rather than waiting out the full soak-test week.

### Follow-up: ground-truthed two of the windows above against what was actually happening (2026-08-06)

User confirmed what the phone was doing during two stretches from the table above:

- **02:00–07:00** was actually on the charger (AC, 37%→95%) the whole time,
  not an idle-discharge window at all — `device_idle=false` there is
  expected (Doze doesn't engage while charging), not part of the "Doze
  never engages" anomaly above.
- **07:30–09:00** ("in the garden, not using the phone, just playing
  [streaming] music") turned out not to be uniformly idle either: screen/lock
  state showed ~37 min unlocked+screen-on at the start (07:30–08:07), ~26 min
  genuinely screen-off+locked (08:22–08:48) matching the description, then
  unlocked again from 08:52. Net drain was 90%→65% in 89 min (-16.8%/hr).

The more useful signal: current draw was nearly identical whether the
screen was on or off during this window (0.82A vs 0.87A) — on a truly idle
phone screen-off draw should be a fraction of screen-on draw. Confirmed
cause is streaming audio: decode + network + Bluetooth/speaker output keep
drawing power independent of screen state, so the "idle" portion wasn't
actually low-power. Not a bug — but it means streaming sessions cost close
to what active screen use would, and reinforces that T0 can't distinguish
local playback from streaming or attribute the draw to a specific app —
exactly what T1's per-app + network data would settle directly instead of
by cross-referencing what the user remembers doing.

## Phase 2 (T1) — in progress, PR opened 2026-08-06

Started ahead of the planned 08-12 review, per the two findings above:
the battery-drain investigation repeatedly hit the same wall (T0 can see
*that* the screen was interactive or *that* current was flowing, but not
*which app* was responsible), which was explicitly flagged as a reason to
pull Phase 2 forward rather than wait out the rest of the soak-test week.

Built on branch `phase2-t1`: `UsageEventsCollector` (incremental event
stream, 24h bootstrap backfill on first run, advances via `maxTs + 1` to
avoid re-emitting the boundary event), `UsageForegroundCollector` and
`NetworkStatsCollector` (both gated to a 30-min-minimum cadence via a
stored last-run timestamp — running them every 60s would emit one row per
installed package per cycle, a real storage-growth risk on a phone with
hundreds of packages), `InstalledPackagesCollector` (one-time snapshot,
dedup'd via a stored hash so it only re-emits when the package set
actually changes), and a "Grant usage access" onboarding button that
deep-links to `Settings.ACTION_USAGE_ACCESS_SETTINGS`. Manifest gained
`PACKAGE_USAGE_STATS` and `QUERY_ALL_PACKAGES`; confirmed `INTERNET` is
still absent from the merged manifest (`ACCESS_NETWORK_STATE`/`WAKE_LOCK`
showed up, pulled in by WorkManager's own manifest — read-only, no egress
capability, doesn't affect the no-network-egress guarantee).

Verified on-device (appop granted via `adb shell appops set … allow`,
no UI automation needed for that part): bootstrap backfill pulled in
7,798 events from the prior 24h on first run; the 30-min-gated collectors
each fired exactly once immediately (as designed) rather than every
cycle; `NetworkStatsManager.querySummary` worked with just the appop
grant, no separate permission needed; no crashes. The appop-*revoke* path
was confirmed separately below.

**This install interrupted the running soak test** — same as any
reinstall, it kills the process (`installPackageLI` in `exit_reason`,
consistent with the first entry from 08-05) and the foreground service
needed manually restarting; the ~14-min Samsung boot-restart delay
documented above applies to a full device reboot, not a reinstall, so it
wasn't a factor here. App data (all prior T0 history) survived the
update intact since this was a version upgrade, not an uninstall.

PR opened, not merged — per "PRs going forward."

### Automated PR review caught 4 real issues (2026-08-06)

An automated reviewer (kilo-code-bot) on PR #2 flagged, and all were
fixed same-day:

- `NetworkStatsCollector` and `UsageForegroundCollector` computed their
  query window as `now - 30min` instead of anchoring to `lastRun`. If a
  cycle ran late — which WorkManager + Doze makes routine, not rare —
  the gap between the previous `lastRun` and the new `now - 30min`
  silently vanished. `UsageEventsCollector`'s watermark approach
  (`maxTs + 1`) didn't have this problem; the other two now use
  `lastRun` as the window start the same way.
- `UsageEventsCollector` and `UsageForegroundCollector` had no exception
  handling around `queryEvents`/`queryUsageStats`, unlike
  `NetworkStatsCollector`. A user revoking usage access between
  `isAvailable()` and `collect()` is a normal race, not an edge case —
  both now catch `SecurityException`/`RuntimeException` and return an
  empty list, consistent with the rest of the codebase's "missing
  sample, not a crash" philosophy (spec §3).

### Finding: battery died at 0%, came back on its own, tested revoke path too (2026-08-06)

Phone died from battery exhaustion (not a deliberate reboot). Data
confirms it cleanly: `level_pct` hit exactly **0.0%** at 13:54:06, a
**61.7-minute gap** in the data follows, then samples resume at
14:55:45 with the phone already at 47% and charging.

`BootReceiver` fired **~1m42s after boot** this time (boot ~14:54:56,
receiver delivered 14:56:38) — much faster than the ~14-minute delay
measured after the earlier deliberate `adb reboot` test. Real-world
variability in Samsung's broadcast-deferral heuristics, not a fixed
constant; don't treat either number as the number.

Also closed out the deferred appop-revoke acceptance test while the
phone was connected: revoked `GET_USAGE_STATS` via adb, confirmed all
four T1 collectors' row counts stayed exactly flat for 2+ minutes (no
crash, `isAvailable()` correctly excluded them from the collection
loop), then re-granted and confirmed `usage_events` resumed immediately
(8657→8665 in the next cycle). T0 collectors kept writing throughout
both phases, confirming the service loop itself was never at risk —
only the T1 collectors were cleanly gated.

### Likely confound: known S25/S24 Ultra battery/thermal regression from the July 2026 patch (2026-08-06)

[NotebookCheck reported](https://www.notebookcheck.net/Galaxy-S25-Ultra-and-S24-Ultra-owners-report-severe-battery-drain-and-overheating-issues-after-July-update.1353452.0.html)
(2026-07-29) that Samsung's July security patch caused widespread S25
Ultra/S24 Ultra battery drain and overheating: affected users report
5–7%/hour loss while genuinely idle, hotter-than-normal running
temperatures, and slower charging. No Samsung acknowledgment or fix as
of the article date.

This phone's security patch is **2026-07-05** — squarely the patch in
question. Checked via `adb shell getprop ro.build.version.security_patch`.

This doesn't overturn the earlier drain findings above (Doze not
engaging, screen-on time, streaming audio) — none of our flagged
windows were purely idle, so we can't cleanly separate "usage-driven"
from "bugged idle floor." More likely the two stack: this regression
inflates the baseline underneath whatever real usage is happening on
top. Treat as a standing confound on every drain number this app
reports until Samsung ships a fix — if rates drop sharply after some
future OTA, that's the tell this was the cause rather than usage
patterns. Nothing actionable on our end; there's no known workaround,
only a firmware fix would resolve it.

## 2026-08-07: shipped fixes, quantified the Android Auto dongle issue, Samsung support engagement, storage + memory findings

### Shipped

- **PR #3** (battery-threshold notification, fixed at 50%) merged. Fires
  once on a real downward crossing while unplugged, re-arms once charged
  back above 50%. Verified twice independently on real crossings
  (2026-08-06 22:07:12 and 2026-08-07 09:52:29 — the latter matched the
  underlying battery broadcast timestamp to the second).
- **Issue #4 / PR #5**: `UsageForegroundCollector` was reporting a
  cumulative "so far today" total, not a per-cycle delta — root cause was
  `queryUsageStats(INTERVAL_BEST, …)` snapping to Android's own coarser
  stored buckets rather than the requested custom window. Fixed by
  diffing against a stored per-package baseline; a drop below baseline is
  only trusted as the midnight reset if the window actually crosses local
  midnight (checked via `java.time.LocalDate`), otherwise the package is
  skipped that cycle rather than emitting an untrustworthy value. Verified
  on-device: zero spurious rows for unchanged apps after the fix, vs.
  the old behaviour of re-emitting the same stale number every cycle.
- **Issue #6 / PR #7**: added `StorageCollector` (T0, no permission,
  30-min gated) after Samsung support flagged low storage as a possible
  contributor — see below.
- Both new collectors went through a second review round (null-safety on
  nullable platform types, wall-clock-jump handling treating a negative
  `now - lastRun` as "run now" rather than blocking indefinitely) — same
  class of bug caught twice by the automated PR review, now fixed
  consistently across `NetworkStatsCollector`, `UsageForegroundCollector`,
  and `StorageCollector`.

### Finding: Android Auto dongle is reconnecting ~every 5–10 min, around the clock, unrelated to driving

Quantified via `usage_events`: **160 distinct Gearhead (Android Auto)
wake/connect sessions over 48 hours**, spanning 2026-08-05 13:03 through
2026-08-07 08:58, including a steady cadence straight through 1am–5am
both nights while the car was parked. This is the user's known flaky
dongle, not drive activity — confirmed directly with the user. Cross-referenced
against `dumpsys batterystats --charged`: **Bluetooth scan time was
~14h05m, essentially the entire ~14h tracked period** — i.e. continuous,
not occasional. Strong candidate for a real, fixable (dongle-side, not
phone-side) contributor to background drain.

### Finding: this morning's drain (2026-08-07) was usage-driven, not anomalous

93%→49% over ~3h (-14.7%/hr) was fully explained once T1 data was
available: ~29 min in Coin Master, ~16 min Chrome, ~15 min 1Password,
BBC Sounds streamed ~60MB over wifi, Instagram ~21MB. Confirms the
pattern from 2026-08-06: this phone's elevated drain rates generally
have a real, findable usage story once T1 exists to check — the earlier
guesswork (T0-only) couldn't have settled this.

### 1Password slow-unlock, investigated twice

**First instance:** thermal ruled out (status "light" only, headroom
~0.80–0.84, battery 35.5°C — cooler than the morning's own 39.8°C peak).
Traced via `usage_events`: opened 11:54:53, then a 42-second gap with no
app-lifecycle events (likely the biometric overlay, which usage stats
doesn't track), screen timed out to Daydream at 11:55:36 (only happens
after a stretch of no touch input), woken and abandoned by 11:55:41 — a
real ~45-second stall, not a perception issue. An initial theory that
concurrent app activity (Global Player, a podcast app) caused contention
was **wrong and corrected by the user** — those apps started *after* the
user had already given up on 1Password, not during.

**Second instance:** correlated with a Samsung Device Care alert
("apps/processes overloading system, needs a restart") arriving at the
same moment. Live `dumpsys meminfo` showed **1,026 total processes**,
**Travel Town at 1.48GB PSS and Coin Master at 1.29GB PSS** individually
(both idle, not being played), 8.8GB/11.4GB RAM in use, and at least two
processes with a low-memory callback logged 2–6 minutes prior. Neither
game had a battery-optimization whitelist entry or an elevated standby
bucket (both were bucket 20/working-set, nothing special) — ruling out a
Game-Booster-specific privilege and pointing at the plain per-app
"background usage limit" setting as the real lever. User set both games
to Restricted (from Optimised). Post-restart, used RAM dropped from
8.8GB to 6.3GB.

### Samsung support exchange

Sent the Bluetooth-scan-time, screen-on/off split, and CPU-while-idle
numbers above. Support's reply was substantive, not a brush-off: they
correctly declined to confirm the July-patch causation from correlation
alone, but explicitly validated the memory/process-bloat and Android
Auto findings as plausible contributors, and asked for storage to be
raised above 10% before a few hours of further monitoring.

**Storage finding:** device was at **9.03% free** (20.7GB of 228.5GB) —
below Samsung's recommended 10%, confirmed both via `df` and the new
`StorageCollector`, which agreed closely (9.03% vs. an initial manual
9.2%). Traced concrete, actionable culprits rather than leaving it
vague: the podcast player app has **~12.4GB** of downloaded episodes in
external storage, WhatsApp media is **~7.3GB** — only ~2.15GB needs
freeing to cross 10%, so clearing old podcast downloads alone comfortably
solves it.

### Tooling note: Battery Historian evaluated and dropped

Attempted to stand up [google/battery-historian](https://github.com/google/battery-historian)
locally to visualize the `dumpsys batterystats` data we've been parsing
by hand all session. Dropped after finding real blockers: the official
prebuilt Docker image is gone (`gcr.io/android-battery-historian`
returns unauthenticated/not-found), the repo predates Go modules (no
`go.mod`), and its chart-rendering step shells out to a bundled
Python **2** script at runtime — not just a build-time dependency, and
Python 2 isn't installable-by-default on a modern Mac. Decision: keep
doing targeted manual `dumpsys batterystats` pulls, which have been
working fine.

### Corroboration: Android Auto reconnect pattern matches known One UI 8.5 reports

Shared the quantified dongle findings (160 reconnect sessions/48h,
~14h continuous Bluetooth scan time) externally for a second opinion.
Independent public reports describe the same symptom — Android Auto
"connecting, dropping within seconds, repeating" — as a known issue
following the One UI 8.5 update, including at least one report of a
wireless Android Auto adapter that worked fine for months and broke
immediately after updating to 8.5. This phone is confirmed on **One UI
8.5** (user-confirmed).

This reframes the dongle issue: likely a **software regression on
Samsung's side** (Bluetooth/Android Auto handling in 8.5), not a
hardware fault in the dongle itself — replacing the dongle would
probably not fix it. Plausibly bundled into the same update lineage as
the July security patch already flagged as a confound above, though
that's not confirmed (One UI feature updates and monthly security
patches often ship together, but aren't necessarily the same change).

## Next

- User to clear storage (podcast downloads) and monitor per Samsung's
  ask; check back on whether the memory/dongle fixes + storage headroom
  measurably change the drain pattern.
- Mention the One UI 8.5 Android Auto corroboration to Samsung support
  alongside the existing thread — it's independent evidence for a
  software-side root cause, not just this one phone's anecdote.
- Phase 3 (T2 + provisioning script) per spec §6, once the above
  monitoring window closes and there's runway to pick it up.
