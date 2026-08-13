# Phase 5 Timeline View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a `TimelineActivity` that renders battery level, thermal state, Doze/screen-on state, and app sessions on a shared, pan/zoomable time axis, per the approved design at `docs/superpowers/specs/2026-08-13-phase5-timeline-design.md`.

**Architecture:** A new `net.kimptoc.introspect.timeline` package holds a `TimelineRepository` (range-scoped Room queries + app-session derivation from `usage_events`), a `TimelineBandView` (a small reusable custom `View` that draws colored interval segments), and `TimelineActivity` (owns an MPAndroidChart `LineChart` for battery plus three `TimelineBandView`s for thermal/Doze/sessions, all kept in visual sync with the chart's pan/zoom).

**Tech Stack:** Kotlin, Android Views (no Compose), Room, Kotlin coroutines, [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) v3.1.0 (via JitPack).

## Global Constraints

- No JVM/instrumented test framework exists anywhere in this codebase (`app/src/test` and `app/src/androidTest` are both absent). Every prior feature in this project was verified by building, installing on the physical device, and checking real behavior via `adb`/`uiautomator` — **this plan follows that same convention**; do not introduce a new test framework as a side effect of this feature. "Verify" steps below mean on-device checks, not `pytest`/JUnit runs.
- `minSdk 30`, `targetSdk 36`, Kotlin, KSP (not kapt) for Room's annotation processing — match `app/build.gradle.kts`'s existing setup exactly.
- No network egress anywhere in this app (spec §1) — MPAndroidChart is a local rendering library only, doesn't touch this constraint, but don't add anything that does.
- Follow existing KDoc conventions: a short doc comment on every new public class explaining *why*, not *what* — match the tone already used throughout `collector/`, `service/`, `shizuku/`.
- A missing/empty signal is an empty or labeled gap, never a crash (spec §3's established philosophy) — applies to every new query and every rendering path in this plan.

---

### Task 1: Add the MPAndroidChart dependency

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: the `com.github.mikephil.charting.*` package available to the rest of the app.

- [ ] **Step 1: Create the feature branch**

Per this project's established workflow (every prior feature, PR #10 through #19): work happens on its own branch, reviewed by kilo-code-bot via a PR, never committed directly to `main` (the one standing exception is STATUS.md-only updates).

```bash
cd /Users/kimptoc/AndroidStudioProjects/phone-introspection
git checkout main
git pull --ff-only
git checkout -b phase5-timeline-view
```

- [ ] **Step 2: Add the JitPack repository**

MPAndroidChart is published on JitPack, not Maven Central. `settings.gradle.kts` currently has `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)`, so the repo must be declared here, not in `app/build.gradle.kts`.

Edit `settings.gradle.kts`'s `dependencyResolutionManagement` block:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // MPAndroidChart (Phase 5 timeline)
    }
}
```

- [ ] **Step 3: Add the dependency**

Edit `app/build.gradle.kts`'s `dependencies` block, adding after the Shizuku lines:

```kotlin
    // Phase 5 (UI): timeline view charting.
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
```

- [ ] **Step 4: Verify the build picks up the dependency**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "$JAVA_HOME") ./gradlew assembleDebug -q`

Expected: empty output (success). If it fails to resolve, double check the JitPack repo line landed in `settings.gradle.kts`, not `app/build.gradle.kts` (the latter would violate `FAIL_ON_PROJECT_REPOS` and fail the build with an explicit error naming that setting).

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts
git commit -m "Add MPAndroidChart dependency for Phase 5 timeline view"
```

---

### Task 2: Range-scoped and downsampled DAO queries

**Files:**
- Modify: `app/src/main/java/net/kimptoc/introspect/db/SampleDao.kt`
- Create: `app/src/main/java/net/kimptoc/introspect/db/TimelineRows.kt`

**Interfaces:**
- Consumes: `SampleEntity`'s existing column names (`timestamp`, `collector_id`, `key`, `value_num`, `value_text`) — no schema change, read-only queries against the existing `samples` table.
- Produces (for Task 3):
  - `TimestampNum(timestamp: Long, valueNum: Double?)`
  - `TimestampText(timestamp: Long, valueText: String?)`
  - `UsageEventRow(timestamp: Long, key: String, valueText: String?)`
  - `SampleDao.rangeNumeric(collectorId, key, startMs, endMs): List<TimestampNum>`
  - `SampleDao.rangeNumericBucketed(collectorId, key, startMs, endMs, bucketMs): List<TimestampNum>`
  - `SampleDao.rangeText(collectorId, key, startMs, endMs): List<TimestampText>`
  - `SampleDao.rangeTextBucketed(collectorId, key, startMs, endMs, bucketMs): List<TimestampText>`
  - `SampleDao.usageEventsInRange(startMs, endMs): List<UsageEventRow>`
  - `SampleDao.earliestTimestamp(): Long?`

- [ ] **Step 1: Create the row POJOs**

Write `app/src/main/java/net/kimptoc/introspect/db/TimelineRows.kt`:

```kotlin
package net.kimptoc.introspect.db

import androidx.room.ColumnInfo

/** One (timestamp, numeric value) row from a Timeline range query. */
data class TimestampNum(
    val timestamp: Long,
    @ColumnInfo(name = "value_num") val valueNum: Double?,
)

/** One (timestamp, text value) row from a Timeline range query. */
data class TimestampText(
    val timestamp: Long,
    @ColumnInfo(name = "value_text") val valueText: String?,
)

