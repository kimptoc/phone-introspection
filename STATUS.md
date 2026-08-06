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

## Next: after Phase 2 merges

Phase 3 (T2 + provisioning script) per spec §6, once T1 has had some
real runway and the soak-test checklist above is fully closed out.
