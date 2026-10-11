package com.focusred.timer

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * FocusRed Floating Overlay Service for Galaxy S21 Ultra
 * Displays a pulsing semi-transparent red floating timer showing the
 * clock, real-time session duration, today's cumulative usage and
 * progress toward the app's daily limit.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "FocusRed"

        // The overlay fades from full to this alpha and back, once every 2 seconds.
        private const val PULSE_MIN_ALPHA = 0.55f
        private const val PULSE_HALF_PERIOD_MS = 1000L

        // A session ends after this long away from its app.
        const val SESSION_BREAK_MS = 5 * 60 * 1000L
        private const val UNLOCK_SHOW_DELAY_MS = 800L

        private val OPACITY_CHIPS = mapOf(
            R.id.chip_opacity_40 to 40,
            R.id.chip_opacity_60 to 60,
            R.id.chip_opacity_75 to 75,
            R.id.chip_opacity_95 to 95
        )
        private val HIDE_CHIPS = mapOf(
            R.id.chip_hide_15 to 15,
            R.id.chip_hide_30 to 30,
            R.id.chip_hide_60 to 60
        )
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var pulseAnimator: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private var sessionSeconds = 0
    private var todayCumulativeSeconds = 0
    private var currentPackageName = ""
    private var limitMinutes = 0
    private var opacityPercent = FocusPrefs.DEFAULT_OPACITY
    private var pulseEnabled = true
    private var isMinimized = false
    private var minimizedSeconds = 0

    private class SavedSession(val seconds: Int, val leftAt: Long)
    // Each app's session time when it last left the screen.
    private val sessions = HashMap<String, SavedSession>()
    // The app in front while its timer is paused from the app settings, since when, and
    // its session time at that moment.
    private var pausedPackage: String? = null
    private var pausedSince = 0L
    private var pausedSessionSeconds = 0
    private val pauseEndRunnable = Runnable { pausedPackage?.let { showFor(it) } }

    // The social app that was in front when the screen turned off, shown again on unlock.
    private var appBeforeScreenOff: String? = null
    private lateinit var keyguardManager: KeyguardManager
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_USER_PRESENT -> onUnlocked()
                // Without a lock screen there's no USER_PRESENT.
                Intent.ACTION_SCREEN_ON -> if (!keyguardManager.isKeyguardLocked) onUnlocked()
            }
        }
    }

    // Set in the app's "Floating timer" card; reloaded each time the timer is shown.
    private var maxMinimizedSeconds = FocusPrefs.DEFAULT_MAX_MINIMIZED_SECONDS
    private var noMinimizeAfterSeconds = FocusPrefs.DEFAULT_NO_MINIMIZE_AFTER_MINUTES * 60
    private var gradualOpacity = true
    private var gradualStart = FocusPrefs.DEFAULT_GRADUAL_START
    private var gradualStep = FocusPrefs.DEFAULT_GRADUAL_STEP
    private var gradualInterval = FocusPrefs.DEFAULT_GRADUAL_INTERVAL_SECONDS

    private lateinit var defaultDisplay: Display
    // Screen size the timer was last positioned for; a change means the screen rotated.
    private var lastScreen = Point()
    // Status bar and navigation/gesture bar sizes; the timer stays between them.
    private val barInsets = Rect()
    // The bar sizes that went with lastScreen, for remapping after a rotation.
    private val lastInsets = Rect()
    // Where the timer was when last hidden, so it comes back in the same place.
    private var savedX = 100
    private var savedY = 200

    private val timerRunnable = object : Runnable {
        override fun run() {
            sessionSeconds++
            todayCumulativeSeconds++
            if (isMinimized) minimizedSeconds++
            updateTimerDisplay()
            keepOnScreen() // Backstop in case a rotation callback was missed.
            handler.postDelayed(this, 1000)
        }
    }

    private val unsnoozeRunnable = Runnable {
        overlayView?.visibility = View.VISIBLE
        pulseAnimator?.resume()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        defaultDisplay = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        opacityPercent = FocusPrefs.overlayOpacity(this)
        pulseEnabled = FocusPrefs.pulseEnabled(this)
        loadTimerSettings()
        keyguardManager = getSystemService(KeyguardManager::class.java)
        // Exported because USER_PRESENT comes from SystemUI, which runs as an ordinary
        // app; all three are protected broadcasts that other apps can't send.
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        startForegroundServiceNotification()
    }

    private fun loadTimerSettings() {
        maxMinimizedSeconds = FocusPrefs.maxMinimizedSeconds(this)
        noMinimizeAfterSeconds = FocusPrefs.noMinimizeAfterMinutes(this) * 60
        gradualOpacity = FocusPrefs.gradualOpacityEnabled(this)
        gradualStart = FocusPrefs.gradualStartPercent(this)
        gradualStep = FocusPrefs.gradualStepPercent(this)
        gradualInterval = FocusPrefs.gradualIntervalSeconds(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val pkg = intent?.getStringExtra("PACKAGE_NAME") ?: ""

        when (action) {
            "ACTION_SHOW_OVERLAY" -> {
                forgetScreenOffApp()
                showFor(pkg)
            }
            "ACTION_HIDE_OVERLAY" -> {
                forgetScreenOffApp()
                leaveApp()
            }
            "ACTION_TEMPORARY_HIDE" -> {
                snoozeOverlay(30) // Hide for 30s as requested
            }
        }
        return START_STICKY
    }

    /** [pkg] is in front: show its timer, unless the timer is paused for it in the app. */
    private fun showFor(pkg: String) {
        handler.removeCallbacks(pauseEndRunnable)
        // Switching straight from a paused app to another one: keep the paused app's time.
        if (pausedPackage != null && pausedPackage != pkg) {
            rememberPausedSession()
            pausedPackage = null
        }
        val pausedUntil = FocusPrefs.pausedUntil(this, pkg)
        val now = System.currentTimeMillis()
        if (pausedUntil > now) {
            if (pausedPackage != pkg) {
                if (overlayView != null) rememberSession()
                hideOverlay()
                pausedPackage = pkg
                pausedSince = now
                pausedSessionSeconds = resumableSession(pkg, now)?.seconds ?: 0
            }
            // Bring the timer back if the pause ends while the app is still in front.
            // (After the phone sleeps, onUnlocked() checks again by the wall clock.)
            if (pausedUntil != FocusPrefs.PAUSED_UNTIL_RESUMED) {
                handler.postDelayed(pauseEndRunnable, pausedUntil - now)
            }
            return
        }
        // A pause that ended with the app still in front: that time was spent in the app.
        if (pausedPackage == pkg) rememberPausedSession()
        pausedPackage = null
        enterApp(pkg)
        todayCumulativeSeconds = try {
            UsageStatsHelper.getTodayUsageForPackage(this, pkg)
        } catch (e: Exception) {
            0 // Usage access not granted yet
        }
        limitMinutes = FocusPrefs.limitMinutes(this, pkg)
        loadTimerSettings()
        showOverlay()
        overlayView?.findViewById<TextView>(R.id.tv_app_name)?.text =
            SocialApps.label(pkg).uppercase(Locale.ROOT)
        updateTimerDisplay()
    }

    /**
     * Starts or resumes [pkg]'s session. A session belongs to one app and survives
     * brief trips elsewhere (other apps included); it ends after [SESSION_BREAK_MS]
     * away from the app. One left before midnight doesn't resume after it.
     */
    private fun enterApp(pkg: String) {
        if (pkg == currentPackageName && overlayView != null) return // Already showing it.
        if (pkg != currentPackageName && overlayView != null) rememberSession()
        val resumed = resumableSession(pkg, System.currentTimeMillis())
        if (pkg != currentPackageName || resumed == null) {
            isMinimized = false
            minimizedSeconds = 0
            // A direct switch from another app keeps the view; show the card again.
            overlayView?.let { applyMinimized(it) }
        }
        currentPackageName = pkg
        sessionSeconds = resumed?.seconds ?: 0
    }

    /** [pkg]'s saved session if it left less than [SESSION_BREAK_MS] ago today, else null. */
    private fun resumableSession(pkg: String, now: Long): SavedSession? =
        sessions[pkg]?.takeIf {
            now - it.leftAt < SESSION_BREAK_MS &&
                UsageStatsHelper.startOfDay(it.leftAt) == UsageStatsHelper.startOfDay(now)
        }

    /** Remembers the current app's session time as it leaves the screen. */
    private fun rememberSession() {
        if (currentPackageName.isEmpty()) return
        sessions[currentPackageName] = SavedSession(sessionSeconds, System.currentTimeMillis())
    }

    /** Time in an app while its timer was paused still counts toward its session. */
    private fun rememberPausedSession() {
        val pkg = pausedPackage ?: return
        val now = System.currentTimeMillis()
        sessions[pkg] = SavedSession(pausedSessionSeconds + ((now - pausedSince) / 1000).toInt(), now)
    }

    /** The social app left the screen: another app, the home screen, or the phone was locked. */
    private fun leaveApp() {
        handler.removeCallbacks(pauseEndRunnable)
        if (pausedPackage != null) {
            rememberPausedSession()
        } else if (overlayView != null) {
            rememberSession()
        }
        pausedPackage = null
        hideOverlay()
    }

    /** Locking the phone counts as leaving the app, so the session's break starts now. */
    private fun onScreenOff() {
        handler.removeCallbacks(unlockRunnable)
        val pkg = pausedPackage ?: currentPackageName.takeIf { overlayView != null } ?: return
        Log.d(TAG, "Screen off in $pkg")
        appBeforeScreenOff = pkg
        leaveApp()
    }

    /**
     * Back from the lock screen into the same app: the accessibility service sends
     * nothing (it ignores the lock screen), so show the timer again here. showFor()
     * also re-checks a pause by the wall clock, which kept running while the phone slept.
     * It waits a moment, so that unlocking through a notification into another app (whose
     * accessibility event cancels this) doesn't flash the previous app's timer.
     */
    private fun onUnlocked() {
        if (appBeforeScreenOff == null) return
        handler.removeCallbacks(unlockRunnable)
        handler.postDelayed(unlockRunnable, UNLOCK_SHOW_DELAY_MS)
    }

    private val unlockRunnable = Runnable {
        val pkg = appBeforeScreenOff ?: return@Runnable
        appBeforeScreenOff = null
        Log.d(TAG, "Unlocked into $pkg")
        showFor(pkg)
    }

    private fun forgetScreenOffApp() {
        appBeforeScreenOff = null
        handler.removeCallbacks(unlockRunnable)
    }

    private fun showOverlay() {
        if (overlayView != null) return
        // Without "Display over other apps" addView() throws, which would crash the
        // process and take the accessibility service down with it.
        if (!Settings.canDrawOverlays(this)) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
            // Position relative to the whole screen (not below the status bar, and
            // camera cutout included), so x/y don't shift when a full-screen video
            // hides the bars and keepOnScreen()'s bounds match the real screen.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val view = LayoutInflater.from(this).inflate(R.layout.layout_floating_timer, null)
        overlayView = view
        setupDragTouchListener(view)
        setupControls(view)
        applyMinimized(view)
        applyOpacity(view)
        applyPulseChips(view)
        // Minimizing, expanding or opening the controls resizes the timer; keep it on screen.
        view.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                // With gesture navigation, a drag starting on the timer near a screen
                // edge would otherwise be taken as a system Back swipe. Android honors
                // at most 200dp per edge, so ask for the top part: header and drag area.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val maxHeight = (200 * v.resources.displayMetrics.density).toInt()
                    v.systemGestureExclusionRects =
                        listOf(Rect(0, 0, right - left, minOf(bottom - top, maxHeight)))
                }
                v.post { keepOnScreen() }
            }
        }

        if (lastScreen.x == 0) lastScreen = screenSize()
        try {
            windowManager.addView(view, params)
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Couldn't show the timer", e)
            overlayView = null
            params = null
            return
        }
        updatePulse()
        handler.removeCallbacks(timerRunnable)
        handler.postDelayed(timerRunnable, 1000)
    }

    private fun setupDragTouchListener(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) moveTo(initialX + dx.toInt(), initialY + dy.toInt())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) v.performClick()
                    true
                }
                else -> false
            }
        }
        // Tapping the minimized pill (without dragging it) expands it again.
        view.setOnClickListener { if (isMinimized) setMinimized(false) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The screen rotated (e.g. a video went full screen): keep the timer visible.
        overlayView?.post { keepOnScreen() }
    }

    /** The whole screen in its current orientation, in pixels. */
    private fun screenSize() = Point().also {
        @Suppress("DEPRECATION")
        defaultDisplay.getRealSize(it)
    }

    /**
     * Updates [barInsets] with the status bar and navigation/gesture bar sizes, whether
     * or not they're currently shown (a full-screen video hides them only until a swipe).
     * Called from keepOnScreen() rather than on every drag step, as it asks the system.
     */
    private fun refreshBarInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val bars = windowManager.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
        )
        barInsets.set(bars.left, bars.top, bars.right, bars.bottom)
    }

    /**
     * Keeps the timer fully on screen. After a rotation it moves to the same
     * relative spot (e.g. bottom-left stays bottom-left) instead of being left at
     * portrait coordinates that are off the landscape screen, or vice versa.
     */
    private fun keepOnScreen() {
        val view = overlayView ?: return
        val p = params ?: return
        if (view.width == 0 || view.height == 0) return // Not laid out yet.
        val screen = screenSize()
        val oldInsets = Rect(lastInsets)
        refreshBarInsets()
        var x = p.x
        var y = p.y
        if (lastScreen.x > 0 && screen != lastScreen) {
            // Same relative spot within the area between the bars, so a timer parked
            // against an edge stays against it after a round trip through landscape.
            x = remap(
                p.x, oldInsets.left, lastScreen.x - oldInsets.right - view.width,
                barInsets.left, screen.x - barInsets.right - view.width
            )
            y = remap(
                p.y, oldInsets.top, lastScreen.y - oldInsets.bottom - view.height,
                barInsets.top, screen.y - barInsets.bottom - view.height
            )
            Log.d(TAG, "Screen ${lastScreen.x}x${lastScreen.y} -> ${screen.x}x${screen.y}: " +
                "timer (${p.x},${p.y}) -> ($x,$y), bars $barInsets")
        }
        moveTo(x, y, screen)
    }

    /** Maps [value] from [oldMin]..[oldMax] to the same relative spot in [newMin]..[newMax]. */
    private fun remap(value: Int, oldMin: Int, oldMax: Int, newMin: Int, newMax: Int): Int {
        if (oldMax <= oldMin) return newMin // It didn't fit before; keep it at the start.
        val fraction = ((value - oldMin).toFloat() / (oldMax - oldMin)).coerceIn(0f, 1f)
        return newMin + (fraction * (newMax - newMin).coerceAtLeast(0)).roundToInt()
    }

    /**
     * Moves the timer to ([x], [y]), clamped so it stays fully on screen and clear of
     * the status bar and navigation/gesture bar, which sit on top and take touches.
     */
    private fun moveTo(x: Int, y: Int, screen: Point = screenSize()) {
        val view = overlayView ?: return
        val p = params ?: return
        lastScreen = screen
        lastInsets.set(barInsets)
        val left = barInsets.left
        val top = barInsets.top
        val newX = x.coerceIn(left, (screen.x - barInsets.right - view.width).coerceAtLeast(left))
        val newY = y.coerceIn(top, (screen.y - barInsets.bottom - view.height).coerceAtLeast(top))
        if (newX != p.x || newY != p.y) {
            p.x = newX
            p.y = newY
            windowManager.updateViewLayout(view, p)
        }
    }

    private fun setupControls(view: View) {
        view.findViewById<View>(R.id.btn_snooze).setOnClickListener { snoozeOverlay(30) }
        view.findViewById<View>(R.id.btn_minimize).setOnClickListener { setMinimized(true) }
        view.findViewById<View>(R.id.btn_expand).setOnClickListener { setMinimized(false) }
        view.findViewById<View>(R.id.btn_dashboard).setOnClickListener { openDashboard() }
        view.findViewById<View>(R.id.btn_open_dashboard).setOnClickListener { openDashboard() }
        view.findViewById<View>(R.id.btn_settings).setOnClickListener {
            val panel = view.findViewById<View>(R.id.settings_panel)
            panel.isVisible = !panel.isVisible
            it.isSelected = panel.isVisible
        }

        OPACITY_CHIPS.forEach { (id, percent) ->
            view.findViewById<View>(id).setOnClickListener { setOpacity(percent) }
        }
        view.findViewById<SeekBar>(R.id.seek_opacity).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) setOpacity(FocusPrefs.MIN_OPACITY + progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            }
        )
        view.findViewById<View>(R.id.chip_pulse_on).setOnClickListener { setPulseEnabled(true) }
        view.findViewById<View>(R.id.chip_pulse_off).setOnClickListener { setPulseEnabled(false) }

        view.findViewById<View>(R.id.btn_limit_minus).setOnClickListener {
            setLimit(FocusPrefs.decreaseLimit(limitMinutes))
        }
        view.findViewById<View>(R.id.btn_limit_plus).setOnClickListener {
            setLimit(FocusPrefs.increaseLimit(limitMinutes))
        }

        HIDE_CHIPS.forEach { (id, seconds) ->
            view.findViewById<View>(id).setOnClickListener { snoozeOverlay(seconds) }
        }
    }

    private fun setLimit(minutes: Int) {
        if (isOverLimit()) return // Over the limit it can only be changed in the app.
        limitMinutes = minutes
        FocusPrefs.setLimitMinutes(this, currentPackageName, minutes)
        updateTimerDisplay()
    }

    private fun setOpacity(percent: Int) {
        // The manual controls are disabled while gradual opacity or the limit decides it.
        if (gradualOpacity || isOverLimit()) return
        opacityPercent = percent.coerceIn(FocusPrefs.MIN_OPACITY, 100)
        FocusPrefs.setOverlayOpacity(this, opacityPercent)
        overlayView?.let { applyOpacity(it) }
    }

    private fun isOverLimit() = limitMinutes > 0 && todayCumulativeSeconds >= limitMinutes * 60

    /** Minimizing or hiding briefly is only allowed early in a session and never over the limit. */
    private fun canMinimize() = !isOverLimit() && sessionSeconds < noMinimizeAfterSeconds

    /** Background opacity right now, in percent. */
    private fun currentOpacity(): Int = when {
        isOverLimit() -> 100
        gradualOpacity ->
            (gradualStart + gradualStep * (sessionSeconds / gradualInterval)).coerceAtMost(100)
        else -> opacityPercent
    }

    /** The opacity setting controls the red background; the pulse fades the whole overlay. */
    private fun applyOpacity(view: View) {
        val percent = currentOpacity()
        val alpha = percent * 255 / 100
        view.findViewById<View>(R.id.card).background.mutate().alpha = alpha
        view.findViewById<View>(R.id.pill).background.mutate().alpha = alpha
        view.findViewById<TextView>(R.id.tv_opacity).text = when {
            isOverLimit() -> "Opacity: 100% while over the daily limit"
            gradualOpacity -> "Opacity: $percent%, rising with the session (set in the FocusRed app)"
            else -> "Opacity: $percent%"
        }
        // The manual controls only apply when nothing else decides the opacity.
        val manual = !gradualOpacity && !isOverLimit()
        view.findViewById<SeekBar>(R.id.seek_opacity).apply {
            isVisible = manual
            progress = percent - FocusPrefs.MIN_OPACITY
        }
        OPACITY_CHIPS.forEach { (id, chipPercent) ->
            view.findViewById<View>(id).apply {
                isVisible = manual
                isSelected = chipPercent == percent
            }
        }
    }

    private fun View.setUsable(usable: Boolean) {
        isEnabled = usable
        alpha = if (usable) 1f else 0.35f
    }

    private fun setPulseEnabled(enabled: Boolean) {
        pulseEnabled = enabled
        FocusPrefs.setPulseEnabled(this, enabled)
        updatePulse()
        overlayView?.let { applyPulseChips(it) }
    }

    /** Pulses when enabled, except over the limit, where the timer stays fully opaque. */
    private fun updatePulse() {
        val shouldPulse = pulseEnabled && !isOverLimit()
        if (shouldPulse && pulseAnimator == null) {
            startPulse()
        } else if (!shouldPulse && pulseAnimator != null) {
            stopPulse()
        }
    }

    private fun applyPulseChips(view: View) {
        view.findViewById<View>(R.id.chip_pulse_on).isSelected = pulseEnabled
        view.findViewById<View>(R.id.chip_pulse_off).isSelected = !pulseEnabled
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        overlayView?.alpha = 1f
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, PULSE_MIN_ALPHA).apply {
            duration = PULSE_HALF_PERIOD_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { overlayView?.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun setMinimized(minimized: Boolean) {
        if (minimized && !canMinimize()) return
        isMinimized = minimized
        minimizedSeconds = 0
        overlayView?.let { applyMinimized(it) }
    }

    /**
     * Enforces the timer rules every second: it may stay minimized only for
     * [maxMinimizedSeconds] and only early in a session; once the daily limit is
     * reached it stays expanded, visible and fully opaque.
     */
    private fun applyRules(view: View) {
        val overLimit = isOverLimit()
        if (isMinimized && (!canMinimize() || minimizedSeconds >= maxMinimizedSeconds)) {
            setMinimized(false)
        }
        // Hiding briefly follows the same rules as minimizing: not after the session cutoff
        // or over the limit, and a hidden timer comes back as soon as either is reached.
        val canHide = canMinimize()
        listOf(R.id.btn_minimize, R.id.btn_snooze).forEach { view.findViewById<View>(it).setUsable(canHide) }
        HIDE_CHIPS.keys.forEach { view.findViewById<View>(it).setUsable(canHide) }
        if (!canHide && view.visibility != View.VISIBLE) {
            handler.removeCallbacks(unsnoozeRunnable)
            unsnoozeRunnable.run()
        }
        // Over the limit nothing on the timer may undo the lock: no raising or removing
        // the limit from here (the app's dashboard still can), and no pulse.
        listOf(R.id.btn_limit_minus, R.id.btn_limit_plus, R.id.chip_pulse_on, R.id.chip_pulse_off)
            .forEach { view.findViewById<View>(it).setUsable(!overLimit) }

        updatePulse()
        applyOpacity(view)
    }

    private fun applyMinimized(view: View) {
        view.findViewById<View>(R.id.card).isVisible = !isMinimized
        view.findViewById<View>(R.id.pill).isVisible = isMinimized
    }

    private fun openDashboard() {
        // AppAccessibilityService hides the overlay once the dashboard is on screen.
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_SHOW_DASHBOARD, true)
        })
    }

    private fun snoozeOverlay(seconds: Int) {
        if (!canMinimize()) return // Same rules as minimizing (session cutoff, limit).
        overlayView?.visibility = View.GONE
        pulseAnimator?.pause()
        handler.removeCallbacks(unsnoozeRunnable)
        handler.postDelayed(unsnoozeRunnable, seconds * 1000L)
    }

    private fun hideOverlay() {
        handler.removeCallbacks(timerRunnable)
        handler.removeCallbacks(unsnoozeRunnable)
        stopPulse()
        params?.let {
            savedX = it.x
            savedY = it.y
        }
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    private fun updateTimerDisplay() {
        val view = overlayView ?: return
        // Follows the phone's 12/24-hour setting, like the status bar clock.
        val clock = DateFormat.format(
            if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm",
            System.currentTimeMillis()
        )
        view.findViewById<TextView>(R.id.tv_clock_time).text = clock
        view.findViewById<TextView>(R.id.tv_pill_clock).text = clock
        val session = formatSecondsDigital(sessionSeconds)
        view.findViewById<TextView>(R.id.tv_session_time).text = session
        view.findViewById<TextView>(R.id.tv_pill_time).text = session
        val today = UsageStatsHelper.formatDuration(todayCumulativeSeconds * 1000L)
        view.findViewById<TextView>(R.id.tv_today_time).text = today
        view.findViewById<TextView>(R.id.tv_pill_today).text = today

        val hasLimit = limitMinutes > 0
        val limitSeconds = limitMinutes * 60
        view.findViewById<View>(R.id.limit_section).isVisible = hasLimit
        view.findViewById<View>(R.id.pill_limit_progress).isVisible = hasLimit
        view.findViewById<View>(R.id.tv_over_limit).isVisible = isOverLimit()
        view.findViewById<TextView>(R.id.tv_limit_value).text = FocusPrefs.formatLimit(limitMinutes)
        if (hasLimit) {
            val percent = (todayCumulativeSeconds * 100L / limitSeconds).toInt().coerceAtMost(100)
            view.findViewById<TextView>(R.id.tv_limit).text = buildSpannedString {
                append("Limit: ")
                bold { append(FocusPrefs.formatLimit(limitMinutes)) }
            }
            view.findViewById<TextView>(R.id.tv_limit_percent).text = "$percent%"
            view.findViewById<ProgressBar>(R.id.limit_progress).progress = percent
            view.findViewById<ProgressBar>(R.id.pill_limit_progress).progress = percent
        }
        applyRules(view)
    }

    private fun startForegroundServiceNotification() {
        val channelId = "focus_red_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "FocusRed Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FocusRed Active")
            .setContentText("Monitoring social media screen time")
            .setSmallIcon(R.drawable.ic_timer)
            .build()

        startForeground(1001, notification)
    }

    private fun formatSecondsDigital(s: Int): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", m, sec)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
    }
}