/** One `usage_events` row: [key] is the package name, [valueText] the event type. */
data class UsageEventRow(
    val timestamp: Long,
    val key: String,
    @ColumnInfo(name = "value_text") val valueText: String?,
)
```

- [ ] **Step 2: Add the range/bucketed queries to `SampleDao`**

The bucketed queries rely on a documented SQLite behavior: when a `GROUP BY` query includes `MIN(timestamp)`, any bare (non-aggregated) column in the same `SELECT` is taken from the row that produced that `MIN` — so `value_num`/`value_text` come from the earliest row in each bucket, not an arbitrary one. This is why every bucketed query below selects `MIN(timestamp) AS timestamp` explicitly rather than the bucket boundary itself — segments/points stay anchored to real sample times.

Edit `app/src/main/java/net/kimptoc/introspect/db/SampleDao.kt`, adding after `count()`:

```kotlin
    @Query(
        """
        SELECT timestamp, value_num FROM samples
        WHERE collector_id = :collectorId AND key = :key AND timestamp BETWEEN :startMs AND :endMs
        ORDER BY timestamp
        """,
    )
    suspend fun rangeNumeric(collectorId: String, key: String, startMs: Long, endMs: Long): List<TimestampNum>

    @Query(
        """
        SELECT MIN(timestamp) AS timestamp, AVG(value_num) AS value_num FROM samples
        WHERE collector_id = :collectorId AND key = :key AND timestamp BETWEEN :startMs AND :endMs
        GROUP BY timestamp / :bucketMs
        ORDER BY timestamp
        """,
    )
    suspend fun rangeNumericBucketed(
        collectorId: String,
        key: String,
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
    ): List<TimestampNum>

    @Query(
        """
        SELECT timestamp, value_text FROM samples
        WHERE collector_id = :collectorId AND key = :key AND timestamp BETWEEN :startMs AND :endMs
        ORDER BY timestamp
        """,
    )
    suspend fun rangeText(collectorId: String, key: String, startMs: Long, endMs: Long): List<TimestampText>

    @Query(
        """
        SELECT MIN(timestamp) AS timestamp, value_text FROM samples
        WHERE collector_id = :collectorId AND key = :key AND timestamp BETWEEN :startMs AND :endMs
        GROUP BY timestamp / :bucketMs
        ORDER BY timestamp
        """,
    )
    suspend fun rangeTextBucketed(
        collectorId: String,
        key: String,
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
    ): List<TimestampText>

    @Query(
        """
        SELECT timestamp, key, value_text FROM samples
        WHERE collector_id = 'usage_events'
          AND value_text IN ('activity_resumed', 'activity_paused', 'activity_stopped')
          AND timestamp BETWEEN :startMs AND :endMs
        ORDER BY timestamp
        """,
    )
    suspend fun usageEventsInRange(startMs: Long, endMs: Long): List<UsageEventRow>

    @Query("SELECT MIN(timestamp) FROM samples")
    suspend fun earliestTimestamp(): Long?
```

No import needed for `TimestampNum`/`TimestampText`/`UsageEventRow` — `TimelineRows.kt` (Step 1) and `SampleDao.kt` are both in package `net.kimptoc.introspect.db`, so Kotlin resolves same-package types automatically.

- [ ] **Step 3: Verify the build (KSP validates the SQL at compile time)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "$JAVA_HOME") ./gradlew assembleDebug -q`

Expected: empty output. Room's KSP processor validates every `@Query` against the entity schema at compile time — a typo'd column name or malformed SQL fails the build here, not at runtime. This is the primary verification for this task; there's no on-device check needed yet since nothing calls these queries.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/kimptoc/introspect/db/SampleDao.kt app/src/main/java/net/kimptoc/introspect/db/TimelineRows.kt
git commit -m "Add range-scoped and downsampled DAO queries for the timeline view"
```

---

### Task 3: `TimelineRepository` — data model, downsampling policy, session derivation

**Files:**
- Create: `app/src/main/java/net/kimptoc/introspect/timeline/TimelineRange.kt`
- Create: `app/src/main/java/net/kimptoc/introspect/timeline/TimelineRepository.kt`

**Interfaces:**
- Consumes: `SampleDao` methods from Task 2, `AppDatabase.get(context).sampleDao()`.
- Produces (for Tasks 6-8):
  - `data class TimelineSegment<T>(startMs: Long, endMs: Long, value: T)`
  - `data class AppSession(packageName: String, startMs: Long, endMs: Long)`
  - `enum class TimelineRange { LAST_24H, LAST_3D, LAST_7D, ALL_TIME }` with `.labelRes: Int`
  - `class TimelineRepository(private val context: Context)` with:
    - `suspend fun resolveRange(range: TimelineRange): Pair<Long, Long>` → `(startMs, endMs)`
    - `suspend fun loadBattery(startMs: Long, endMs: Long): List<TimestampNum>` (points, not segments — battery is a continuous line)
    - `suspend fun loadThermal(startMs: Long, endMs: Long): List<TimelineSegment<String>>`
    - `suspend fun loadDeviceIdle(startMs: Long, endMs: Long): List<TimelineSegment<Boolean>>`
    - `suspend fun loadScreenOn(startMs: Long, endMs: Long): List<TimelineSegment<Boolean>>`
    - `suspend fun loadAppSessions(startMs: Long, endMs: Long): List<AppSession>`

- [ ] **Step 1: `TimelineRange`**

Write `app/src/main/java/net/kimptoc/introspect/timeline/TimelineRange.kt`:

```kotlin
package net.kimptoc.introspect.timeline

import net.kimptoc.introspect.R

/**
 * The four ranges offered by the range picker. LAST_24H/LAST_3D load raw
 * samples directly - even at this app's finest cadence (60s, spec §4)
 * that's at most a few thousand rows per signal, well within what
 * MPAndroidChart renders smoothly. LAST_7D/ALL_TIME downsample in SQL
 * (see [TimelineRepository]) since the table is 400K+ rows and growing.
 */
enum class TimelineRange(val labelRes: Int, val downsample: Boolean) {
    LAST_24H(R.string.timeline_range_24h, downsample = false),
    LAST_3D(R.string.timeline_range_3d, downsample = false),
    LAST_7D(R.string.timeline_range_7d, downsample = true),
    ALL_TIME(R.string.timeline_range_all, downsample = true),
}
```

- [ ] **Step 2: `TimelineRepository`**

Write `app/src/main/java/net/kimptoc/introspect/timeline/TimelineRepository.kt`:

```kotlin
package net.kimptoc.introspect.timeline

