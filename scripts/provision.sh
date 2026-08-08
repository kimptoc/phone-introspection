#!/usr/bin/env bash
# T2 provisioning (spec §3): one-off adb grants this app can't get from
# Settings. These survive reboot but are wiped on reinstall — rerun this
# after every `./gradlew installDebug` (or wire it into that task).
set -euo pipefail

PKG="net.kimptoc.introspect"

# BatteryStatsManager per-UID attribution (BatteryAttributionCollector).
adb shell pm grant "$PKG" android.permission.BATTERY_STATS

# Logcat as a side channel on system behaviour (LogcatCollector). Without
# this, `logcat` only returns this app's own output, not other processes'.
adb shell pm grant "$PKG" android.permission.READ_LOGS

# BatteryStatsManager/BatteryUsageStats/UidBatteryConsumer are @SystemApi —
# not in the public SDK — so BatteryAttributionCollector reaches them via
# reflection. Reflection into hidden framework classes is blocked by
# Android's hidden-API enforcement unless it's relaxed device-wide.
# hidden_api_policy=1 is "warn, don't block". This is a global device
# setting, not scoped to $PKG.
adb shell settings put global hidden_api_policy 1

echo "Provisioned $PKG for T2 (BatteryStatsManager)."
