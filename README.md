# GNDEC Timetable — Lecture Reminder App

Native Android app for Guru Nanak Dev Engineering College (GNDEC) students.
It fetches the official college timetable, parses it (deterministic HTML parsing first,
Groq AI only for ambiguous cells), shows your **next lecture**, and fires **local,
offline-capable lecture reminders** via `AlarmManager` — no network needed at reminder time.

## Features

- Dynamic group discovery from the official timetable HTML (nothing hard-coded; default group ITB2)
- Offline-first: Room-cached timetable; app works fully without internet
- "Last fetched" freshness indicator + manual **↻ Fetch again** (ETag / Last-Modified aware)
- Next-lecture card with live countdown, happening-now state, free-period detection
- Full day view with completed / happening / upcoming / free states
- Reference-style Home, Today, Alerts, Profile, and onboarding surfaces with a fixed-height bottom navigation
- Local lecture reminders (15 min before / at start, configurable) with a custom notification sound
- Exact alarms with graceful fallback + reliability checklist (battery, permissions)
- Boot receiver reschedules alarms after device reboot
- Hybrid parsing: deterministic parser is authoritative for group/day/time; Groq AI only fills
  ambiguous subject/teacher/venue/type fields, with local AI-result caching
- Bring-your-own Groq API key (encrypted storage) or restricted developer backend
- Dynamic Groq model list (models API) + custom model ID — no APK update needed for model changes
- Official GNDEC 2026 student lookup by branch PDF, duplicate-name disambiguation, local cache, and manual fallback

## Architecture

```
GNDEC official site → fetch HTML (ETag/Last-Modified) → Jsoup parser →
raw timetable → (deterministic | Groq AI for ambiguous cells) → validation →
Room → ┬ Home Screen (instant, offline)
       └ AlarmManager → BroadcastReceiver → Notification  (internet NOT needed)

GNDEC 2026 branch PDF → OkHttp → on-device PDF text extraction →
student directory cache → name search → profile + temporary subsection/group
```

Backend (optional, in `/backend`) is only a restricted cell-normalization proxy with a
server-side `GROQ_API_KEY` secret. Push/FCM is not required for lecture reminders.

## Official 2026 student profile lookup

During first-time setup, the app lets the student choose CE, CS, EC, EE, IT, ME, or RAI. It downloads the corresponding **2026 Batch Temporary Section details** PDF from GNDEC’s official timetable page, extracts the student directory on-device, and keeps the parsed directory locally for offline reuse. Typing a name filters the branch list; if the same name appears more than once, the choices include the registration number so the student can identify the correct record.