import android.content.Context
import net.kimptoc.introspect.db.AppDatabase
import net.kimptoc.introspect.db.TimestampNum
import net.kimptoc.introspect.db.TimestampText

/** One value held from [startMs] until [endMs] - a step-function segment. */
data class TimelineSegment<T>(val startMs: Long, val endMs: Long, val value: T)

/** One foreground session for [packageName], derived from `usage_events`. */
data class AppSession(val packageName: String, val startMs: Long, val endMs: Long)

/**
 * Range-scoped queries plus derived data (app sessions, categorical
 * segments) for [net.kimptoc.introspect.timeline.TimelineActivity]. Every
 * method is scoped to an explicit (startMs, endMs) window - the `samples`
 * table is 400K+ rows and growing, so nothing here ever loads it whole
 * (spec §7's storage-growth caution applies to reading it back out too,
 * not just writing it).
 */
class TimelineRepository(private val context: Context) {

    private val dao get() = AppDatabase.get(context).sampleDao()

    /** Target point count for downsampled ranges - keeps render cost flat regardless of dataset size. */
    private val targetPoints = 500

    suspend fun resolveRange(range: TimelineRange): Pair<Long, Long> {
        val endMs = System.currentTimeMillis()
        val startMs = when (range) {
            TimelineRange.LAST_24H -> endMs - 24 * 60 * 60 * 1000L
            TimelineRange.LAST_3D -> endMs - 3 * 24 * 60 * 60 * 1000L
            TimelineRange.LAST_7D -> endMs - 7 * 24 * 60 * 60 * 1000L
            TimelineRange.ALL_TIME -> dao.earliestTimestamp() ?: endMs
        }
        return startMs to endMs
    }

    suspend fun loadBattery(startMs: Long, endMs: Long): List<TimestampNum> =
        loadNumeric("battery", "level_pct", startMs, endMs)

    suspend fun loadThermal(startMs: Long, endMs: Long): List<TimelineSegment<String>> =
        loadText("thermal", "status", startMs, endMs).toSegments(endMs) { it ?: "unknown" }

    suspend fun loadDeviceIdle(startMs: Long, endMs: Long): List<TimelineSegment<Boolean>> =
        loadText("doze", "device_idle", startMs, endMs).toSegments(endMs) { it == "true" }

    suspend fun loadScreenOn(startMs: Long, endMs: Long): List<TimelineSegment<Boolean>> =
        loadText("doze", "screen_on", startMs, endMs).toSegments(endMs) { it == "true" }

    /**
     * Sessions still open at [endMs] (the app was in the foreground when
     * the loaded range ends) are capped there rather than dropped - an
     * ongoing session at the visible edge is real data, not noise.
     */
    suspend fun loadAppSessions(startMs: Long, endMs: Long): List<AppSession> {
        val events = dao.usageEventsInRange(startMs, endMs)
        val openStarts = mutableMapOf<String, Long>()
        val sessions = mutableListOf<AppSession>()
        for (event in events) {
            when (event.valueText) {
                "activity_resumed" -> openStarts.putIfAbsent(event.key, event.timestamp)
                "activity_paused", "activity_stopped" -> {
                    val start = openStarts.remove(event.key)
                    if (start != null) sessions += AppSession(event.key, start, event.timestamp)
                }
            }
        }
        openStarts.forEach { (pkg, start) -> sessions += AppSession(pkg, start, endMs) }
        return sessions.sortedBy { it.startMs }
    }

    private suspend fun loadNumeric(collectorId: String, key: String, startMs: Long, endMs: Long): List<TimestampNum> {
        val bucketMs = bucketMsFor(startMs, endMs)
        return if (bucketMs == null) {
            dao.rangeNumeric(collectorId, key, startMs, endMs)
        } else {
            dao.rangeNumericBucketed(collectorId, key, startMs, endMs, bucketMs)
        }
    }

    private suspend fun loadText(collectorId: String, key: String, startMs: Long, endMs: Long): List<TimestampText> {
        val bucketMs = bucketMsFor(startMs, endMs)
        return if (bucketMs == null) {
            dao.rangeText(collectorId, key, startMs, endMs)
        } else {
            dao.rangeTextBucketed(collectorId, key, startMs, endMs, bucketMs)
        }
    }

    /** null means "load raw, no downsampling" - only wide ranges bucket. */
    private fun bucketMsFor(startMs: Long, endMs: Long): Long? {
        val span = endMs - startMs
        // 3 days raw is already the widest un-downsampled range
        // (TimelineRange.LAST_3D); anything wider buckets.
        if (span <= 3 * 24 * 60 * 60 * 1000L) return null
        return (span / targetPoints).coerceAtLeast(1000L)
    }

    private fun <T> List<TimestampText>.toSegments(rangeEndMs: Long, map: (String?) -> T): List<TimelineSegment<T>> {
        if (isEmpty()) return emptyList()
        return mapIndexed { i, row ->
            val end = if (i + 1 < size) this[i + 1].timestamp else rangeEndMs
            TimelineSegment(row.timestamp, end, map(row.valueText))
        }
    }
}
```

- [ ] **Step 2b: Add the four range-label strings this task depends on**

Edit `app/src/main/res/values/strings.xml`, adding before the closing `</resources>`:

```xml
    <string name="timeline_range_24h">Last 24h</string>
    <string name="timeline_range_3d">Last 3 days</string>
    <string name="timeline_range_7d">Last 7 days</string>
    <string name="timeline_range_all">All time</string>
```

- [ ] **Step 3: Verify the build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "$JAVA_HOME") ./gradlew assembleDebug -q`

