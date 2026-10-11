# FocusRed - Galaxy S21 Ultra Social Media Timer
This app runs a floating semi-transparent red timer over social media apps showing, in yellow, the clock, the current session time and today's cumulative usage. Minimized, it still shows the clock and session time.

## Galaxy S21 Ultra Setup:
1. Open project in Android Studio (Hedgehog / Iguana / Jellyfish).
2. Connect your Galaxy S21 Ultra via USB or Wi-Fi debugging.
3. Click "Run" (Shift + F10) to install APK.
4. On your Galaxy S21 Ultra:
   - Go to Settings > Apps > Special access > Appear on top > Enable FocusRed.
   - Go to Settings > Apps > Special access > Usage data access > Enable FocusRed.
   - Go to Settings > Accessibility > Installed apps > Enable FocusRed Accessibility Service.

## Dashboard & limits
- The FocusRed screen shows a weekly dashboard below the permission checklist: time per day for the last 7 days, time per app, this week vs last week and the most-used app. Times are rebuilt from `UsageStatsManager.queryEvents` activity events.
- Android only keeps ~10 days of events, so FocusRed caches each finished day; "last week" becomes complete after it has been running for a week.
- Each app has a daily limit (default 1h, set with −/+ on the dashboard or in the timer's controls panel). The floating timer shows a yellow progress bar and an "Over Limit" badge.
- The floating timer pulses by default; turn it off with "Pulse: Off" in the timer's controls panel (sliders icon).

## Floating timer rules (set in the app's "Floating timer" section)
- The timer can be minimized to a pill (clock, session, today's total and limit bar), but only for a limited time (default 30 s) and only early in a session: after a session of 5 min (adjustable) in an app it can't be minimized for that app.
- Gradual opacity (on by default): the background starts at 65% and rises 10% every 30 s of the session (all adjustable). When it's off, the opacity from the timer's controls panel is used.
- Once an app's daily limit is reached, the timer stays expanded, fully opaque and visible: no minimizing, no hiding, no pulse.