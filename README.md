# FocusRed - Social Media Timer for Android
This app runs a floating semi-transparent red timer over social media apps showing, in yellow, the clock, the current session time and today's cumulative usage. Minimized, it still shows the clock and session time.

It was built and tested on a Galaxy S21 Ultra, but uses only standard Android features, so it should work on other Android 14+ phones (Pixel, Motorola, Xiaomi, OnePlus, etc.). Tracked apps: Instagram (and Instagram Lite), TikTok (including the Asian build and TikTok Lite), YouTube, X, Reddit, Facebook (and Facebook Lite) and Threads. Time in a Lite or regional version counts toward the main app.

## Install
1. Open the project in Android Studio (Hedgehog or newer).
2. Connect the phone via USB or Wi-Fi debugging (Settings > About phone > tap "Build number" 7 times, then Settings > System > Developer options > USB debugging / Wireless debugging).
3. Click "Run" (Shift + F10) to install the APK.

If you install the APK file another way (browser, file manager), Android 13+ blocks turning on the accessibility service at first: open Settings > Apps > FocusRed, tap ⋮ (top right) > "Allow restricted settings", then try again.

## Permissions
Open FocusRed and tap each button in its checklist; they open the right Settings page on any phone. The names differ by brand:

| Permission | Samsung (One UI) | Pixel / stock Android | Xiaomi (HyperOS / MIUI) |
|---|---|---|---|
| Overlay | Apps > Special access > Appear on top | Apps > Special app access > Display over other apps | Apps > Manage apps > FocusRed > Other permissions > Display pop-up windows |
| Usage data | Apps > Special access > Usage data access | Apps > Special app access > Usage access | Privacy > Special permissions > Apps with usage access |
| Accessibility | Accessibility > Installed apps > FocusRed | Accessibility > FocusRed | Additional settings > Accessibility > Downloaded apps > FocusRed |
| Notifications | Allowed from the app's prompt | Allowed from the app's prompt | Allowed from the app's prompt |
| Battery optimization | Apps > FocusRed > Battery > Unrestricted | Apps > FocusRed > App battery usage > Unrestricted | Apps > Manage apps > FocusRed > Battery saver > No restrictions |

## Keeping FocusRed running on stricter phones
Some brands close background apps aggressively, which stops the timer from appearing (or turns the accessibility service off). Besides turning off battery optimization:
- **Xiaomi / Redmi / POCO:** in App info turn on **Autostart**, and under Other permissions allow **"Display pop-up windows while running in the background"**.
- **OnePlus / Oppo / Realme:** in App info > Battery allow **background activity** (and **Auto launch** if shown).
- **Vivo:** allow **Autostart** / **High background power consumption** in the battery or i Manager settings.
- **Huawei / Honor:** Battery > App launch > FocusRed > **Manage manually**, with all three switches on.
- **Any phone:** open Recent apps and **lock** FocusRed (long-press its card, or the lock/padlock icon).

If the timer stops appearing, open FocusRed: a ❌ in the checklist shows which permission was turned off.

## Dashboard & limits
- The FocusRed screen shows a weekly dashboard below the permission checklist: time per day for the last 7 days, time per app, this week vs last week and the most-used app. Times are rebuilt from `UsageStatsManager.queryEvents` activity events.
- Android only keeps ~10 days of events, so FocusRed caches each finished day; "last week" becomes complete after it has been running for a week.
- Each app has a daily limit (default 1h, set with −/+ on the dashboard or in the timer's controls panel). The floating timer shows a yellow progress bar and an "Over Limit" badge.
- The floating timer pulses by default; turn it off with "Pulse: Off" in the timer's controls panel (sliders icon).

## Floating timer rules (set in the app's "Floating timer" section)
- A session is the time spent in one app (paused time included). It ends once you've been away from the app for 5 minutes (time with the phone locked counts as away).
- The timer can be minimized to a pill (clock, session, today's total and limit bar), but only for a limited time (default 30 s) and only early in a session: after a session of 5 min (adjustable) in an app it can't be minimized or hidden briefly for that app.
- Gradual opacity (on by default): the background starts at 65% and rises 10% every 30 s of the session (all adjustable). When it's off, the opacity from the timer's controls panel is used.
- Once an app's daily limit is reached, the timer stays expanded, fully opaque and visible: no minimizing, no hiding, no pulse, and the limit can only be changed in the app.
- "Always allow minimizing" (off by default) lets you minimize the timer at any time, also after the session cutoff and over the limit, and it then stays minimized until you expand it. Hiding briefly keeps the strict rules.
- "Pause the timer" turns the timer off for one app for 15 min to 2 h or until resumed, e.g. for full-screen viewing. Usage still counts toward stats and limits.