# Phone Usage & Health Monitor — Technical Spec

**Target:** Samsung S25 Ultra, Android 15/16
**Distribution:** sideloaded, self-signed. Not Play Store.
**Status:** draft v0.1

---

## 1. Goal

A single always-on app that records what the phone is doing over time — which apps are in use, what the thermal and battery picture looks like, when the device slept, rebooted or killed background work — and stores it locally for later inspection.

Emphasis is on **longitudinal data**, not a live dashboard. The interesting questions are retrospective: *why did background collection stop at 3am*, *what was draining the battery on Tuesday*, *does the phone throttle during commutes*.

### Non-goals

- No cloud sync, no account, no network egress at all in v1.
- Not a "phone cleaner" or optimiser. Read-only observation.
- No attempt to work unmodified on arbitrary devices — one phone, known configuration.

---

## 2. Capability tiers

The app is built in tiers. Each tier degrades gracefully to the one below, so a fresh install with zero setup still collects something useful.

| Tier | Unlock cost | What it adds |
|---|---|---|
| **T0** | Nothing | Battery temp/level/current, thermal status & headroom, boot time, doze & power-save state, screen state, own-process CPU & memory, own-process exit reasons |
| **T1** | User taps through Settings | Per-app foreground time and usage events, per-UID network bytes, real-time foreground app, full installed-package list |
| **T2** | One-off `adb` grants | Per-UID battery attribution, full logcat, `@hide` API access via reflection |
| **T3** | Shizuku running | `dumpsys` output — batterystats, cpuinfo, sensorservice, deviceidle. Full process list. |
| **T4** | Root (out of scope v1) | Raw SoC/skin thermal zones, unrestricted `/proc` |

Design rule: **every collector declares its tier**, and the app surfaces which tiers are currently live. A tier going dark (Shizuku died after reboot) must be visible, not silent.

---

## 3. Signal inventory

### T0 — no permissions

| Signal | Source |
|---|---|
| Battery level, status, health, plug type | `ACTION_BATTERY_CHANGED` sticky broadcast |
| Battery temperature (0.1 °C) | `EXTRA_TEMPERATURE` from same |
| Instantaneous current, charge counter, energy counter | `BatteryManager.getIntProperty()` / `getLongProperty()` |
| Thermal status bucket (NONE → CRITICAL) | `PowerManager.getCurrentThermalStatus()` + `addThermalStatusListener()` |
| Thermal headroom, 0–1+ where 1.0 = throttle point | `PowerManager.getThermalHeadroom(seconds)` |
| Boot timestamp | `currentTimeMillis() − elapsedRealtime()` |
| Deep-sleep time | `elapsedRealtime() − uptimeMillis()` |
| Doze / power save / battery-optimisation exemption | `PowerManager.isDeviceIdleMode()`, `isPowerSaveMode()`, `isIgnoringBatteryOptimizations()` |
| Screen on/off, keyguard locked | `ACTION_SCREEN_ON/OFF`, `KeyguardManager` |
| System memory pressure | `ActivityManager.getMemoryInfo()` |
| Own process CPU, own RSS | `Process.getElapsedCpuTime()`, `/proc/self/*` |
| Why our own process last died | `ActivityManager.getHistoricalProcessExitReasons()` |
| Per-CPU current frequency *(best effort)* | `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` |
| Free internal storage (total / available / pct) | `StatFs` on app data volume |

The `cpufreq` read is OEM-dependent and may be SELinux-denied. Treat as optional; never let a failure there break a collection cycle.

Free storage wasn't in the original signal inventory — added after Samsung support flagged low storage headroom (<10% free) as a potential contributor to instability/overheating during the diagnostic exchange. Same rule applies: `StatFs` failures should degrade to a missing sample, not a broken cycle.

### T1 — user-granted via Settings

| Signal | Requires |
|---|---|
| Per-package foreground time, last-used, standby bucket | `PACKAGE_USAGE_STATS` appop → `UsageStatsManager` |
| Usage event stream (ACTIVITY_RESUMED/PAUSED, screen interactive, device shutdown) | same |
| Per-UID mobile & wifi bytes, bucketed | same appop → `NetworkStatsManager` |
| Installed package list with labels and install times | `QUERY_ALL_PACKAGES` |
| Foreground package in real time, low latency | `AccessibilityService` on `TYPE_WINDOW_STATE_CHANGED` |

`UsageStatsManager.queryEvents()` is the primary source of truth for session boundaries — it survives our process dying and can be back-filled on next start. The accessibility service is a *latency optimisation only*; the app must be correct without it.

### T2 — adb-granted

```bash
PKG=uk.example.phonemon
adb shell pm grant $PKG android.permission.BATTERY_STATS
adb shell pm grant $PKG android.permission.READ_LOGS
adb shell pm grant $PKG android.permission.DUMP
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
adb shell settings put global hidden_api_policy 1
```

- `BATTERY_STATS` → `BatteryStatsManager`, per-UID battery consumption attribution.
- `READ_LOGS` → logcat as a side channel on system behaviour (thermal daemon, LMK, JobScheduler decisions).
- `WRITE_SECURE_SETTINGS` → sets `hidden_api_policy`, which in turn unblocks reflective access to `@hide` APIs.

**`BatteryStatsManager` is not actually a two-line integration.** `BatteryStatsManager`, `BatteryUsageStats` and `UidBatteryConsumer` are all `@SystemApi` — confirmed absent from the public SDK stub jars for API 33 through 36, so there is nothing to compile against directly. Reaching them means reflecting into hidden framework classes via `context.getSystemService("batterystats")` and `Class.getMethod(...)`, and that reflection is itself blocked by Android's hidden-API enforcement unless `hidden_api_policy` is relaxed. In other words, the `BATTERY_STATS` bullet above and the `hidden_api_policy` bullet below aren't independent unlocks for this signal — you need both together, or `getUidBatteryConsumers()` throws/returns nothing even with the permission granted. `WRITE_SECURE_SETTINGS` does *not* need to be granted to the app itself for this: `adb shell settings put global hidden_api_policy 1` runs as shell, which already holds that permission, and it's a device-global setting, not one scoped to the app's own package.