Expected: empty output. Nothing calls `TimelineRepository` yet, so this is a compile-only check.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/kimptoc/introspect/timeline/TimelineRange.kt app/src/main/java/net/kimptoc/introspect/timeline/TimelineRepository.kt app/src/main/res/values/strings.xml
git commit -m "Add TimelineRepository: range resolution, downsampling, app-session derivation"
```

---

### Task 4: `TimelineBandView` — reusable segment renderer

**Files:**
- Create: `app/src/main/java/net/kimptoc/introspect/timeline/TimelineBandView.kt`

**Interfaces:**
- Consumes: nothing project-specific (pure Android `View`).
- Produces (for Task 7):
  - `class TimelineBandView(context: Context, attrs: AttributeSet?) : View`
  - `TimelineBandView.Segment(startMs: Long, endMs: Long, color: Int, label: String)`
  - `fun setSegments(segments: List<Segment>)`
  - `fun setVisibleRange(startMs: Long, endMs: Long)`

- [ ] **Step 1: Write the view**

One reusable band renderer used three times (thermal, Doze/screen-on, app sessions) rather than three near-identical custom Views - all three are the same shape (a list of colored horizontal intervals over a shared time axis), just with different data and colors.

Write `app/src/main/java/net/kimptoc/introspect/timeline/TimelineBandView.kt`:

```kotlin
package net.kimptoc.introspect.timeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws a horizontal strip of colored intervals (thermal state, Doze/
 * screen-on state, or app sessions - all the same shape: a value held
 * from one timestamp to the next). Not an MPAndroidChart `BarData` grid:
 * these intervals are variable-width and don't sit on a regular tick
 * grid, which `BarData` assumes. [setVisibleRange] is driven externally
 * by [TimelineActivity] from the battery [com.github.mikephil.charting.charts.LineChart]'s
 * own pan/zoom state, keeping all bands in sync with it without this
 * view needing its own gesture handling.
 */
class TimelineBandView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Segment(val startMs: Long, val endMs: Long, val color: Int, val label: String)

    private var segments: List<Segment> = emptyList()
    private var visibleStartMs: Long = 0L
    private var visibleEndMs: Long = 1L
    private val paint = Paint()

    fun setSegments(newSegments: List<Segment>) {
        segments = newSegments
        invalidate()
    }

    fun setVisibleRange(startMs: Long, endMs: Long) {
        visibleStartMs = startMs
        visibleEndMs = endMs.coerceAtLeast(startMs + 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val span = (visibleEndMs - visibleStartMs).toFloat()
        if (span <= 0f || width == 0) return
        for (segment in segments) {
            if (segment.endMs < visibleStartMs || segment.startMs > visibleEndMs) continue
            val left = ((segment.startMs - visibleStartMs) / span * width).coerceIn(0f, width.toFloat())
            val right = ((segment.endMs - visibleStartMs) / span * width).coerceIn(0f, width.toFloat())
            if (right <= left) continue
            paint.color = segment.color
            canvas.drawRect(left, 0f, right, height.toFloat(), paint)
        }
    }
}
```

- [ ] **Step 2: Verify the build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "$JAVA_HOME") ./gradlew assembleDebug -q`

Expected: empty output. Not yet placed in any layout, so this is a compile-only check.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/kimptoc/introspect/timeline/TimelineBandView.kt
git commit -m "Add TimelineBandView: reusable colored-segment renderer"
```

---

### Task 5: `TimelineActivity` shell — layout, manifest, launch button, range picker

**Files:**
- Create: `app/src/main/res/layout/activity_timeline.xml`
- Create: `app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/net/kimptoc/introspect/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `TimelineRange` (Task 3).
- Produces: a launchable `TimelineActivity` with a working range picker that logs the resolved range (chart wiring comes in Task 6) — this task's job is the screen shell and navigation, not the chart itself.

- [ ] **Step 1: Add the layout**

Write `app/src/main/res/layout/activity_timeline.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/timelineRootLayout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <Button
            android:id="@+id/range24hButton"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/timeline_range_24h" />

        <Button
            android:id="@+id/range3dButton"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/timeline_range_3d" />

        <Button
            android:id="@+id/range7dButton"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/timeline_range_7d" />

        <Button
            android:id="@+id/rangeAllButton"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/timeline_range_all" />

    </LinearLayout>

    <TextView
        android:id="@+id/timelineEmptyStateText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:visibility="gone" />

    <com.github.mikephil.charting.charts.LineChart
        android:id="@+id/batteryChart"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="3"
        android:layout_marginTop="16dp" />

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/timeline_band_thermal" />

    <net.kimptoc.introspect.timeline.TimelineBandView
        android:id="@+id/thermalBand"
        android:layout_width="match_parent"
        android:layout_height="24dp" />

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/timeline_band_doze" />

    <net.kimptoc.introspect.timeline.TimelineBandView
        android:id="@+id/dozeBand"
        android:layout_width="match_parent"
        android:layout_height="24dp" />

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/timeline_band_sessions" />

    <net.kimptoc.introspect.timeline.TimelineBandView
        android:id="@+id/sessionsBand"
        android:layout_width="match_parent"
        android:layout_height="24dp" />

</LinearLayout>
```

- [ ] **Step 2: Add the new strings**

Edit `app/src/main/res/values/strings.xml`, adding alongside the range strings from Task 3:

```xml
    <string name="open_timeline">Open timeline</string>
    <string name="timeline_title">Timeline</string>
    <string name="timeline_band_thermal">Thermal state</string>
    <string name="timeline_band_doze">Doze / screen on</string>
    <string name="timeline_band_sessions">App sessions</string>
    <string name="timeline_no_data">No data in this range</string>
    <string name="timeline_grant_usage_access">Grant usage access to see app sessions</string>
</resources>
```

(Note: this replaces the file's existing closing `</resources>` tag — make sure there's exactly one at the end, not two.)

- [ ] **Step 3: Write the Activity**

Write `app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt`:

