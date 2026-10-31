package com.focusred.timer

import android.content.Context

/** User settings shared by the setup screen and the floating timer. */
object FocusPrefs {

    private const val NAME = "focusred_prefs"

    const val DEFAULT_LIMIT_MINUTES = 60
    const val MAX_LIMIT_MINUTES = 600

    const val DEFAULT_OPACITY = 75
    const val MIN_OPACITY = 20

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Daily limit for [packageName] in minutes; 0 means no limit. */
    fun limitMinutes(context: Context, packageName: String): Int =
        prefs(context).getInt("limit_$packageName", DEFAULT_LIMIT_MINUTES)

    fun setLimitMinutes(context: Context, packageName: String, minutes: Int) {
        prefs(context).edit()
            .putInt("limit_$packageName", minutes.coerceIn(0, MAX_LIMIT_MINUTES))
            .apply()
    }

    /** Next limit for the + button: 5-minute steps up to an hour, 15-minute steps after. */
    fun increaseLimit(minutes: Int): Int =
        (if (minutes < 60) minutes + 5 else minutes + 15).coerceAtMost(MAX_LIMIT_MINUTES)

    /** Next limit for the − button; reaching 0 turns the limit off. */
    fun decreaseLimit(minutes: Int): Int =
        (if (minutes <= 60) minutes - 5 else minutes - 15).coerceAtLeast(0)

    fun formatLimit(minutes: Int): String = when {
        minutes == 0 -> "Off"
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

    /** Opacity of the floating timer's background, in percent. */
    fun overlayOpacity(context: Context): Int =
        prefs(context).getInt("overlay_opacity", DEFAULT_OPACITY)

    fun setOverlayOpacity(context: Context, percent: Int) {
        prefs(context).edit()
            .putInt("overlay_opacity", percent.coerceIn(MIN_OPACITY, 100))
            .apply()
    }

    /** Whether the floating timer pulses (fades in and out). */
    fun pulseEnabled(context: Context): Boolean =
        prefs(context).getBoolean("overlay_pulse", true)

    fun setPulseEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("overlay_pulse", enabled).apply()
    }

    // ---- Floating timer behavior, set in the app's "Floating timer" card ----

    const val DEFAULT_MAX_MINIMIZED_SECONDS = 30
    const val DEFAULT_NO_MINIMIZE_AFTER_MINUTES = 5
    const val DEFAULT_GRADUAL_START = 65
    const val DEFAULT_GRADUAL_STEP = 10
    const val DEFAULT_GRADUAL_INTERVAL_SECONDS = 30

    /** How long the timer may stay minimized before it expands again, in seconds. */
    fun maxMinimizedSeconds(context: Context): Int =
        prefs(context).getInt("max_minimized_seconds", DEFAULT_MAX_MINIMIZED_SECONDS)

    fun setMaxMinimizedSeconds(context: Context, seconds: Int) =
        putInt(context, "max_minimized_seconds", seconds.coerceIn(5, 300))

    /** Session length (per app) after which the timer can no longer be minimized, in minutes. */
    fun noMinimizeAfterMinutes(context: Context): Int =
        prefs(context).getInt("no_minimize_after_minutes", DEFAULT_NO_MINIMIZE_AFTER_MINUTES)

    fun setNoMinimizeAfterMinutes(context: Context, minutes: Int) =
        putInt(context, "no_minimize_after_minutes", minutes.coerceIn(1, 60))

    /**
     * Whether the timer can always be minimized (also after the session cutoff and over the
     * daily limit) and then stays minimized until expanded. Hiding keeps the strict rules.
     */
    fun alwaysAllowMinimize(context: Context): Boolean =
        prefs(context).getBoolean("always_allow_minimize", false)

    fun setAlwaysAllowMinimize(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("always_allow_minimize", enabled).apply()
    }

    /** Whether the timer's opacity rises with the session time instead of using [overlayOpacity]. */
    fun gradualOpacityEnabled(context: Context): Boolean =
        prefs(context).getBoolean("gradual_opacity", true)

    fun setGradualOpacityEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("gradual_opacity", enabled).apply()
    }

    /** Gradual opacity at the start of a session, in percent. */
    fun gradualStartPercent(context: Context): Int =
        prefs(context).getInt("gradual_start", DEFAULT_GRADUAL_START)

    fun setGradualStartPercent(context: Context, percent: Int) =
        putInt(context, "gradual_start", percent.coerceIn(MIN_OPACITY, 100))

    /** How much the gradual opacity rises each [gradualIntervalSeconds], in percent. */
    fun gradualStepPercent(context: Context): Int =
        prefs(context).getInt("gradual_step", DEFAULT_GRADUAL_STEP)

    fun setGradualStepPercent(context: Context, percent: Int) =
        putInt(context, "gradual_step", percent.coerceIn(5, 50))

    fun gradualIntervalSeconds(context: Context): Int =
        prefs(context).getInt("gradual_interval", DEFAULT_GRADUAL_INTERVAL_SECONDS)

    fun setGradualIntervalSeconds(context: Context, seconds: Int) =
        putInt(context, "gradual_interval", seconds.coerceIn(10, 300))

    /**
     * Until when the timer is paused for [packageName], as epoch millis: 0 means not
     * paused and [PAUSED_UNTIL_RESUMED] means paused until the user resumes it.
     */
    fun pausedUntil(context: Context, packageName: String): Long =
        prefs(context).getLong("paused_until_$packageName", 0L)

    fun setPausedUntil(context: Context, packageName: String, until: Long) {
        prefs(context).edit().putLong("paused_until_$packageName", until).apply()
    }

    const val PAUSED_UNTIL_RESUMED = Long.MAX_VALUE

    /** "45s", "1m", "1m 30s". */
    fun formatSeconds(seconds: Int): String = when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}m"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }

    private fun putInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }
}