**These grants survive reboot but are wiped on reinstall.** Put them in `scripts/provision.sh` and call it from the install task, or you will silently lose T2 on the next build.

### T3 — Shizuku

Shizuku runs a service as shell UID (2000), started over wireless debugging, and brokers system API calls. No root. On Android 11+ it can self-start without a laptop after each reboot.

Gives us shell-level `dumpsys`:

| Command | Yields |
|---|---|
| `dumpsys batterystats` | Historical per-UID wakelocks, CPU time, sensor time |
| `dumpsys cpuinfo` | System-wide CPU load by process |
| `dumpsys sensorservice` | Registered sensor clients, batching/FIFO state, sampling rates |
| `dumpsys deviceidle` | Doze state machine, whitelist, step transitions |
| `dumpsys power` | Wakelock holders |
| `ps -A` | Real process list |

Output is unstructured text and changes between Android versions. **Wrap every parser in try/catch, store the raw text alongside the parsed result**, and treat a parse failure as a missing sample rather than an error.

---

## 4. Collection strategy

Three cadences:

**Event-driven (cheap, always on)**
Registered receivers/listeners in a foreground service: battery change, thermal status change, screen on/off, doze transitions. Write on change, not on poll.

**Periodic sample (every 60 s while screen on, every 15 min while dozing)**
Thermal headroom, battery current, memory info, cpufreq, own-process stats. Backed by the foreground service while awake; `WorkManager` periodic work is the fallback when the service is killed.

**Batch reconciliation (every 30 min and on every app start)**
Query `UsageStatsManager` and `NetworkStatsManager` for the window since last successful reconciliation. This is what makes the dataset gap-free across process death — the system kept the records even when we weren't running.

Doze will suppress the middle tier. That is fine and is itself a signal — record doze entry/exit and let the gaps in the data be interpretable rather than mysterious.

---

## 5. Architecture

```
┌─────────────────────────────────────────────┐
│  Foreground Service (persistent notif)      │
│    ├── EventCollectors  (broadcast/listener)│
│    └── SamplingLoop     (coroutine ticker)  │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  CollectorRegistry                          │
│    each collector: id, tier, isAvailable(), │
│    collect() → List<Sample>                 │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  Room database (WAL, on internal storage)   │
│    samples │ events │ sessions │ raw_dumps  │
└──────────────────┬──────────────────────────┘
                   │
      ┌────────────┴────────────┐
      │                         │
┌─────▼──────┐          ┌───────▼────────┐
│ UI (charts)│          │ CSV/SQLite     │
│            │          │ export to SAF  │
└────────────┘          └────────────────┘
```

**Collector interface.** Every signal is a `Collector` with a declared tier and an `isAvailable()` probe run at startup and after any permission change. The registry composes whatever is available. Adding root support later means adding collectors, not restructuring.

**Storage.** Room, with a generic `samples` table (`timestamp`, `collector_id`, `key`, `value_num`, `value_text`) plus purpose-built tables for usage sessions. Generic table keeps schema churn low while the signal set is still moving. Retention: 90 days rolling, configurable, with a hard size cap.

**Export.** Storage Access Framework, CSV per table plus raw SQLite copy. No network permission in the manifest at all — makes the privacy story trivially auditable and removes a whole class of accidental egress.

**Resilience.** `START_STICKY` foreground service, `WorkManager` watchdog that restarts it, `RECEIVE_BOOT_COMPLETED` for restart after reboot, and `ApplicationExitInfo` logged on every start so process deaths are self-documenting. Samsung's aggressive background management is the main adversary here — the app should prompt once for battery-optimisation exemption and warn if it's revoked.

---

## 6. Build phases

**Phase 1 — T0 skeleton.** Foreground service, Room, battery + thermal + boot + doze collectors, CSV export. Ship this and let it run a week before adding anything; it will surface the real background-survival problems on this specific phone.

**Phase 2 — T1.** UsageStats and NetworkStats collectors with reconciliation logic. Onboarding flow that deep-links to the Settings screens. This is where the app becomes genuinely useful.

**Phase 3 — T2 + provisioning script.** BatteryStatsManager, logcat tail collector with filtering. Provision script wired into the Gradle install task.

**Phase 4 — T3 Shizuku.** Dumpsys collectors, starting with `sensorservice` and `batterystats`. Raw-text-plus-parsed storage from the start.

**Phase 5 — UI.** Timeline view aligning app sessions against thermal state and battery drain. Until then, export and analyse externally.

---

## 7. Known risks

- **Dumpsys parsing is brittle** across Android versions and One UI updates. Mitigated by storing raw output.
- **Samsung background restrictions** will kill the service. Mitigated by reconciliation-on-start rather than assuming continuous uptime.
- **Accessibility service** is a large privilege for a latency optimisation. Consider deferring it or making it opt-in; the app must not depend on it.
- **adb grants lost on reinstall** — the single most likely cause of "why did T2 data stop". Automate, and detect-and-warn at startup.
- **Storage growth** from logcat and dumpsys raw text is the real space consumer. Cap aggressively, compress raw dumps.

---

## 8. Adjacent use

The `dumpsys sensorservice` collector is directly applicable to debugging step-counter behaviour: it exposes whether the step sensor is batching, its FIFO depth, and which clients are registered — which is usually where hourly step counting goes wrong. Worth prioritising within Phase 4 if that's a live problem.