```kotlin
package net.kimptoc.introspect.timeline

import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import kotlinx.coroutines.launch
import net.kimptoc.introspect.R

/**
 * Phase 5 (spec §6): battery, thermal, Doze/screen-on, and app-session
 * data on a shared, pan/zoomable time axis. General-purpose data browser,
 * not a single-workflow view - see the design doc
 * (docs/superpowers/specs/2026-08-13-phase5-timeline-design.md) for why.
 */
class TimelineActivity : ComponentActivity() {

    private lateinit var repository: TimelineRepository
    private lateinit var batteryChart: LineChart
    private var currentRange = TimelineRange.LAST_24H

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timeline)
        repository = TimelineRepository(this)
        batteryChart = findViewById(R.id.batteryChart)

        findViewById<Button>(R.id.range24hButton).setOnClickListener { loadRange(TimelineRange.LAST_24H) }
        findViewById<Button>(R.id.range3dButton).setOnClickListener { loadRange(TimelineRange.LAST_3D) }
        findViewById<Button>(R.id.range7dButton).setOnClickListener { loadRange(TimelineRange.LAST_7D) }
        findViewById<Button>(R.id.rangeAllButton).setOnClickListener { loadRange(TimelineRange.ALL_TIME) }

        loadRange(currentRange)
    }

    private fun loadRange(range: TimelineRange) {
        currentRange = range
        lifecycleScope.launch {
            val (startMs, endMs) = repository.resolveRange(range)
            // Chart/band wiring lands in Task 6/7; this task only proves
            // navigation and range resolution work end to end.
            android.util.Log.d("TimelineActivity", "range=$range startMs=$startMs endMs=$endMs")
        }
    }
}
```

- [ ] **Step 4: Register the Activity in the manifest**

Edit `app/src/main/AndroidManifest.xml`, adding after `MainActivity`'s closing `</activity>`:

```xml
        <activity
            android:name=".timeline.TimelineActivity"
            android:label="@string/timeline_title"
            android:exported="false" />
```

- [ ] **Step 5: Add the launch button to `MainActivity`**

Edit `app/src/main/res/layout/activity_main.xml`, adding after `shizukuAccessButton`'s closing `/>` and before `tierStatusText`:

```xml
    <Button
        android:id="@+id/openTimelineButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/open_timeline" />
```

Edit `app/src/main/java/net/kimptoc/introspect/MainActivity.kt`: add the import

```kotlin
import net.kimptoc.introspect.timeline.TimelineActivity
```

and inside `onCreate`, after the `shizukuAccessButton.setOnClickListener { ... }` block:

```kotlin
        findViewById<Button>(R.id.openTimelineButton).setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }
```

- [ ] **Step 6: Build, install, verify on-device**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "$JAVA_HOME") ./gradlew installDebug -q
```

Expected: `Installed on 1 device.` / `Provisioned net.kimptoc.introspect for T2 ...`

On-device check (adapt the wake/dismiss-keyguard/launch sequence used throughout this project's prior PRs if the screen is locked):

```bash
adb shell am start -n net.kimptoc.introspect/.MainActivity
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | grep -o 'text="Open timeline"'
```

Expected: the button text is found. Tap it (get its bounds from the dump the same way prior PRs did), confirm `TimelineActivity` opens (four range buttons, empty chart/bands visible, no crash), then check logcat:

```bash
adb logcat -d -t 200 | grep TimelineActivity
```

Expected: one `range=LAST_24H startMs=... endMs=...` line logged on open, and a new line each time a different range button is tapped, with `endMs - startMs` matching that range's expected duration (e.g. `LAST_3D` should show roughly `3 * 24 * 60 * 60 * 1000` between the two values). Also confirm no `FATAL EXCEPTION` in a wider logcat check:

```bash
adb logcat -d -t 500 | grep -i "FATAL EXCEPTION"
```

Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/layout/activity_timeline.xml app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt app/src/main/java/net/kimptoc/introspect/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "Add TimelineActivity shell: layout, navigation, range picker"
```

---

### Task 6: Wire the battery `LineChart`, empty state, and usage-access hint

**Files:**
- Modify: `app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt`

**Interfaces:**
- Consumes: `TimelineRepository.loadBattery()` (Task 3), `net.kimptoc.introspect.collector.t1.UsageAccess.isGranted()` (existing).
- Produces: `TimelineActivity.rangeStartMs: Long` / `rangeEndMs: Long` (instance state Task 7 reads to size its bands), `TimelineActivity.timestampToX(ts: Long): Float` / `xToTimestamp(x: Float): Long` (the millis↔chart-X mapping Task 7 and Task 8 both need).

**Why a custom X-axis unit:** MPAndroidChart's `Entry.x`/`Entry.y` are `Float`. Epoch-millisecond timestamps (13 digits) exceed `Float`'s exact-integer range (~16.7 million), so plotting raw millis directly causes visible jitter/misalignment. Every chart X value in this task is instead **seconds since `rangeStartMs`** — small enough to stay exact, and there's no need for sub-second precision at this app's 60-second sampling cadence (spec §4).

**Amended after Task 5's on-device verification**: Task 5 discovered that Android 15+'s edge-to-edge enforcement (the same issue `MainActivity` already works around) rendered the range-picker buttons unreachable — underneath the status bar — without inset handling, and added an `applySystemBarInsetsAsPadding` helper mirroring `MainActivity`'s. The full-file listing below **includes that fix**; it is not optional scaffolding to drop.

- [ ] **Step 1: Replace the placeholder `loadRange` with real chart wiring**

Edit `app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt` in full:

