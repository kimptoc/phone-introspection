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
grant, no separate permission needed; no crashes. Still pending: confirming
the appop-*revoke* path takes T1 back to dark cleanly (tracked as a
follow-up, screen-free, doesn't need to block the PR).

**This install interrupted the running soak test** — same as any
reinstall, it kills the process (`installPackageLI` in `exit_reason`,
consistent with the first entry from 08-05) and the foreground service
needed manually restarting; the ~14-min Samsung boot-restart delay
documented above applies to a full device reboot, not a reinstall, so it
wasn't a factor here. App data (all prior T0 history) survived the
update intact since this was a version upgrade, not an uninstall.

PR opened, not merged — per "PRs going forward."

## Next: after Phase 2 merges

Phase 3 (T2 + provisioning script) per spec §6, once T1 has had some
real runway and the soak-test checklist above is fully closed out.
