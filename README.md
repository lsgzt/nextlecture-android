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
- Local lecture reminders (15 min before / at start, configurable) with a custom notification sound
- Exact alarms with graceful fallback + reliability checklist (battery, permissions)
- Boot receiver reschedules alarms after device reboot
- Hybrid parsing: deterministic parser is authoritative for group/day/time; Groq AI only fills
  ambiguous subject/teacher/venue/type fields, with local AI-result caching
- Bring-your-own Groq API key (encrypted storage) or restricted developer backend
- Dynamic Groq model list (models API) + custom model ID — no APK update needed for model changes

## Architecture

```
GNDEC official site → fetch HTML (ETag/Last-Modified) → Jsoup parser →
raw timetable → (deterministic | Groq AI for ambiguous cells) → validation →
Room → ┬ Home Screen (instant, offline)
       └ AlarmManager → BroadcastReceiver → Notification  (internet NOT needed)
```

Backend (optional, in `/backend`) is only a restricted cell-normalization proxy with a
server-side `GROQ_API_KEY` secret. Push/FCM is not required for lecture reminders.

## Build

Requires JDK 17+ and the Android SDK (installed automatically on CI).

```bash
./gradlew testDebugUnitTest   # run unit tests
./gradlew assembleDebug       # build APK → app/build/outputs/apk/debug/app-debug.apk
```

## Download the APK from GitHub Actions

Every push runs `.github/workflows/build-apk.yml`: it runs the tests, builds the debug
APK, and uploads it as an artifact named **`college-timetable-debug.apk`**
(Actions tab → latest run → Artifacts).

## Security

- No Groq key is embedded in the APK or committed to the repo.
- User-provided keys are stored with Android Keystore-backed encrypted preferences.
- Keys are never logged, never sent to the backend, never included in analytics/crash logs.

## Reliability disclaimer

The app maximizes practical reliability (exact alarms, boot recovery, reliability
checklist) but cannot *guarantee* notifications: OEM battery restrictions, disabled
notifications, Do Not Disturb or a powered-off device can affect delivery. The app
detects and surfaces these conditions in Settings → Notification Reliability.