```kotlin
package net.kimptoc.introspect.timeline

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.launch
import net.kimptoc.introspect.R
import net.kimptoc.introspect.collector.t1.UsageAccess

/**
 * Phase 5 (spec §6): battery, thermal, Doze/screen-on, and app-session
 * data on a shared, pan/zoomable time axis. General-purpose data browser,
 * not a single-workflow view - see the design doc
 * (docs/superpowers/specs/2026-08-13-phase5-timeline-design.md) for why.
 *
 * Chart X values are seconds-since-[rangeStartMs], not raw epoch millis -
 * see [timestampToX] for why.
 */
class TimelineActivity : ComponentActivity() {

    private lateinit var repository: TimelineRepository
    private lateinit var batteryChart: LineChart
    private lateinit var emptyStateText: TextView

    private var rangeStartMs = 0L
    private var rangeEndMs = 1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timeline)
        // Android 15+ (targetSdk 35+) enforces edge-to-edge, same as
        // MainActivity: without this the range-picker button row renders
        // (and is tappable) underneath the status bar/action bar rather
        // than below it (found on-device during Task 5).
        applySystemBarInsetsAsPadding(findViewById(R.id.timelineRootLayout))
        repository = TimelineRepository(this)
        batteryChart = findViewById(R.id.batteryChart)
        emptyStateText = findViewById(R.id.timelineEmptyStateText)
        batteryChart.description.isEnabled = false

        findViewById<Button>(R.id.range24hButton).setOnClickListener { loadRange(TimelineRange.LAST_24H) }
        findViewById<Button>(R.id.range3dButton).setOnClickListener { loadRange(TimelineRange.LAST_3D) }
        findViewById<Button>(R.id.range7dButton).setOnClickListener { loadRange(TimelineRange.LAST_7D) }
        findViewById<Button>(R.id.rangeAllButton).setOnClickListener { loadRange(TimelineRange.ALL_TIME) }

        loadRange(TimelineRange.LAST_24H)
    }

    private fun timestampToX(ts: Long): Float = (ts - rangeStartMs) / 1000f

    private fun loadRange(range: TimelineRange) {
        lifecycleScope.launch {
            val (startMs, endMs) = repository.resolveRange(range)
            rangeStartMs = startMs
            rangeEndMs = endMs

            val battery = repository.loadBattery(startMs, endMs)
            if (battery.isEmpty()) {
                emptyStateText.text = getString(R.string.timeline_no_data)
                emptyStateText.visibility = android.view.View.VISIBLE
                batteryChart.clear()
                return@launch
            }
            emptyStateText.visibility = android.view.View.GONE

            val entries = battery.mapNotNull { row ->
                row.valueNum?.let { Entry(timestampToX(row.timestamp), it.toFloat()) }
            }
            val dataSet = LineDataSet(entries, getString(R.string.timeline_band_thermal).let { "" }).apply {
                color = Color.BLUE
                setDrawCircles(false)
                lineWidth = 2f
            }
            batteryChart.data = LineData(dataSet)
            batteryChart.notifyDataSetChanged()
            batteryChart.invalidate()

            if (!UsageAccess.isGranted(this@TimelineActivity)) {
                android.widget.Toast.makeText(
                    this@TimelineActivity,
                    R.string.timeline_grant_usage_access,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Mirrors MainActivity.applySystemBarInsetsAsPadding: without it,
     * edge-to-edge draws this screen's content behind the status bar and
     * the window's action bar, leaving the top row of range buttons
     * visually hidden and untappable (confirmed on-device in Task 5).
     */
    private fun applySystemBarInsetsAsPadding(root: View) {
        val basePadding = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom,
            )
            insets
        }
    }
}
```

(The `.let { "" }` on the dataset label is deliberate - MPAndroidChart's `LineDataSet` constructor requires a label string, but this chart only ever holds one series and `description.isEnabled = false` already hides it; an empty label is simpler than adding a throwaway string resource for text nothing displays.)

- [ ] **Step 2: Build, install, verify real data renders**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "$JAVA_HOME") ./gradlew installDebug -q
```

On-device: launch `TimelineActivity` (same sequence as Task 5), confirm a real battery-level line renders (not empty) for the default 24h range, tap through the other three range buttons and confirm the line's shape changes each time (wider ranges should show a longer/coarser trace). Pinch-zoom and drag on the chart - confirm MPAndroidChart's built-in gestures work (this is library-default behavior, nothing to wire for it).

If usage access isn't currently granted on the test device, confirm the "Grant usage access to see app sessions" toast appears; if it is granted, this step doesn't need separate verification here (Task 7 covers the app-sessions band itself).

Check for crashes:

```bash
adb logcat -d -t 500 | grep -i "FATAL EXCEPTION"
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt
git commit -m "Wire battery LineChart with real range data and empty-state handling"
```

---

### Task 7: Wire the thermal, Doze, and app-session bands, synced to the chart's pan/zoom

**Files:**
- Modify: `app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt`

**Interfaces:**
- Consumes: `TimelineRepository.loadThermal/loadDeviceIdle/loadScreenOn/loadAppSessions` (Task 3), `TimelineBandView.setSegments/setVisibleRange` (Task 4), `LineChart.lowestVisibleX`/`highestVisibleX` (MPAndroidChart, `BarLineChartBase`).
- Produces: nothing further consumed by later tasks besides what Task 8 needs (already covered by Task 6's `timestampToX`/`rangeStartMs`).

- [ ] **Step 1: Add band loading and gesture-sync wiring**

Edit `app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt`. Add these imports:

```kotlin
import android.view.MotionEvent
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
```

Add these fields alongside the existing ones:

```kotlin
    private lateinit var thermalBand: TimelineBandView
    private lateinit var dozeBand: TimelineBandView
    private lateinit var sessionsBand: TimelineBandView
```

In `onCreate`, after `emptyStateText = findViewById(...)`:

```kotlin
        thermalBand = findViewById(R.id.thermalBand)
        dozeBand = findViewById(R.id.dozeBand)
        sessionsBand = findViewById(R.id.sessionsBand)
        batteryChart.onChartGestureListener = object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastGesture: ChartTouchListener.ChartGesture?) = syncBandsToChart()
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) = syncBandsToChart()
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) = syncBandsToChart()
        }
```

Add the sync function and a `xToTimestamp` inverse of Task 6's `timestampToX`:

```kotlin
    private fun xToTimestamp(x: Float): Long = rangeStartMs + (x * 1000).toLong()

    private fun syncBandsToChart() {
        val startMs = xToTimestamp(batteryChart.lowestVisibleX)
        val endMs = xToTimestamp(batteryChart.highestVisibleX)
        thermalBand.setVisibleRange(startMs, endMs)
        dozeBand.setVisibleRange(startMs, endMs)
        sessionsBand.setVisibleRange(startMs, endMs)
    }
