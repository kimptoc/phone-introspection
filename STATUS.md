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

## Next: Phase 2 (T1)

UsageStats + NetworkStats collectors, reconciliation logic, and an
onboarding flow that deep-links to the `PACKAGE_USAGE_STATS` appop Settings
screen. Starts after the soak-test review, on a feature branch via PR.
