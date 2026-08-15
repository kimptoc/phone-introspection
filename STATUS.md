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

## 2026-08-08: measurable improvement after storage cleanup + game restrictions + dongle unplugged

Compared discharge segments before vs. after 2026-08-07's changes
(both games set to Restricted, storage cleared above 10%, and the
Android Auto dongle physically unplugged from the car):

| Metric | Before (08-05/06) | After (08-07/08) |
|---|---|---|
| Overnight drain rate | -10.8%/hr | **-5.6%/hr** |
| Morning drain rate | -18.6%/hr | **-5.5%/hr** |
| Overnight Doze engagement | 0% | **39%** |
| Morning Doze engagement | 2% | **51%** |
| Screen-on time | 78–100% | ~47% |
| Android Auto reconnects/day | 232 (08-06) | 52 (08-07) |

The Doze-engagement jump (0–2% → 39–51%) is the most telling number —
that's the mechanism flagged as broken in the very first drain
investigation (2026-08-06) actually functioning again, not just
"screen was off more." Drain rates now land at -5.5 to -5.6%/hr,
matching or beating the "5–7%/hr idle" baseline that was the
known-bad symptom for *other* affected users in the NotebookCheck
report. Can't cleanly separate how much is the Restricted-app setting
vs. reduced usage over these two days, since both happened together —
but the Doze mechanism itself working again is a structural change,
not a usage artifact.

The Android Auto reconnect drop (232→52/day) has a clean, confirmed
cause: the user physically unplugged the dongle from the car on
2026-08-07, rather than anything phone-side. Consistent with the
2026-08-07 finding that this was likely a One UI 8.5 software
regression rather than a phone-setting-fixable issue — removing the
dongle sidesteps it rather than resolving it.

## 2026-08-08: Phase 3 (T2) shipped — BatteryStatsManager works, logcat cross-process visibility confirmed unavailable