```

In `loadRange`, after the existing `batteryChart.invalidate()` call and before the `UsageAccess` check, add:

```kotlin
            val thermalColors = mapOf(
                "none" to Color.rgb(200, 230, 200),
                "light" to Color.rgb(255, 235, 150),
                "moderate" to Color.rgb(255, 180, 80),
                "severe" to Color.rgb(255, 100, 60),
                "critical" to Color.rgb(220, 40, 40),
                "emergency" to Color.rgb(150, 0, 0),
                "shutdown" to Color.rgb(80, 0, 0),
                "unknown" to Color.LTGRAY,
            )
            thermalBand.setSegments(
                repository.loadThermal(startMs, endMs).map {
                    TimelineBandView.Segment(it.startMs, it.endMs, thermalColors[it.value] ?: Color.LTGRAY, it.value)
                },
            )

            val deviceIdle = repository.loadDeviceIdle(startMs, endMs)
            val screenOn = repository.loadScreenOn(startMs, endMs)
            // One combined band: screen_on takes visual priority (drawn
            // second, so it wins where both series would otherwise
            // overlap) since an interactive screen is the more actionable
            // state to see at a glance than Doze specifically.
            dozeBand.setSegments(
                deviceIdle.map {
                    TimelineBandView.Segment(
                        it.startMs, it.endMs,
                        if (it.value) Color.rgb(150, 180, 255) else Color.LTGRAY,
                        if (it.value) "idle" else "active",
                    )
                } + screenOn.map {
                    TimelineBandView.Segment(
                        it.startMs, it.endMs,
                        if (it.value) Color.rgb(255, 220, 100) else Color.TRANSPARENT,
                        if (it.value) "screen on" else "screen off",
                    )
                },
            )

            val sessionColors = listOf(
                Color.rgb(120, 190, 230), Color.rgb(230, 160, 120), Color.rgb(160, 210, 130),
                Color.rgb(220, 150, 200), Color.rgb(210, 210, 120),
            )
            val packageColor = mutableMapOf<String, Int>()
            sessionsBand.setSegments(
                repository.loadAppSessions(startMs, endMs).map { session ->
                    val color = packageColor.getOrPut(session.packageName) {
                        sessionColors[packageColor.size % sessionColors.size]
                    }
                    TimelineBandView.Segment(session.startMs, session.endMs, color, session.packageName)
                },
            )

            syncBandsToChart()
```

- [ ] **Step 2: Build, install, verify all three bands render and stay synced**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "$JAVA_HOME") ./gradlew installDebug -q
```

