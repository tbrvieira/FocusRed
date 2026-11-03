package com.focusred.timer

import android.graphics.Color

/**
 * A tracked app. [packageName] is the key for its limits, stats and pauses;
 * [variants] are other packages of the same app (regional builds, Lite versions)
 * whose time counts as this app's.
 */
data class SocialApp(
    val packageName: String,
    val label: String,
    val color: Int,
    val variants: Set<String> = emptySet()
)

/** The social media apps FocusRed tracks, in display order. */
object SocialApps {

    val all = listOf(
        SocialApp(
            "com.instagram.android", "Instagram", Color.parseColor("#E1306C"),
            setOf("com.instagram.lite")
        ),
        SocialApp(
            "com.zhiliaoapp.musically", "TikTok", Color.parseColor("#00B8C4"),
            setOf(
                "com.ss.android.ugc.trill",          // TikTok in parts of Asia
                "com.zhiliaoapp.musically.go",       // TikTok Lite
                "com.ss.android.ugc.tiktok.lite"     // TikTok Lite (other regions)
            )
        ),
        SocialApp("com.google.android.youtube", "YouTube", Color.parseColor("#FF0000")),
        SocialApp("com.twitter.android", "X", Color.parseColor("#1D9BF0")),
        SocialApp("com.reddit.frontpage", "Reddit", Color.parseColor("#FF4500")),
        SocialApp(
            "com.facebook.katana", "Facebook", Color.parseColor("#1877F2"),
            setOf("com.facebook.lite")
        ),
        SocialApp("com.instagram.barcelona", "Threads", Color.parseColor("#737373"))
    )

    /** The main package of every tracked app (the keys used everywhere). */
    val packages: Set<String> = all.map { it.packageName }.toSet()

    private val byAnyPackage: Map<String, String> = all.flatMap { app ->
        (app.variants + app.packageName).map { it to app.packageName }
    }.toMap()

    /** Every package FocusRed watches for, variants included. */
    val allPackages: Set<String> = byAnyPackage.keys

    /** The main package for [packageName] (itself for a main package), or null if untracked. */
    fun canonical(packageName: String): String? = byAnyPackage[packageName]

    fun label(packageName: String): String =
        all.find { it.packageName == canonical(packageName) }?.label ?: "Social"
}
