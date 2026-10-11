package com.focusred.timer

import android.graphics.Color

data class SocialApp(val packageName: String, val label: String, val color: Int)

/** The social media apps FocusRed tracks, in display order. */
object SocialApps {

    val all = listOf(
        SocialApp("com.instagram.android", "Instagram", Color.parseColor("#E1306C")),
        SocialApp("com.zhiliaoapp.musically", "TikTok", Color.parseColor("#00B8C4")),
        SocialApp("com.google.android.youtube", "YouTube", Color.parseColor("#FF0000")),
        SocialApp("com.twitter.android", "X", Color.parseColor("#1D9BF0")),
        SocialApp("com.reddit.frontpage", "Reddit", Color.parseColor("#FF4500")),
        SocialApp("com.facebook.katana", "Facebook", Color.parseColor("#1877F2")),
        SocialApp("com.instagram.barcelona", "Threads", Color.parseColor("#737373"))
    )

    val packages: Set<String> = all.map { it.packageName }.toSet()

    fun label(packageName: String): String =
        all.find { it.packageName == packageName }?.label ?: "Social"
}
