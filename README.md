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
- Exam mode: publish a date sheet with `"exam_mode": "on"` to pause lecture reminders and show each student their own exams (see [Exam mode](#exam-mode))

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
| `publishedAt` | recommended | ISO-8601 timestamp (e.g. `2026-09-16T10:00:00Z`). Among announcements that **match the student**, the app shows the one with the greatest `publishedAt`. |
| `type` | no | Visual style of the Home card. Default: `info`. |
| `link` | no | Optional URL. When set, **tapping anywhere on the card** opens it. When omitted or `""`, the card is not clickable. |
| `branch` | no | Audience filter. Empty / `"all"` → every branch. Single value (`"IT"`) or list (`"CS, IT"`) — student matches if their branch is **any** listed value. |
| `section` | no | Audience filter within the branch. Empty / `"all"` → every section. Single or list (`"ITA, ITB"`). |
| `subsection` | no | Audience filter within the section. Empty / `"all"` → every subgroup. Single or list (`"ITB1, ITB2"`). Matched against the student's saved group / subsection. |
| `active` | no | Set `false` to hide without deleting. Default: `true`. |
| `exam_mode` | no | `"on"` switches every student the announcement reaches into **exam mode** and reads `message` as the exam schedule (see [Exam mode](#exam-mode)). Any other value, or leaving it out, is off. |

Only **active** announcements with non-blank `id`, `title`, and `message` that **match the student's profile** are considered. Among those, the one with the greatest `publishedAt` is shown on Home (and may notify once).

### Audience targeting (personalised notices)

Targeting is hierarchical. Leave a field empty (or set `"all"`) to mean “everyone at that level”.

| `branch` | `section` | `subsection` | Who sees it |
|----------|-----------|--------------|-------------|
| *(empty)* / `all` | *(empty)* | *(empty)* | All students |
| `IT` | *(empty)* | *(empty)* | All IT students |
| `CS, IT` | *(empty)* | *(empty)* | All CS **or** IT students |
| `IT` | `ITB` | *(empty)* | All ITB section students |
| `IT` | `ITA, ITB` | *(empty)* | ITA or ITB students in IT |
| `IT` | `ITB` | `ITB2` | Only ITB2 subgroup |
| `IT` | `ITB` | `ITB1, ITB2` | ITB1 or ITB2 |

Lists use commas (or `;` / `|`). Within a field, values are **OR**’d; across fields, conditions are **AND**’d (branch must match **and** section must match, etc.).

Matching is case-insensitive and ignores spaces/hyphens (`itb2` = `ITB2` = `ITB-2`).  
`subsection` is compared to the student’s timetable group / directory subsection (whichever is saved on the device).  
If the student has no branch/section stored yet, only announcements with no targeting (or `all`) apply.

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
- `~~strikethrough~~`
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
      "id": "college-wide-holiday-note",
      "title": "Campus closed Friday",
      "message": "College will remain closed on **Friday** for maintenance.",
      "publishedAt": "2026-09-16T08:00:00Z",
      "type": "notice",
      "branch": "all",
      "section": "",
      "subsection": "",
      "link": "",
      "active": true
    },
    {
      "id": "it-lab-closed-2026-09-16",
      "title": "OS1 Lab closed today",
      "message": "The **OS1 Lab** is closed for maintenance.\nDetails on the [notice board](https://example.com/notice).",
      "publishedAt": "2026-09-16T10:00:00Z",
      "type": "warn",
      "branch": "IT",
      "section": "",
      "subsection": "",
      "link": "https://example.com/full-notice",
      "active": true
    },
    {
      "id": "itb2-mentor-meet",
      "title": "ITB2 mentor meeting",
      "message": "Mentor meeting at **3 PM** in F108.",
      "publishedAt": "2026-09-16T11:00:00Z",
      "type": "info",
      "branch": "IT",
      "section": "ITB",
      "subsection": "ITB2",
      "link": "",
      "active": true
    }
  ]
}
```

- First entry: every student  
- Second: all **IT** students (card opens `link`; body link still works alone)  
- Third: only **ITB2** students  

### Delivery notes

This feed is intentionally separate from lecture reminders. Lecture reminders remain local and offline-capable; announcement delivery depends on Android allowing the periodic check and on the device having connectivity at check time. The latest announcement is also cached on-device (including `type` and `link`) so the Home card can still render offline after the first successful fetch.

## Exam mode

During exams the lecture timetable steps aside: no lecture reminders, no next-lecture card. A date sheet published with `"exam_mode": "on"` switches the app over. The app reads the schedule out of `message`, asks each student which group they sit with, and shows their exams on **Home** and on the **Today** tab for as long as the sheet is on.

### Publishing a date sheet

Add an announcement like any other, with `"exam_mode": "on"`, and write `message` in the schedule syntax described below. This is the complete object:

```json
{
  "id": "mse-ii-date-sheet-november-2026",
  "title": "MSE-II Date Sheet (November 2026)",
  "message": "**Chemistry Group:**\n\n23 Nov — Mathematics-I — 9:15–10:45 AM\n25 Nov — PPS — 9:15–10:45 AM\n26 Nov — Chemistry — 9:15–10:45 AM\n27 Nov — English — 9:15–10:45 AM\n28 Nov — BEEE — 9:15–10:45 AM\n\n**Physics Group:**\n\n23 Nov — Mathematics-I — 9:15–10:45 AM\n25 Nov — PPS — 9:15–10:45 AM\n26 Nov — Physics — 12:45–2:15 PM\n27 Nov — Economics — 12:45–2:15 PM\n28 Nov — EDG — 11:00 AM–12:30 PM (ME A, ME B, CE A, CE B, RAI)\n28 Nov — EDG — 1:00–2:30 PM (EE A, EE B, EC A, EC B)",
  "publishedAt": "2026-11-12T21:52:08+05:30",
  "type": "notice",
  "branch": "all",
  "section": "",
  "subsection": "",
  "link": "",
  "active": true,
  "exam_mode": "on"
}
```

### Schedule syntax

The `message` is read line by line. Three kinds of lines matter:

```text
Chemistry Group:

23 Nov — Mathematics-I — 9:15–10:45 AM
28 Nov — EDG — 11:00 AM–12:30 PM (ME A, ME B, CE A, CE B, RAI)
```

| Line | Rule |
|------|------|
| Group heading | Ends with a colon. Every exam line after it belongs to that group, until the next heading. Bold is fine: `**Chemistry Group:**`. |
| Exam line | `date — subject — time`, one exam per line: `23 Nov — Mathematics-I — 9:15–10:45 AM`. |
| Sections | Optional list in brackets at the end of an exam line: `(ME A, ME B)`. |
| Anything else | Ignored: greetings, instructions, blank lines. |

**Date.** Day then month: `23 Nov`, `23rd November`. A year is optional (`23 Nov 2026`). Without one the app picks the year that puts the date closest to `publishedAt`, so a December sheet with January exams needs no year. Add the year when the exams are more than six months after publication, or when reposting a sheet whose dates have already passed.

**Time.** `start–end` in 12-hour form (`9:15–10:45 AM`) or 24-hour form (`14:00–16:30`). AM/PM may be written once, on the end (`12:45–2:15 PM`) or on the start (`11:00 AM–12:30`). An exam cannot cross midnight.

**Dashes and wording.** Any dash works between the parts: `-`, `–`, `—`. `to` works between the times. Markdown bold, italics and `#` headings are ignored when reading, so the text can still be styled for the notice card.

**Sections.** Separate them with commas; `;`, `|`, `&` and the word "and" also work. A line that lists sections is for those sections only. A line without them is for the whole group. The app compares each listed section with the branch, section and subgroup saved in the student's profile, ignoring case, spaces and hyphens (`ME A` = `ME-A`), and a listed section also covers its subgroups (`ME A` covers `ME A1`).

**Order.** Lines may come in any order; the app sorts the exams by date and time. Month first (`Nov 23 — …`) and subject first (`Mathematics — 23 Nov — …`) are also accepted.

**Unreadable lines.** A line with an impossible date (`31 Feb`), an impossible time (`9:65`) or no time range is skipped on its own. The rest of the sheet still works, and the exam card says how many lines were skipped, so a missed exam is never silent. A 12-hour range that ends before it starts is read across noon where that makes sense: `11:00 AM–12:30` ends at 12:30 PM, `11–12 PM` starts at 11 AM, and a slip such as `9:15 PM–10:45 AM` is read as ending at 10:45 PM. Where it does not (`10:45–9:15 AM`, or a 24-hour range written backwards such as `16:00–14:00`), the line is skipped.

Keep three things in mind:

- One exam per line. Two sessions of the same subject on one day are two lines, as for EDG in the example.
- Every line that ends with a colon is a heading. Do not end an ordinary sentence with a colon on a line of its own unless the exams below it belong to that group.
- Give each new date sheet a new `id`, as for any announcement. That is what notifies students and makes the app ask for their group again.

### What students see

- **Group question.** A sheet with two or more groups makes the app ask which group the student is in, the first time it opens with that sheet and again for every new sheet. The choice can be changed from the big card. A sheet with one group, or without headings, asks nothing.
- **Home.** The big card shows the exam that is running now (with the time left), else the next exam today, else the next exam on a later day. The exams still to come are listed under it. The date sheet's own notice card is left out so the schedule is not shown twice, unless one of its lines could not be read; every other notice, from the college or from this feed, appears as usual.
- **Today tab.** Today, Tomorrow and Full week show the exams in place of the timetable, under a note that exams are ongoing.
- **Reminders.** Lecture reminders stop, including ones already scheduled, and come back when exam mode ends. Announcements and app-update notifications are not affected.

### Ending exam mode

Exam mode lasts exactly as long as `exam_mode` says `"on"`; the app does not infer it from the dates. When the exams are over, change the announcement to `"exam_mode": "off"` (or set `"active": false`) and commit. Until then the Home card says the exams are over and the timetable stays paused.

### Notes

- Devices pick the sheet up on their next feed check: when the app opens, on pull-to-refresh on Home, and in the 12-hour background refresh. Publish it ahead of the first exam. Reminders that were scheduled earlier keep firing until a device has checked the feed.
- `branch`, `section` and `subsection` targeting work as for any announcement, so only the students a sheet reaches enter exam mode. Separate date sheets for different branches can each be their own announcement. If two switched-on sheets reach the same student, the one with the greater `publishedAt` is used.
- If no exam line can be read, the app stays in exam mode, shows the announcement as an ordinary notice card and says that the schedule could not be read. If only some lines can be read, the exams that were read are shown, the exam card says how many lines were skipped and the notice card stays as well. After publishing, check the Home tab: the schedule should be in the exam card at the top with no line reported as skipped, not in a notice card below.

## GitHub release updates

The app checks the latest stable GitHub release from `https://api.github.com/repos/lsgzt/nextlecture-android/releases/latest` on startup when the cached result is older than six hours and during the existing 12-hour background refresh. Settings also provides **Check for updates**.

Android `versionName` is currently `1.6.0`, while `BuildConfig.RELEASE_MARKER` is the separate GitHub release marker `1.6`. The two values are compared numerically, so a GitHub release tagged `1.6` is treated as the same version and a future marker such as `1.7` will be treated as newer. Update both version values in `app/build.gradle.kts` whenever publishing a new release.

When a newer stable release is found, Home and Settings show an update card and the app may send one update notification per release marker. **Download update** opens `https://github.com/lsgzt/nextlecture-android/releases/latest/download/gndec-timetable.apk`. Android still requires the user to confirm the APK installation.
.
