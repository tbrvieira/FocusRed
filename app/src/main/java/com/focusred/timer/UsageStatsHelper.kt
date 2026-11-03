package com.focusred.timer

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Social media foreground time for one calendar day. */
class DayUsage(
    val dayStart: Long,
    /** Foreground milliseconds per package. */
    val perApp: Map<String, Long>,
    /** False when Android had already discarded this day's events and it was never cached. */
    val hasData: Boolean
) {
    val total: Long get() = perApp.values.sum()
}

/**
 * Foreground time rebuilt from the raw activity events of
 * UsageStatsManager.queryEvents(). The INTERVAL_DAILY buckets from
 * queryUsageStats() are unreliable (their boundaries don't match local
 * midnight and they overlap), so every number FocusRed shows comes from here.
 */
object UsageStatsHelper {

    // An app that was already open when a range starts only has its
    // ACTIVITY_RESUMED event before the range, so look back this far for it.
    private const val SESSION_LOOKBACK_MS = 3 * 60 * 60 * 1000L

    // Android keeps only ~10 days of events, so finished days are cached
    // locally to keep "last week" available.
    private const val CACHE_PREFS = "usage_cache"
    private const val CACHE_DAYS = 30

    private class Session(val packageName: String, val start: Long, val end: Long)

    /**
     * Calculates total cumulative usage seconds for a package today (midnight to now).
     */
    fun getTodayUsageForPackage(context: Context, packageName: String): Int {
        val now = System.currentTimeMillis()
        val (sessions, _) = foregroundSessions(context, startOfDay(now), now, setOf(packageName))
        return (sessions.sumOf { it.end - it.start } / 1000).toInt()
    }

    /**
     * Social media usage for each of the last [dayCount] days, oldest first.
     * The last entry is today so far.
     */
    fun usageByDay(context: Context, dayCount: Int): List<DayUsage> {
        val now = System.currentTimeMillis()
        val dayStarts = (dayCount - 1 downTo 0).map { startOfDay(now, daysAgo = it) }
        val (sessions, firstEvent) =
            foregroundSessions(context, dayStarts.first(), now, SocialApps.packages)

        val cache = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val editor = cache.edit()
        val days = dayStarts.mapIndexed { i, dayStart ->
            val dayEnd = dayStarts.getOrElse(i + 1) { now }
            val key = dayKey(dayStart)
            // Events reaching back past midnight mean this day wasn't pruned.
            val complete = firstEvent != null && firstEvent <= dayStart
            if (!complete) {
                cache.getString(key, null)?.let {
                    val cached = decode(it)
                    // Days cached before an app (e.g. Threads) was tracked lack its time.
                    return@mapIndexed DayUsage(dayStart, cached, cached.keys.containsAll(SocialApps.packages))
                }
            }
            val perApp = SocialApps.packages.associateWith { pkg ->
                sessions.filter { it.packageName == pkg }
                    .sumOf { (minOf(it.end, dayEnd) - maxOf(it.start, dayStart)).coerceAtLeast(0) }
            }
            if (complete && dayEnd < now) editor.putString(key, encode(perApp))
            DayUsage(dayStart, perApp, complete)
        }

        val oldestKept = dayKey(startOfDay(now, daysAgo = CACHE_DAYS))
        cache.all.keys.filter { it < oldestKept }.forEach { editor.remove(it) }
        editor.apply()
        return days
    }

    /**
     * Rebuilds foreground sessions for [packages] (main packages; time in a variant
     * such as TikTok Lite is reported under its main package) between [start] and [end]
     * from ACTIVITY_RESUMED / ACTIVITY_PAUSED / ACTIVITY_STOPPED events.
     * Returns the sessions clipped to the range, plus the timestamp of the
     * earliest event Android still had (how far back its history goes).
     */
    private fun foregroundSessions(
        context: Context,
        start: Long,
        end: Long,
        packages: Set<String>
    ): Pair<List<Session>, Long?> {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usageStatsManager.queryEvents(start - SESSION_LOOKBACK_MS, end)
        val event = UsageEvents.Event()

        // An app is in the foreground while at least one of its activities is resumed.
        val resumedActivities = HashMap<String, MutableSet<String>>()
        val openSince = HashMap<String, Long>()
        val sessions = ArrayList<Session>()
        var firstEvent: Long? = null

        fun close(pkg: String, at: Long) {
            resumedActivities.remove(pkg)
            val since = openSince.remove(pkg) ?: return
            val from = maxOf(since, start)
            val to = minOf(at, end)
            if (to > from) sessions.add(Session(SocialApps.canonical(pkg) ?: pkg, from, to))
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (firstEvent == null) firstEvent = event.timeStamp
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if ((SocialApps.canonical(pkg) ?: pkg) in packages) {
                    resumedActivities.getOrPut(pkg) { HashSet() }.add(event.className.orEmpty())
                    openSince.putIfAbsent(pkg, event.timeStamp)
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val resumed = resumedActivities[pkg] ?: continue
                    resumed.remove(event.className.orEmpty())
                    if (resumed.isEmpty()) close(pkg, event.timeStamp)
                }
                // Safety net in case an app never reported being paused.
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN ->
                    openSince.keys.toList().forEach { close(it, event.timeStamp) }
            }
        }
        // Anything still open is in the foreground right now (or was at `end`).
        openSince.keys.toList().forEach { close(it, end) }
        return sessions to firstEvent
    }

    fun startOfDay(time: Long, daysAgo: Int = 0): Long = Calendar.getInstance().run {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        timeInMillis
    }

    /** "<1m", "45m", "1h", "2h 5m". */
    fun formatDuration(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            millis in 1 until 60_000 -> "<1m"
            minutes < 60 -> "${minutes}m"
            minutes % 60 == 0L -> "${minutes / 60}h"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }

    private fun dayKey(dayStart: Long) =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dayStart))

    private fun encode(perApp: Map<String, Long>) =
        perApp.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun decode(value: String): Map<String, Long> =
        value.split(';').filter { '=' in it }.associate {
            it.substringBefore('=') to (it.substringAfter('=').toLongOrNull() ?: 0L)
        }
}
