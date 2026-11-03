package com.focusred.timer

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Real-time Foreground App Detector
 * Automatically launches the floating red timer when social media apps are opened.
 */
class AppAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FocusRed"
    }

    // Variants (e.g. TikTok Lite) included; the overlay is told the main package.
    private val monitoredPackages = SocialApps.allPackages

    private val ignoredPackages = setOf(
        "com.android.systemui",
        "com.samsung.android.honeyboard"  // Samsung Keyboard
    )

    private var lastForegroundPackage: String? = null

    // "package/class" -> whether that class is an activity (cached PackageManager lookups).
    private val activityCache = HashMap<String, Boolean>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            val className = event.className?.toString()

            // Ignore our own overlay, the system UI (notification shade) and keyboards,
            // otherwise the timer would disappear whenever they appear on screen.
            // FocusRed's own dashboard does count as leaving the social app.
            val isOwnDashboard = packageName == this.packageName &&
                className == MainActivity::class.java.name
            if ((packageName == this.packageName && !isOwnDashboard) ||
                packageName in ignoredPackages ||
                packageName.contains("inputmethod") ||
                packageName.contains("keyboard")
            ) return

            // Pop-ups, dialogs, the "viewing full screen" hint, edge panels and other
            // system windows also send this event (e.g. when a video goes full screen or
            // the screen rotates) without the user leaving the social app. Only a real
            // app screen (an activity) of another app counts as leaving it.
            if (packageName !in monitoredPackages && !isOwnDashboard &&
                !isActivity(packageName, className)
            ) {
                if (packageName != lastForegroundPackage) {
                    Log.d(TAG, "Ignored non-activity window: $packageName/$className")
                }
                return
            }

            if (packageName != lastForegroundPackage) {
                Log.d(TAG, "Foreground: $packageName/$className")
                lastForegroundPackage = packageName

                if (monitoredPackages.contains(packageName)) {
                    // Launch Overlay Service
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = "ACTION_SHOW_OVERLAY"
                        putExtra("PACKAGE_NAME", SocialApps.canonical(packageName))
                    }
                    startService(intent)
                } else {
                    // Hide Overlay
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = "ACTION_HIDE_OVERLAY"
                    }
                    startService(intent)
                }
            }
        }
    }

    /**
     * True if [className] is an activity of [packageName] (QUERY_ALL_PACKAGES makes
     * every installed app visible). Windows of apps installed only in another profile,
     * such as Secure Folder, count as not an activity.
     */
    private fun isActivity(packageName: String, className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        return activityCache.getOrPut("$packageName/$className") {
            val component = ComponentName(packageName, className)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getActivityInfo(component, 0)
                }
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    override fun onInterrupt() {}
}