Both T2 signals from spec §3 built and merged (PR #8, PR #9), plus
`scripts/provision.sh` wired into `./gradlew installDebug` via a
Gradle `finalizedBy` task, so the adb grants can't be silently lost on
reinstall the way spec §3/§7 warn about — confirmed firing
automatically across several reinstalls this session.

**BatteryAttributionCollector** (per-UID battery consumption, mAh
since last charge): `BatteryStatsManager`/`BatteryUsageStats`/
`UidBatteryConsumer` turned out to be `@SystemApi` — confirmed absent
from the public SDK stub jars for API 33 through 36 — so this reflects
into the framework rather than compiling against it directly. That
reflection is itself blocked by Android's hidden-API enforcement
unless `hidden_api_policy` is relaxed, so `BATTERY_STATS` and
`hidden_api_policy` turned out to be a coupled requirement for this
signal, not the two independent unlocks spec.md originally implied —
spec.md corrected accordingly. Verified on-device: 146 real per-UID
consumers with plausible values (system `android` 19.9 mAh, Routines
10.8 mAh, dashcam app `com.mynextbase.connect` 3.5 mAh).

**LogcatCollector** (thermal daemon/LMK/JobScheduler/Doze/ANR side
channel): the collector mechanics are solid — verified over 110 cycles
across ~9 hours that the `logcat -T '<time>'` watermark genuinely
advances (`lines=` varying 0–118 per cycle, no duplicate/flooding
rows), with a 5s exec timeout and concurrent stdout/stderr draining to
stop a wedged `logcat` process from stalling the whole sampling cycle.
But **`READ_LOGS` does not grant this app cross-process log
visibility on this device** (S25 Ultra, Android 16, One UI 8.5,
2026-07-05 patch), despite every documented step being followed
correctly: manifest declaration, `pm grant`, confirmed
`granted=true`, and the running process confirmed to hold gid `1007`
(`log`) via `/proc/<pid>/status`, matching `platform.xml`'s
`READ_LOGS` → `gid=log` mapping. Every line ever read carried this
app's own PID throughout the 9-hour run, against 10 distinct PIDs
visible to the shell UID over the same buffer. A runtime "allow access
to logs" dialog appeared mid-session and was approved — retested
afterward and it made no difference. Unlike `BatteryAttributionCollector`,
there's no known complementary unlock; this looks like AOSP hardening
or Samsung SELinux restricting `logd` read access below what the
documented permission model implies.

Chasing this down also surfaced a real bug: the collector's only
"match" in that whole 9-hour run was a false positive — androidx.work's
own `SystemJobScheduler` boilerplate (this app's own WorkManager
instance) coincidentally contains the substring `jobscheduler`. Fixed
by excluding the current process's PID before keyword matching, though
that exclusion is narrower than it sounds: it only ever catches the
*currently running* instance, so a line from an earlier incarnation of
this same app (different PID, still sitting in buffer history) can
still pass — observed directly during testing across repeated
reinstalls.

**Decision: keeping `LogcatCollector`** despite the confirmed-empty
signal. Cost is ~290 rows/day of `read_status=ok ... pids=1` for as
long as this remains true; kept per spec §2 ("a tier going dark must
be visible, not silent") as a live tripwire in case a future OS or
permission-model change opens up real visibility.

Also closed out a carried-over task from Phase 2: verified
`UsageForegroundCollector` emits real per-cycle deltas on-device.
Checked 171 rows across 34 cycles since the T1 delta fix (PR #5)
deployed — zero clamp violations, deltas in a plausible 0–30 min range
averaging ~3 min. For contrast, pre-fix data from 2026-08-06 still
sitting in the DB shows the old bug's signature clearly: the same
package repeating an identical ~6-hour delta across ten consecutive
30-minute cycles.

## 2026-08-11: Phase 4 (T3) started — Shizuku set up, sensorservice collector shipped

Shizuku wasn't available through the Play Store on this device ("made
for an older version of Android" — a device-compatibility filter
issue, not an actual incompatibility). Sideloaded the APK directly
from Shizuku's GitHub releases instead, consistent with how this whole
app is distributed. Paired via wireless debugging and confirmed the
server running under the **shell** UID (`ps -A` showed `shizuku_server`
as `shell`, not the app's own restricted UID) — that's what makes T3
fundamentally different from T2's `LogcatCollector`, which stayed
sandboxed to its own process no matter what permission was granted.

**Architecture is a real departure from T0-T2.** `Shizuku.newProcess()`
is deprecated in the current API in favour of an AIDL-defined
`UserService`, bound asynchronously via `bindUserService()` and run in
a process Shizuku's server itself spawns. `ShizukuManager` holds the
bound binder as a singleton across `collect()` calls rather than
forcing an async bind into the synchronous `Collector` interface.
`daemon(true)` keeps the UserService alive across this app's own
process restarts, since Samsung killing the app is the normal case
here (spec §7), not the exception.

**`SensorServiceCollector`** (first T3 signal, PR #10): raw
`dumpsys sensorservice` output, capped at 20,000 chars, hourly cadence.
Verified on-device: `T3: live` after granting Shizuku access, real
20K-char dump captured on the first cycle, including exactly the
`step_counter active-count / sampling_period / batching_period` detail
spec §8 flags as useful for the step-counter debugging problem.

**Bot review caught 6 real bugs**, all fixed before merge:
- The hourly watermark was being consumed by a transient `NotBoundYet`
  result, burning a full hour waiting on a bind that normally
  finishes in seconds — partially undoing the same PR's prewarm fix.
- A genuine data race: `StringBuilder` written by a reader thread, read
  by the caller after a `join(1000)` that gives no happens-before
  guarantee if the thread hadn't actually finished.
- The reader thread had no exception handling — an uncaught throw
  there could take down the whole daemon process, not just fail one
  collection.
- stderr was never drained in `DumpsysService`'s exec — the same
  deadlock class already fixed once in `LogcatCollector`'s exec,
  reintroduced here by not remembering the earlier fix.
- `onServiceDisconnected` didn't reset the `binding` flag, so a bind
  failure that fires disconnect without ever connecting first would
  permanently lock out all future bind attempts.
- The "Grant Shizuku access" button silently did nothing when Shizuku
  wasn't installed/running — no user feedback at all.

One bot finding was incorrect (a claimed inversion in the pre-v11
Shizuku version check) — verified against the reference Shizuku demo's
own code, which matches this app's logic exactly. Pushed back on the
PR thread rather than "fixing" correct code, though the two functions'
phrasing was aligned anyway since the bot flagged it as worth
resolving for consistency.

**Real environmental finding, unrelated to the code**: Shizuku's
server died independently mid-session, tied to a wireless-adb
disconnect (not anything in the app). The manager app stayed running
but `shizuku_server` itself stopped. Recovered cleanly after
restarting Shizuku from its own app — the permission grant survived,
T3 went back to live, and the UserService rebound correctly. Worth
knowing this is a live dependency that can drop independently of the
phone or this app, not just a one-time setup step.

## 2026-08-11: second T3 collector (`batterystats`) — and a real concurrency bug surfaced by it

**`BatteryStatsCollector`** (PR #11): raw `dumpsys batterystats --charged`
output — spec.md's T3 row names this signal first ("Historical
per-UID wakelocks, CPU time, sensor time"), and it's genuinely
different data from `BatteryAttributionCollector`'s `BatteryStatsManager`
reflection, which only gives a flat mAh figure per UID with no
wakelock/CPU breakdown. Checked the real dump's structure on-device
before picking a cap (spec §7: decide deliberately, not after seeing
the DB size) — full dump is 1.7MB, `--charged` is 550KB, and the
per-UID power summary plus kernel wake lock sections both live in
roughly the first 150,000 characters, so that's the cap, at a 2-hour
cadence (vs `sensorservice`'s hourly) to keep storage growth in check
given the much bigger payload.

**A genuine cross-collector concurrency bug surfaced along the way.**
`MonitoringService`'s periodic loop and its event listeners
(battery/screen/thermal) all launch independent coroutines on a
multi-threaded dispatcher — near-simultaneous triggers at startup
(typically a sticky battery broadcast landing within milliseconds of
the periodic loop's first tick) could genuinely run `collectAll()`
concurrently. Every collector's watermark gate assumes only one
`collect()` call at a time, so two concurrent callers could both read
the same stale watermark before either wrote it back — observed
directly as 2-3x duplicate `dump` rows on startup. Cheap for
`sensorservice` (~20KB each), expensive at 150KB × 3 for
`batterystats`, which is what made it worth fixing now rather than
deferring again (it was flagged as a known gap in PR #10 and left
alone there).

Took two rounds to actually close: the first fix added a `Mutex`
scoped to `MonitoringService`, but bot review caught that
`SamplingWorker` — the 15-minute WorkManager fallback — calls the same
collectors on its own independent schedule, completely bypassing a
lock that only guarded one of the two entry points. Fixed properly by
moving the lock into `CollectorRegistry.collectAllLocked()`, the one
choke point both callers actually share, then made the old unlocked
`collectAll()` private so a future caller can't accidentally bypass it
by reaching for the shorter name. Verified on-device: zero same-key
duplicate rows across a fresh startup burst, after two prior startups
had shown 2x and 3x duplicates respectively.

**Found but explicitly not fixed**: `UsageEventsCollector` emits
genuinely identical triplicate rows for the same package at the same
timestamp — confirmed unrelated to the concurrency bug above (same
`value_num`/`value_text`, not a race artifact), a separate pre-existing
issue in that collector's own event-processing logic. Filed as its own
issue rather than scope-creeping the batterystats PR.

## 2026-08-11: third T3 collector (`deviceidle`) — and a genuine truncation-detection bug, caught in three rounds

**`DeviceIdleCollector`** (PR #13): raw `dumpsys deviceidle` output —
Doze state machine detail (`mState`/`mLightState`, idling history,
allowlists, `mNextAlarmTime`) directly relevant to this project's
recurring Doze-engagement investigation theme (see 2026-08-08's
before/after table). Complements the cheap T0 `DozeScreenCollector`
polling rather than replacing it — this is a periodic deep snapshot
with structural detail no public API exposes. Real dump is only
~17,000 chars; capped at 60,000 for headroom after bot review flagged
an initial 20,000-char cap as too tight (~2.8KB margin).

**Bot review caught a genuine three-round boundary bug** in how
`DumpsysService` reports whether a dump was truncated:
1. Round 1: a naive `sb.length >= maxChars` check couldn't distinguish
   "genuinely capped" from "coincidentally exactly `maxChars` long."
2. Round 2 "fix": moved the check into `DumpsysService` itself (the
   only code that sees the pre-truncation length) as `sb.length >
   maxChars` — but this can *never* be true, because the reader
   loop's own append guard (`if (sb.length < maxChars) sb.append(...)`)
   structurally prevents `sb` from ever exceeding the cap. A dump cut
   off exactly at the boundary read back as "fit fine" — the exact
   silent-truncation failure this parameter exists to catch.
3. Round 3 fix (the one that actually holds): a `sawDrop` flag set
   inside the reader loop's own `else` branch, at the moment content
   is genuinely skipped — `wasTruncated = sawDrop || sb.length >
   maxChars`. Also made the `truncated[0]` write unconditional rather
   than guarded, since the guard's own failure mode (silently
   swallowing the signal) was worse than a loud crash on a caller
   violating the contract.

Verified on-device after each round; `userServiceVersion` bumped
4→5→6 across the three rounds since `DumpsysService`'s behaviour kept
changing. A clear case of the bot catching a genuinely non-obvious
off-by-one/boundary bug three times running on the same small piece
of code, not diminishing-returns nitpicking.

## 2026-08-11: fixed `UsageEventsCollector` duplicate rows (issue #12, PR #14)

Root-caused the triplicate-row bug filed above. Confirmed it's
**not** the (already-fixed) cross-collector concurrency race: 11
duplicate groups occurred on-device over 4+ hours *after* that fix
was already live, each isolated rather than clustered at a single
wall-clock moment the way a race would produce. Almost all were
`type_10` (`NOTIFICATION_INTERACTION`, missing from the
`eventTypeName` map) — consistent with `UsageStatsManager.queryEvents()`
occasionally handing back the same event twice within a single call,
plausibly a query window straddling an internal usage-stats file
rotation and reading the same record from both the old and new file.

Fixed by deduping on `(timestamp, packageName, eventType)` identity
within each `collect()` call's result, since that's the only place
the duplication is actually observable — no fix possible upstream in
the OS API itself. Verified on-device: 293 fresh `usage_events` rows
post-fix, including 22 `type_10` events (the type that was
duplicating), zero duplicate groups.

## 2026-08-12: T3 complete — `cpuinfo`, a `DumpsysCollector` base class, and `power` close out spec §3's dumpsys list

**`CpuInfoCollector`** (PR #15): raw `dumpsys cpuinfo` output — system-
wide CPU load by process plus a `TOTAL` user/kernel/iowait/irq/softirq
breakdown. A point-in-time load snapshot, complementing
`BatteryStatsCollector`'s cumulative since-charge CPU time — directly
useful for the memory/process-bloat investigation thread (the
2026-08-07 1,026-process finding had no CPU-load data alongside it at
the time). Consistent ~17,000-char dump, capped at 50,000, hourly
cadence.

**Extracted a `DumpsysCollector` base class** (issue #16, PR #17)
after a bot nitpick on PR #15 pointed out that four collectors had
independently copied the same ~45-line state machine (watermark gate,
`NotBoundYet` handling, `Success`/`NotPermitted`/`Error` mapping) and
had already drifted apart in practice: `SensorServiceCollector`
predated the `truncated` handling the other three shipped with, with
no mechanism to pick it up. Bringing it up to date surfaced real
value — `sensorservice` (20,000-char cap vs. a real 268,855-char
dump) and `batterystats` (150,000 vs. ~550,000) now correctly report
`dump_status=truncated` every cycle, previously silent. This was also
the **first on-device confirmation that the `sawDrop` fix (PR #13)
actually detects real truncation**: `DeviceIdleCollector`'s dump never
exceeds its own cap, so all its prior verification only ever proved
the absence of false positives, never a true positive.

One capture during that verification briefly looked like a
regression — traced via `dumpsys package`'s `lastUpdateTime` to the
old pre-refactor build (installed *before* that particular capture),
not a defect in the new code. 3/3 reproductions post-install,
including deliberately forcing all four collectors to fire together
right after a fresh Shizuku bind (the exact scenario that produced
the anomaly), correctly detected truncation with no false positives
on the two collectors that don't need it.

**`PowerCollector`** (PR #18): raw `dumpsys power` output — the last
named T3 signal in spec §3's dumpsys table. Currently-held wake locks
by owner/uid/duration and suspend blocker state — the classic
runaway-wakelock drain signature `BatteryStatsCollector`'s cumulative
view can only name a culprit for after the fact. Real dump is
~449,776 chars, but the `Wake Locks`/`Suspend Blockers` sections that
matter are fully contained in the first ~13,100 — capped at 30,000
for >2x headroom, same head-heavy pattern as `sensorservice`/
`batterystats`. Trivial to add thanks to the new base class — five
lines plus KDoc. Verified on-device: real wake lock data captured
(including a 2-day-11-hour `StepCounterWakeLock`, a candidate worth a
closer look), `truncated` correctly reported.

**Real environmental issue hit during `power`'s verification,
unrelated to the code**: three `shizuku_server` processes were
running simultaneously, which appears to be why binding got stuck on
repeated `NotBoundYet` across 5+ cycles. Resolved by force-stopping
and restarting Shizuku cleanly from its own app — binding then
succeeded on the very next cycle. Another entry in the "Shizuku is a
live dependency that can misbehave independently of this app"
column, alongside the earlier server-death incidents.

Spec §3's entire named T3 dumpsys list (`batterystats`,
`sensorservice`, `deviceidle`, `cpuinfo`, `power`) is now shipped.

## 2026-08-12: MainActivity status UI fixed (issue #1, PR #19)

All four points from issue #1: toggle button now reads
`MonitoringService.isRunning` (a volatile companion flag set in
`onCreate`/`onDestroy`) instead of a local boolean that started false
on every launch regardless of reality; "Stop monitoring" now cancels
the `SamplingWorker` WorkManager fallback too, not just the
foreground service; tier status list has short per-tier labels; status
text shows last-sample age and row count, kept live via a
`repeatOnLifecycle(RESUMED)` 5s poll.

**Bot review round 1** caught three issues, all fixed: the toggle
click was reading `isRunning` back *after* calling `start()`/`stop()`,
before `onCreate()`/`onDestroy()` had actually run, so the tap still
showed the pre-tap state — fixed by deciding the intent up front
(`targetRunning = !isRunning`) and driving the UI from that instead;
`refreshServiceStatus()` spawned a fresh unawaited coroutine on every
5s tick and every tap, outliving `repeatOnLifecycle`'s own
cancellation — made `suspend` and called directly from the existing
loop coroutine; hardcoded English strings moved to `strings.xml`.

**Bot review round 2** caught that round 1's own fix was still
broken: `lifecycleScope` runs on `Dispatchers.Main.immediate`, so the
click handler's `launch { refreshServiceStatus() }` executed *inline*
in the same call stack, re-deriving the not-yet-updated
`isRunning` and immediately overwriting the fresh label with the
stale one — same bug, one frame later. Fixed by passing the decided
intent through as a `forceRunning` parameter instead of re-deriving
it. Also flagged a `maxLines=3` truncation risk on the status text
(added in the same round for layout stability) at large accessibility
font scales — dropped `maxLines`, kept `minLines=3` for the stable
height budget without capping content.

**A genuine bug surfaced during manual on-device verification**,
independent of the bot: `BootReceiver` was restarting monitoring
unconditionally on every boot, which would have silently un-stopped
an explicit "Stop" tap after a reboot — the exact "UI says one thing,
reality does another" failure this issue exists to close, just
reboot-triggered. Fixed by persisting user intent via a
`monitoring_state` SharedPreferences flag (`start()`/`stop()` both
write it, default `true` so existing boot-restart resilience is
unaffected for anyone who's never tapped Stop) that `BootReceiver`
checks before restarting anything.

Verification here was itself a lesson: an early WorkManager-cancel
check read `no_backup/androidx.work.workdb` without its `-wal` file
and showed a stale pre-cancel snapshot — same class of artifact as
the `introspect.db` "malformed" pull earlier in the project. Fixed by
pulling all three SQLite files (`.db`/`-wal`/`-shm`) together so
SQLite replays the WAL on open.

## 2026-08-12: filed issue #20 — `myhourly:StepCounterWakeLock` held 2+ days continuously

`PowerCollector`'s wake-lock data, checked again: the same lock
(`uid=10719`, `pid=12316`, `lock=7625c29` — same ID across both
checks, confirming one continuous hold, not repeated re-acquisition)
went from 2d11h to 2d18h between two captures a few hours apart.
Cross-referenced: `usage_foreground` shows zero recent foreground
time for `com.example.myhourlystepcounterv2`, yet
`BatteryAttributionCollector` shows it attributing real mAh in most
2-hour windows — a background app being kept alive by its own
held-forever wake lock, exactly the pattern `PowerCollector` was
built to catch. Filed as issue #20 (third-party app, not something to
fix in Introspect) with four concrete investigation angles, including
whether the step counter's own sensor batching (spec §8) could
replace the wake lock entirely.

## 2026-08-12/13: multi-day stats review — Android Auto churn is longstanding, Doze/drain numbers are confounded by this week's dev work

Pulled the full dataset and checked the days since the 2026-08-08
improvement:

- **Doze engagement collapsed again**: 33.6% (08-09) → 13.5% (08-10)
  → 0.0% (08-11) → 0.4% (08-12), tracking `power_save` going from
  100% → 63.5% → 0% → 0% on the same days. Can't cleanly separate
  cause from confound: 08-11/08-12 were also the heaviest dev-testing
  days this project has had (repeated reinstalls, constant screen
  wake, adb activity), which independently suppresses Doze regardless
  of any platform issue. **Not a confirmed regression** — needs a
  quiet non-dev day to re-check cleanly.
- **Drain rates crept back to 6–10%/hr** across discharge segments,
  above the 08-08 ~5.5%/hr improvement — same confound applies, these
  overlap active dev-session use, not clean idle windows.
- **Android Auto reconnect churn: confirmed longstanding, not a
  recent regression.** User asked whether the current ~40–80
  reconnects/hour during actual driving (08-11/08-12) reflects the
  dongle getting worse. Checked peak *hourly* reconnect rate across
  the full dataset instead of just daily totals: 08-06 (before the
  dongle was unplugged) peaked at **265/hour, sustained 220–265/hr
  from 2am–6am** — provably not driving. That's higher than anything
  measured since. Conclusion: the flaky-reconnect-while-paired rate
  looks roughly constant across the whole observation window: what
  changed on 08-07 was *exposure* (unplugging when not driving cut
  the ~20 idle hours/day it used to churn through), not the
  underlying churn rate itself. **Can't tell if it predates One UI
  8.5** - the dataset only starts 2026-08-05, and we don't know
  whether the phone was already on 8.5 by then; the only 8.5-specific
  evidence remains the external corroborating reports found
  2026-08-07, not anything measured on this device.

## 2026-08-13/15: Phase 5 (UI) shipped — timeline view, plus a fix for the gap that prompted the whole "why is nothing running" question

**`TimelineActivity`** (PR #21): battery level, thermal state, Doze/
screen-on state, and app sessions charted on a shared, pan/zoomable
time axis (MPAndroidChart `LineChart` + a custom `TimelineBandView`
for the three categorical bands), with a 24h/3d/7d/all-time range
picker (7d/all downsample in SQL to keep queries bounded against the
400K+-row `samples` table) and a tap-marker showing exact values.
Design and 8-task plan written up front
(`docs/superpowers/specs/2026-08-13-phase5-timeline-design.md`,
`docs/superpowers/plans/2026-08-13-phase5-timeline.md`) and built via
subagent-driven-development — a fresh implementer + fresh reviewer
per task, then a final whole-branch review.

The final review caught two real cross-task integration bugs
invisible to any single task's diff: stale bands left on screen when
switching to an empty range (also incorrectly gated on battery alone
rather than each signal independently), and a race where rapid
range-switching could corrupt the chart's X-axis origin. Bot review
on the resulting PR then caught three more, all confirmed against
real on-device data before fixing rather than taken on faith: a
phantom "still open" app session synthesized from a 39-hour-old
dangling `activity_resumed` with no pairing close (this device's
monitoring had been stopped since 2026-08-12, so the range being
tested was genuinely empty) that suppressed the "no data" state;
the same phantom walking back in via a wider range because the fix
only gated one of two paths that could produce it; and — the sharpest
one — `UsageEventsCollector`'s own source data firing **both**
`activity_paused` and `activity_stopped` for every single real
backgrounding, which meant the second of every pair was mistaken for
a genuinely orphaned close and synthesized a phantom session on
*every* backgrounding in the range, not just the rare true-orphan
case the fallback was meant for. One SQL-correctness claim from the
bot was disputed and verified wrong before responding: the bare-
column-alongside-`MIN()`/`MAX()` GROUP BY trick this code relies on
*is* documented, deterministic SQLite behavior, confirmed against the
exact query shape with `sqlite3` directly (twice, with non-monotonic
insertion order) rather than assumed either way.

**Auto-start monitoring on open** (PR #22): the Timeline work's own
verification surfaced the question that prompted this — "why does
Last 24h say no data?" — because monitoring had been off since
2026-08-12 with nothing to notice or fix it. `MonitoringService.isEnabledByUser`
(persisted user intent from issue #1's fix, default true) was
previously only acted on by `BootReceiver`, i.e. only at device boot;
anything else that stops the service without an explicit Stop tap (a
reinstall force-stops the process with no reboot involved; Android
killing it) left monitoring off until manually restarted.
`MainActivity.onCreate()` now checks intent + actual running state on
every open and restarts if they've drifted apart. Four bot-review
rounds on the PR, all on code correctness/consistency in the fix
itself (no prompting for notification permission on a silent
auto-restart; a defensive catch around the foreground-service start,
using `IllegalStateException` rather than the API-31-only exception
class since this app's `minSdk` is 30; narrowing that catch to not
also swallow the WorkManager fallback enqueue; two rounds of comment-
accuracy chasing after the code changed) — a good illustration of the
bot catching real drift between code and its own explanatory comments
across incremental edits, not just logic bugs.

## Next

- `myhourly:StepCounterWakeLock` investigation — issue #20, third-party
  app, not blocking any Introspect work.
- Re-check Doze engagement / drain rate on a genuinely quiet
  (non-dev-session) day to separate the real signal from this week's
  confound before drawing conclusions for the Samsung thread.
- Consider sending Samsung an update on the Android Auto per-hour
  churn-during-use finding (194–232 events clustered into 4–5hr
  driving windows on 08-11/08-12) — user's call on timing/wording.
- Timeline view's own known gaps, not blocking: `usage_events` has no
  downsampled query variant (unbounded load at "All time" as the
  table grows further); bands aren't pixel-column-aligned with the
  chart's actual plot rect (axis-label insets).
