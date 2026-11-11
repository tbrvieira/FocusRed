package com.focusred.timer

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Setup screen: shows which permissions FocusRed still needs and
 * opens the right Settings page for each one. Below the checklist are the
 * weekly usage dashboard, the per-app daily limits and the floating timer settings.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_DASHBOARD = "show_dashboard"

        private val RED = Color.parseColor("#DC2626")
        private val GREEN = Color.parseColor("#16A34A")
        private val LIMIT_YELLOW = Color.parseColor("#EAB308")

        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }

    private lateinit var statusText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var dashboardTitle: TextView
    private lateinit var dashboard: LinearLayout
    private lateinit var pauses: LinearLayout
    private val loader = Executors.newSingleThreadExecutor()
    private var scrollToDashboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "FocusRed"
            textSize = 28f
            setTextColor(Color.parseColor("#B91C1C"))
        })

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, pad)
        }
        root.addView(statusText)

        root.addView(button("1. Allow display over other apps") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })
        root.addView(button("2. Allow usage data access") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(button("3. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            root.addView(button("4. Allow notifications") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        val batteryStep = if (Build.VERSION.SDK_INT >= 33) 5 else 4
        root.addView(button("$batteryStep. Turn off battery optimization") {
            requestBatteryExemption()
        })
        root.addView(button("If accessibility is greyed out: open App info") {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        })

        root.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            text = "Tip: on Android 13+ an APK installed outside the Play Store can't turn on " +
                "accessibility until you open App info, tap the ⋮ menu (top right) and choose " +
                "\"Allow restricted settings\".\n\n" +
                "Xiaomi, OnePlus, Oppo, Realme, Vivo, Huawei and Honor phones may still close " +
                "FocusRed in the background. In App info also turn on Autostart (if offered), " +
                "set battery use to \"No restrictions\" / \"Unrestricted\" and lock FocusRed in " +
                "Recent apps. On Xiaomi, also allow \"Display pop-up windows while running in the " +
                "background\" under Other permissions."
        })

        dashboardTitle = TextView(this).apply {
            text = "Weekly usage"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad * 2, 0, 0)
        }
        root.addView(dashboardTitle)
        dashboard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(dashboard)

        root.addView(TextView(this).apply {
            text = "Floating timer"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad * 2, 0, 0)
        })
        pauses = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(card("Pause the timer", pauses))
        timerSettings().forEach { root.addView(it) }

        scrollView = ScrollView(this).apply { addView(root) }
        setContentView(scrollView)
        scrollToDashboard = intent.getBooleanExtra(EXTRA_SHOW_DASHBOARD, false)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        loadDashboard()
        renderPauses()
    }

    override fun onStop() {
        super.onStop()
        pauses.removeCallbacks(refreshPauses) // onResume re-renders and re-posts it.
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun refreshStatus() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val lines = mutableListOf(
            "${mark(Settings.canDrawOverlays(this))} Display over other apps",
            "${mark(hasUsageAccess())} Usage data access",
            "${mark(isAccessibilityEnabled())} Accessibility service"
        )
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            lines.add("${mark(granted)} Notifications")
        }
        lines.add("${mark(isIgnoringBatteryOptimizations())} Battery optimization off")
        statusText.text = lines.joinToString("\n") +
            "\n\nWhen all are ✅, open Instagram, TikTok, YouTube, X, Reddit, Facebook or Threads " +
            "and the red timer appears."
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    /**
     * Asks to exempt FocusRed from battery optimization, so the system (and the stricter
     * battery managers of some phone brands) doesn't stop the timer in the background.
     */
    private fun requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        // Some phones don't offer the direct prompt; fall back to the full list.
        val intent = direct.takeIf { it.resolveActivity(packageManager) != null }
            ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals("$packageName/${AppAccessibilityService::class.java.name}", true) ||
                it.equals("$packageName/.AppAccessibilityService", true)
        }
    }

    // ---- Weekly dashboard ----

    private fun loadDashboard() {
        if (!hasUsageAccess()) {
            dashboard.removeAllViews()
            dashboard.addView(hint("Allow usage data access (step 2) to see your weekly usage and set daily limits."))
            return
        }
        if (dashboard.childCount == 0) dashboard.addView(hint("Loading…"))
        // Reading two weeks of usage events takes a moment, so keep it off the UI thread.
        loader.execute {
            val days = try {
                UsageStatsHelper.usageByDay(this, 14)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (days != null) {
                    renderDashboard(days)
                } else {
                    dashboard.removeAllViews()
                    dashboard.addView(hint("Couldn't read usage data."))
                }
                if (scrollToDashboard) {
                    scrollToDashboard = false
                    scrollView.post { scrollView.smoothScrollTo(0, dashboardTitle.top) }
                }
            }
        }
    }

    /** [days] holds 14 days, oldest first: last week, then this week ending today. */
    private fun renderDashboard(days: List<DayUsage>) {
        val thisWeek = days.takeLast(7)
        val lastWeek = days.dropLast(7)
        val perApp = SocialApps.all
            .map { app -> app to thisWeek.sumOf { it.perApp[app.packageName] ?: 0L } }
            .sortedByDescending { it.second }

        dashboard.removeAllViews()
        dashboard.addView(card("Social media time per day",
            WeeklyBarChartView(this).apply { setDays(thisWeek) }))
        dashboard.addView(card("This week vs last week", weekComparison(thisWeek, lastWeek)))
        dashboard.addView(card("Most used app", mostUsedApp(perApp)))
        dashboard.addView(card("Time per app this week", appBreakdown(perApp)))
        dashboard.addView(card("Daily limits", dailyLimits(days.last().perApp)))
    }

    private fun weekComparison(thisWeek: List<DayUsage>, lastWeek: List<DayUsage>): View {
        val thisTotal = thisWeek.sumOf { it.total }
        val lastTotal = lastWeek.sumOf { it.total }

        val change = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        }
        if (lastTotal == 0L) {
            change.text = "No social media time recorded last week."
        } else {
            val percent = ((thisTotal - lastTotal) * 100.0 / lastTotal).roundToInt()
            when {
                percent > 0 -> {
                    change.text = "▲ $percent% more than last week"
                    change.setTextColor(RED)
                }
                percent < 0 -> {
                    change.text = "▼ ${-percent}% less than last week"
                    change.setTextColor(GREEN)
                }
                else -> change.text = "Same as last week"
            }
        }

        return vertical(
            LinearLayout(this).apply {
                addView(stat("This week", thisTotal), weighted())
                addView(stat("Last week", lastTotal), weighted())
            },
            change,
            if (lastWeek.any { !it.hasData }) {
                hint("Android only keeps about 10 days of detailed history, so last week is " +
                    "incomplete. FocusRed saves every finished day, so this fills in over time.")
            } else null
        )
    }

    private fun mostUsedApp(perApp: List<Pair<SocialApp, Long>>): View {
        val (app, millis) = perApp.first()
        if (millis == 0L) return hint("No social media time this week.")
        val share = millis * 100 / perApp.sumOf { it.second }
        return vertical(
            row(
                dot(app.color, 12),
                TextView(this).apply {
                    text = app.label
                    textSize = 20f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(10), 0, 0, 0)
                },
                Space(this),
                TextView(this).apply {
                    text = UsageStatsHelper.formatDuration(millis)
                    textSize = 20f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                }
            ),
            hint("$share% of your social media time this week")
        )
    }

    private fun appBreakdown(perApp: List<Pair<SocialApp, Long>>): View {
        val max = perApp.maxOf { it.second }.coerceAtLeast(1)
        return vertical(*perApp.map { (app, millis) ->
            vertical(
                row(
                    dot(app.color, 10),
                    TextView(this).apply {
                        text = app.label
                        textSize = 15f
                        setPadding(dp(8), 0, 0, 0)
                    },
                    Space(this),
                    TextView(this).apply {
                        text = UsageStatsHelper.formatDuration(millis)
                        textSize = 15f
                        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    }
                ),
                HorizontalBarView(this).apply {
                    fraction = millis / max.toFloat()
                    color = app.color
                    setPadding(0, dp(6), 0, 0)
                }
            ).apply { setPadding(0, dp(12), 0, 0) }
        }.toTypedArray())
    }

    private fun dailyLimits(today: Map<String, Long>): View = vertical(
        hint("Tap − or + to set each app's daily limit (0 turns it off). The floating timer " +
            "shows your progress toward it."),
        *SocialApps.all.map { limitRow(it, today[it.packageName] ?: 0L) }.toTypedArray()
    )

    private fun limitRow(app: SocialApp, usedToday: Long): View {
        var limit = FocusPrefs.limitMinutes(this, app.packageName)

        val badge = TextView(this).apply {
            text = "Limit exceeded"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(RED)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = rounded(Color.argb(0x22, 0xDC, 0x26, 0x26), 6)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) }
        }
        val value = stepperValue()
        val bar = HorizontalBarView(this)
        val summary = hint("")

        fun update() {
            value.text = FocusPrefs.formatLimit(limit)
            val limitMillis = limit * 60_000L
            val exceeded = limit > 0 && usedToday >= limitMillis
            badge.isVisible = exceeded
            bar.isVisible = limit > 0
            bar.fraction = if (limit > 0) usedToday / limitMillis.toFloat() else 0f
            bar.color = if (exceeded) RED else LIMIT_YELLOW
            val used = UsageStatsHelper.formatDuration(usedToday)
            summary.text = when {
                limit == 0 -> "$used today · no limit"
                exceeded -> "$used today · ${UsageStatsHelper.formatDuration(usedToday - limitMillis)} over"
                else -> "$used of ${FocusPrefs.formatLimit(limit)} today"
            }
        }
        fun setLimit(minutes: Int) {
            limit = minutes
            FocusPrefs.setLimitMinutes(this, app.packageName, minutes)
            update()
        }

        val stepper = stepper(
            "${app.label} limit", value,
            onMinus = { setLimit(FocusPrefs.decreaseLimit(limit)) },
            onPlus = { setLimit(FocusPrefs.increaseLimit(limit)) }
        )

        update()
        return vertical(
            row(
                dot(app.color, 10),
                TextView(this).apply {
                    text = app.label
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(8), 0, 0, 0)
                },
                badge,
                Space(this),
                stepper
            ),
            bar.apply { setPadding(0, dp(8), 0, 0) },
            summary
        ).apply { setPadding(0, dp(14), 0, 0) }
    }

    // ---- Floating timer settings ----

    /** Per-app Pause/Resume rows; rebuilt on resume so expired pauses disappear. */
    private fun renderPauses() {
        pauses.removeCallbacks(refreshPauses)
        pauses.removeAllViews()
        pauses.addView(hint("Turn the timer off for an app for a while, e.g. to watch something " +
            "full screen. Its time still counts toward your stats and limits."))
        val now = System.currentTimeMillis()
        SocialApps.all.forEach { app ->
            val until = FocusPrefs.pausedUntil(this, app.packageName)
            val paused = until > now
            val status = when {
                !paused -> null
                until == FocusPrefs.PAUSED_UNTIL_RESUMED -> "Paused until you resume"
                else -> "Paused until " + DateFormat.getTimeFormat(this).format(Date(until))
            }
            val button = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (paused) "Resume" else "Pause"
                contentDescription = "${text} the timer for ${app.label}"
                setOnClickListener {
                    if (paused) {
                        FocusPrefs.setPausedUntil(context, app.packageName, 0L)
                        renderPauses()
                    } else {
                        choosePause(app)
                    }
                }
            }
            pauses.addView(row(
                dot(app.color, 10),
                vertical(
                    TextView(this).apply {
                        text = app.label
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                    status?.let { hint(it) }
                ).apply {
                    setPadding(dp(8), 0, 0, 0)
                    layoutParams = weighted()
                },
                button
            ).apply { setPadding(0, dp(8), 0, 0) })
        }
        // Update the list when the next pause runs out while this screen is open.
        SocialApps.all.map { FocusPrefs.pausedUntil(this, it.packageName) }
            .filter { it > now && it != FocusPrefs.PAUSED_UNTIL_RESUMED }
            .minOrNull()
            ?.let { pauses.postDelayed(refreshPauses, it - now + 500) }
    }

    private val refreshPauses = Runnable { renderPauses() }

    private fun choosePause(app: SocialApp) {
        val options = listOf(
            "15 minutes" to 15, "30 minutes" to 30, "1 hour" to 60, "2 hours" to 120,
            "Until I resume" to 0
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Pause the timer for ${app.label}")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val minutes = options[which].second
                val until = if (minutes == 0) {
                    FocusPrefs.PAUSED_UNTIL_RESUMED
                } else {
                    System.currentTimeMillis() + minutes * 60_000L
                }
                FocusPrefs.setPausedUntil(this, app.packageName, until)
                renderPauses()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun timerSettings(): List<View> {
        val maxMinimizedRow = settingRow(
            "Stay minimized for at most", FocusPrefs.maxMinimizedSeconds(this), 5..300,
            FocusPrefs::formatSeconds,
            down = { if (it <= 60) it - 5 else it - 15 },
            up = { if (it < 60) it + 5 else it + 15 }
        ) { FocusPrefs.setMaxMinimizedSeconds(this, it) }
        fun showMaxMinimized(applies: Boolean) {
            maxMinimizedRow.alpha = if (applies) 1f else 0.4f
            setEnabledDeep(maxMinimizedRow, applies)
        }
        val alwaysMinimizeSwitch = MaterialSwitch(this).apply {
            text = "Always allow minimizing"
            textSize = 15f
            isChecked = FocusPrefs.alwaysAllowMinimize(context)
            setOnCheckedChangeListener { _, checked ->
                FocusPrefs.setAlwaysAllowMinimize(context, checked)
                showMaxMinimized(!checked)
            }
        }
        showMaxMinimized(!alwaysMinimizeSwitch.isChecked)

        val minimizing = vertical(
            maxMinimizedRow,
            settingRow(
                "No minimizing after a session of", FocusPrefs.noMinimizeAfterMinutes(this), 1..60,
                { "$it min" }, down = { it - 1 }, up = { it + 1 }
            ) { FocusPrefs.setNoMinimizeAfterMinutes(this, it) },
            hint("A session is your time in one app (paused time included). It ends once " +
                "you've been away from the app for ${OverlayService.SESSION_BREAK_MS / 60_000} " +
                "minutes (time with the phone locked counts as away). After the " +
                "no-minimizing time the timer can't be minimized or hidden for the rest of the " +
                "session. Once an app's daily limit is reached, the timer always stays expanded " +
                "and fully opaque."),
            alwaysMinimizeSwitch.apply { setPadding(0, dp(12), 0, 0) },
            hint("When on, you can minimize the timer at any time, even after the " +
                "no-minimizing time or over the daily limit, and it stays minimized until you " +
                "expand it. Hiding briefly still follows the rules above.")
        )

        val gradualRows = vertical(
            settingRow(
                "Start at", FocusPrefs.gradualStartPercent(this), FocusPrefs.MIN_OPACITY..100,
                { "$it%" }, down = { it - 5 }, up = { it + 5 }
            ) { FocusPrefs.setGradualStartPercent(this, it) },
            settingRow(
                "Increase by", FocusPrefs.gradualStepPercent(this), 5..50,
                { "+$it%" }, down = { it - 5 }, up = { it + 5 }
            ) { FocusPrefs.setGradualStepPercent(this, it) },
            settingRow(
                "Every", FocusPrefs.gradualIntervalSeconds(this), 10..300,
                FocusPrefs::formatSeconds,
                down = { if (it <= 60) it - 10 else it - 30 },
                up = { if (it < 60) it + 10 else it + 30 }
            ) { FocusPrefs.setGradualIntervalSeconds(this, it) }
        )
        fun showGradual(enabled: Boolean) {
            gradualRows.alpha = if (enabled) 1f else 0.4f
            setEnabledDeep(gradualRows, enabled)
        }
        val gradualSwitch = MaterialSwitch(this).apply {
            text = "Get more opaque as the session goes on"
            textSize = 15f
            isChecked = FocusPrefs.gradualOpacityEnabled(context)
            setOnCheckedChangeListener { _, checked ->
                FocusPrefs.setGradualOpacityEnabled(context, checked)
                showGradual(checked)
            }
        }
        showGradual(gradualSwitch.isChecked)
        val opacity = vertical(
            gradualSwitch,
            gradualRows,
            hint("When this is off, the timer uses the opacity set in its controls panel " +
                "(sliders icon).")
        )

        return listOf(card("Minimizing", minimizing), card("Opacity", opacity))
    }

    /** A setting with its label on the left and a − value + stepper on the right. */
    private fun settingRow(
        label: String,
        initial: Int,
        range: IntRange,
        format: (Int) -> String,
        down: (Int) -> Int,
        up: (Int) -> Int,
        save: (Int) -> Unit
    ): View {
        var current = initial
        val value = stepperValue().apply {
            minWidth = dp(64)
            text = format(current)
        }
        fun set(newValue: Int) {
            current = newValue.coerceIn(range)
            save(current)
            value.text = format(current)
        }
        return row(
            TextView(this).apply {
                text = label
                textSize = 15f
                layoutParams = weighted()
            },
            stepper(label.lowercase(), value, onMinus = { set(down(current)) }, onPlus = { set(up(current)) })
        ).apply { setPadding(0, dp(10), 0, 0) }
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
        }
    }

    // ---- Small view helpers ----

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun weighted() = LinearLayout.LayoutParams(0, WRAP, 1f)

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun card(title: String, content: View) = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        radius = dp(16).toFloat()
        setContentPadding(dp(16), dp(14), dp(16), dp(16))
        addView(vertical(
            TextView(context).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            },
            content
        ))
    }

    private fun vertical(vararg children: View?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        children.filterNotNull().forEach { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
    }

    /** Horizontal row; a [Space] child soaks up the leftover width. */
    private fun row(vararg children: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        children.forEach { child ->
            when {
                child is Space -> addView(child, weighted())
                child.layoutParams != null -> addView(child)
                else -> addView(child, LinearLayout.LayoutParams(WRAP, WRAP))
            }
        }
    }

    private fun dot(color: Int, sizeDp: Int) = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun stat(label: String, millis: Long) = vertical(
        hint(label),
        TextView(this).apply {
            text = UsageStatsHelper.formatDuration(millis)
            textSize = 24f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
    )

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(themeColor(android.R.attr.textColorSecondary))
        setPadding(0, dp(4), 0, 0)
    }

    /** A − [value] + control; [name] is used for the buttons' accessibility labels. */
    private fun stepper(name: String, value: TextView, onMinus: () -> Unit, onPlus: () -> Unit) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.argb(0x55, 0x80, 0x80, 0x80))
            }
            addView(stepperButton("−", "Decrease $name", onMinus))
            addView(value)
            addView(stepperButton("+", "Increase $name", onPlus))
        }

    private fun stepperValue() = TextView(this).apply {
        textSize = 14f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = dp(56)
    }

    private fun stepperButton(label: String, description: String, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
        }
}