After a match is selected, the app saves the candidate name, registration number, Sr. No. as the roll-number field, branch, temporary section, temporary subsection/timetable group, and mentor name. The Profile screen provides the same branch refresh and search workflow later, including a manual-entry fallback if the official PDF is unavailable or a correction is needed. The source PDFs are the seven branch links under the **2026 Batch Temporary Section details** section at [GNDEC’s timetable page](https://appsc.gndec.ac.in/time_tables).

## Build

Requires JDK 17+ and the Android SDK (installed automatically on CI).

```bash
./gradlew testDebugUnitTest   # run unit tests
./gradlew assembleDebug       # build APK → app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Releases

Published APKs are distributed through the repository’s GitHub Releases page. The app’s **Download update** action opens the latest stable APK asset directly.

## Security

- No Groq key is embedded in the APK or committed to the repo.
- User-provided keys are stored with Android Keystore-backed encrypted preferences.
- Keys are never logged, never sent to the backend, never included in analytics/crash logs.
- Student directory and profile data remain local to the device after download.

## Reliability disclaimer

The app maximizes practical reliability (exact alarms, boot recovery, reliability
checklist) but cannot *guarantee* notifications: OEM battery restrictions, disabled
notifications, Do Not Disturb or a powered-off device can affect delivery. The app
detects and surfaces these conditions in Settings → Notification Reliability.

## Announcements for all users

The app reads [`announcements.json`](announcements.json) from the public `main` branch through GitHub’s raw-content endpoint. This is a lightweight broadcast feed rather than a real-time push service: devices check it when the app opens and during the existing network-constrained background refresh, then show each new announcement once as a local notification and on the **Home** screen card.

To publish an announcement from a phone, open the repository on GitHub, open `announcements.json`, choose **Edit**, and add an object inside the `announcements` array. Commit the change to `main`; installed apps will discover it on their next feed check. The in-app **Settings → App updates → Manage on GitHub** shortcut opens the mobile edit page for this file.

### Field reference

| Field | Required | Description |
|-------|----------|-------------|
| `id` | yes | Stable unique string. Changing `id` is treated as a **new** announcement (notification can fire again). |
| `title` | yes | Short headline shown on the Home card and in the notification. |
| `message` | yes | Body text. Supports lightweight **markdown** (see below). |
| `publishedAt` | recommended | ISO-8601 timestamp (e.g. `2026-09-16T10:00:00Z`). The app shows the **latest** active item by this value. |
| `type` | no | Visual style of the Home card. Default: `info`. |
| `link` | no | Optional URL. When set, **tapping anywhere on the card** opens it. When omitted or `""`, the card is not clickable. |
| `active` | no | Set `false` to hide without deleting. Default: `true`. |

Only **active** announcements with non-blank `id`, `title`, and `message` are considered. Among those, the one with the greatest `publishedAt` is shown.

### Types (Home card style)

| `type` | Emoji / icon | Use for |
|--------|--------------|---------|
| `info` (default) | Campaign icon | Neutral updates |
| `notice` | 📌 | Official notices, date sheets, formal info |
| `warn` / `warning` | ⚠️ | Amber card — closures, schedule changes, caution |
| `happy` / `celebrate` | 🎉 | Green festive card — good news, results, events |
| `urgent` / `critical` / `alert` | 🚨 | Red card — time-sensitive or critical alerts |
| `update` / `feature` | ✨ | Sky/cyan card — app or feature updates |

### Markdown in `message`

The Home card renders a small markdown subset in the body:

- `**bold**`
- `*italic*` or `_italic_`
- `[label](https://example.com)` — tappable link
- Bare `https://…` URLs — also tappable

Inline links stay independently tappable even when the card has a top-level `link`. Newlines in the JSON string become line breaks on the card.

### Whole-card link

- **With** `"link": "https://…"` → the entire announcement block is clickable and opens that URL; a small “Tap card to open” hint is shown.
- **Without** `link` (or empty string) → the block is not clickable as a whole; only markdown links inside `message` work.

### Example

```json
{
  "version": 2,
  "announcements": [
    {
      "id": "os1-lab-closed-2026-09-16",
      "title": "OS1 Lab closed today",
      "message": "The **OS1 Lab** is closed for maintenance.\nDetails on the [notice board](https://example.com/notice).",
      "publishedAt": "2026-09-16T10:00:00Z",
      "type": "warn",
      "link": "https://example.com/full-notice",
      "active": true
    },
    {
      "id": "mse1-date-sheet-2026-09",
      "title": "MSE-1 Date Sheet",
      "message": "**Chemistry Group:**\n25 Sep — Chemistry — 9:15–10:45 AM\n\n**Physics Group:**\n25 Sep — Physics — 12:45–2:15 PM",
      "publishedAt": "2026-09-13T12:00:00Z",
      "type": "notice",
      "link": "",
      "active": true
    }
  ]
}
```

In the first example, tapping the card opens `link`; tapping “notice board” in the body opens that URL only.

### Delivery notes

This feed is intentionally separate from lecture reminders. Lecture reminders remain local and offline-capable; announcement delivery depends on Android allowing the periodic check and on the device having connectivity at check time. The latest announcement is also cached on-device (including `type` and `link`) so the Home card can still render offline after the first successful fetch.

## GitHub release updates

The app checks the latest stable GitHub release from `https://api.github.com/repos/lsgzt/nextlecture-android/releases/latest` on startup when the cached result is older than six hours and during the existing 12-hour background refresh. Settings also provides **Check for updates**.

Android `versionName` is currently `1.6.0`, while `BuildConfig.RELEASE_MARKER` is the separate GitHub release marker `1.6`. The two values are compared numerically, so a GitHub release tagged `1.6` is treated as the same version and a future marker such as `1.7` will be treated as newer. Update both version values in `app/build.gradle.kts` whenever publishing a new release.

When a newer stable release is found, Home and Settings show an update card and the app may send one update notification per release marker. **Download update** opens `https://github.com/lsgzt/nextlecture-android/releases/latest/download/gndec-timetable.apk`. Android still requires the user to confirm the APK installation.
.