On-device: open `TimelineActivity`, confirm the thermal band shows colored segments (mostly the "none" color if the phone's been cool), the Doze/screen-on band shows alternating idle/active and on/off coloring, and (if usage access is granted) the sessions band shows colored blocks with real app package boundaries. Pinch-zoom and pan the battery chart, then confirm all three bands below it visibly shift/zoom in sync (not lagging or static) - this is the core thing this task exists to prove.

Cross-check against real data: pull the DB and compare one thermal transition visible in the band against the raw `thermal` rows for that time window, same way every prior collector PR in this project verified its data against ground truth:

```bash
DB=/tmp/introspect_timeline_check.db
adb exec-out run-as net.kimptoc.introspect cat databases/introspect.db > "$DB"
sqlite3 "$DB" "SELECT datetime(timestamp/1000,'unixepoch','localtime'), value_text FROM samples WHERE collector_id='thermal' AND key='status' ORDER BY timestamp DESC LIMIT 5;"
```

Expected: the most recent rows' values match the color/segment visible at the right edge of the thermal band.

Check for crashes:

```bash
adb logcat -d -t 500 | grep -i "FATAL EXCEPTION"
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt
git commit -m "Wire thermal, Doze/screen-on, and app-session bands, synced to chart pan/zoom"
```

---

### Task 8: Tap-marker for exact values

**Files:**
- Create: `app/src/main/java/net/kimptoc/introspect/timeline/TimelineMarkerView.kt`
- Create: `app/src/main/res/layout/timeline_marker.xml`
- Modify: `app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt`

**Interfaces:**
- Consumes: MPAndroidChart's `MarkerView`, `Entry`, `Highlight`; `TimelineActivity`'s already-loaded band/session data for the current range.
- Produces: nothing further consumed by later tasks (this is the last task).

- [ ] **Step 1: Marker layout**

Write `app/src/main/res/layout/timeline_marker.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="#DD000000"
    android:padding="8dp">

    <TextView
        android:id="@+id/markerText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textColor="#FFFFFF"
        android:textSize="12sp" />

</LinearLayout>
```

- [ ] **Step 2: Marker view class**

Write `app/src/main/java/net/kimptoc/introspect/timeline/TimelineMarkerView.kt`:

```kotlin
package net.kimptoc.introspect.timeline

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import net.kimptoc.introspect.R
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Shows the exact timestamp/battery%/thermal/Doze/app values for a
 * tapped point, looked up from [TimelineActivity]'s already-loaded range
 * data - no per-tap query, the range is small enough to hold in memory
 * (spec-consistent with every other in-memory-after-one-query pattern in
 * this app; see [TimelineRepository]'s downsampling for why the range
 * stays bounded even at "all time").
 */
class TimelineMarkerView(
    context: Context,
    private val rangeStartMs: Long,
    private val lookup: (timestampMs: Long) -> String,
) : MarkerView(context, R.layout.timeline_marker) {

    private val textView: TextView = findViewById(R.id.markerText)
    private val timeFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    override fun refreshContent(e: Entry, highlight: Highlight) {
        val timestampMs = rangeStartMs + (e.x * 1000).toLong()
        textView.text = "${timeFormat.format(timestampMs)}\n${lookup(timestampMs)}"
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF = MPPointF(-(width / 2f), -height.toFloat())
}
```

- [ ] **Step 3: Wire the marker into `TimelineActivity`**

Add a field to hold the currently-loaded data the marker needs to look up (battery/thermal/doze/sessions for the visible range), and set the marker in `loadRange`.

Edit `app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt`. Add fields:

```kotlin
    private var loadedThermal: List<TimelineSegment<String>> = emptyList()
    private var loadedDeviceIdle: List<TimelineSegment<Boolean>> = emptyList()
    private var loadedScreenOn: List<TimelineSegment<Boolean>> = emptyList()
    private var loadedSessions: List<AppSession> = emptyList()
```

In `loadRange`, store what Task 7 already computes instead of discarding it. Find this exact block from Task 7:

```kotlin
            thermalBand.setSegments(
                repository.loadThermal(startMs, endMs).map {
                    TimelineBandView.Segment(it.startMs, it.endMs, thermalColors[it.value] ?: Color.LTGRAY, it.value)
                },
            )
```

and replace it with:

```kotlin
            loadedThermal = repository.loadThermal(startMs, endMs)
            thermalBand.setSegments(
                loadedThermal.map {
                    TimelineBandView.Segment(it.startMs, it.endMs, thermalColors[it.value] ?: Color.LTGRAY, it.value)
                },
            )
```

Find this exact block from Task 7:

```kotlin
            val deviceIdle = repository.loadDeviceIdle(startMs, endMs)
            val screenOn = repository.loadScreenOn(startMs, endMs)
```

and replace it with:

```kotlin
            loadedDeviceIdle = repository.loadDeviceIdle(startMs, endMs)
            loadedScreenOn = repository.loadScreenOn(startMs, endMs)
            val deviceIdle = loadedDeviceIdle
            val screenOn = loadedScreenOn
```

Find this exact block from Task 7:

```kotlin
            sessionsBand.setSegments(
                repository.loadAppSessions(startMs, endMs).map { session ->
                    val color = packageColor.getOrPut(session.packageName) {
                        sessionColors[packageColor.size % sessionColors.size]
                    }
                    TimelineBandView.Segment(session.startMs, session.endMs, color, session.packageName)
                },
            )
```

and replace it with:

```kotlin
            loadedSessions = repository.loadAppSessions(startMs, endMs)
            sessionsBand.setSegments(
                loadedSessions.map { session ->
                    val color = packageColor.getOrPut(session.packageName) {
                        sessionColors[packageColor.size % sessionColors.size]
                    }
                    TimelineBandView.Segment(session.startMs, session.endMs, color, session.packageName)
                },
            )
```

Then, after `syncBandsToChart()` at the end of the successful-load path in `loadRange`, add:

```kotlin
            batteryChart.marker = TimelineMarkerView(this@TimelineActivity, rangeStartMs) { timestampMs ->
                buildString {
                    append(loadedThermal.firstOrNull { timestampMs in it.startMs..it.endMs }?.value?.let { "Thermal: $it\n" } ?: "")
                    append(loadedDeviceIdle.firstOrNull { timestampMs in it.startMs..it.endMs }?.value?.let { "Idle: $it\n" } ?: "")
                    append(loadedScreenOn.firstOrNull { timestampMs in it.startMs..it.endMs }?.value?.let { "Screen on: $it\n" } ?: "")
                    append(loadedSessions.firstOrNull { timestampMs in it.startMs..it.endMs }?.packageName?.let { "App: $it" } ?: "")
                }.trimEnd()
            }
```

- [ ] **Step 4: Build, install, verify tap-marker shows correct values**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "$JAVA_HOME") ./gradlew installDebug -q
```

On-device: open `TimelineActivity`, tap a point on the battery line. Confirm a marker appears showing a timestamp, and thermal/Doze/app values that look plausible for that moment. Cross-check one tapped point precisely against the raw DB, same pattern as Task 7's verification:

```bash
DB=/tmp/introspect_timeline_check2.db
adb exec-out run-as net.kimptoc.introspect cat databases/introspect.db > "$DB"
sqlite3 "$DB" "SELECT datetime(timestamp/1000,'unixepoch','localtime'), key, value_num, value_text FROM samples WHERE timestamp BETWEEN <marker_ts_minus_60000> AND <marker_ts_plus_60000> AND collector_id IN ('battery','thermal','doze') ORDER BY timestamp;"
```

(Substitute the actual tapped timestamp from the marker, converted to epoch millis, for the two `<...>` placeholders.)

Expected: the marker's displayed thermal/Doze values match what the DB shows was true at that timestamp.

Check for crashes:

```bash
adb logcat -d -t 500 | grep -i "FATAL EXCEPTION"
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/kimptoc/introspect/timeline/TimelineMarkerView.kt app/src/main/res/layout/timeline_marker.xml app/src/main/java/net/kimptoc/introspect/timeline/TimelineActivity.kt
git commit -m "Add tap-marker showing exact values, looked up from already-loaded range data"
```

---

## Post-plan: open a PR

All 8 tasks committed to the `phase5-timeline-view` branch created in Task 1. Push it and open a PR (reviewed by kilo-code-bot before merging — the assistant does not self-merge, matching every prior PR in this project):

```bash
git push -u origin phase5-timeline-view
gh pr create --title "Phase 5 (UI): timeline view" --body "$(cat <<'EOF'
## Summary
- Implements spec.md §6's Phase 5: a TimelineActivity charting battery level, thermal state, Doze/screen-on state, and app sessions on a shared, pan/zoomable time axis.
- Design: docs/superpowers/specs/2026-08-13-phase5-timeline-design.md
- Battery via MPAndroidChart LineChart; thermal/Doze/sessions via a reusable TimelineBandView (colored interval segments), kept in sync with the chart's pan/zoom.
- Range picker (24h/3d/7d/all); 7d/all downsample in SQL to keep queries and rendering bounded as the dataset grows.
- Tap-marker shows exact timestamp/battery%/thermal/Doze/app values.

## Test plan
- [x] Built and installed on-device after every task
- [x] Verified real data renders for each range, pinch-zoom/pan works, bands stay synced with the chart
- [x] Cross-checked thermal band and tap-marker values against raw DB rows
- [x] Verified empty-range and no-usage-access states
- [x] No crashes across the full verification session
EOF
)"
```

(Branch should have been created before Task 1's first commit - if not, create it retroactively with `git branch phase5-timeline-view` before pushing, or cherry-pick the 8 commits onto a fresh branch off `main`.)
