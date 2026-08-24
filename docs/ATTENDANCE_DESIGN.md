# Attendance feature design

## Scope

Attendance is an additive feature. Existing timetable, notifications, PYQ RAG, and bundled Previous year papers behavior must remain unchanged.

## Identity and privacy

The current app has no Supabase Auth session. Attendance therefore uses a high-entropy bearer session issued by the existing Vercel API. The app generates a random installation ID, registers the saved student profile fingerprint, receives an access token, and stores the token only in Android Keystore-backed encrypted preferences. Supabase stores only a SHA-256 hash of the installation ID and access token, plus branch/subsection and the opaque profile fingerprint; it does not store the student name, registration number, or CRN in the attendance tables.

The server first recognizes the current installation scoped to the active profile and otherwise recovers the same attendance owner by the saved profile fingerprint. This means a reinstall can reclaim the student's existing server-side attendance records after the same saved profile is entered again, while switching profiles on one installation cannot reuse the previous student's owner. The Android client also revokes its cached attendance session before every profile save or automatic profile migration. Records remain associated with the opaque student-profile identity rather than a plaintext name. It is still not an account system and does not synchronize attendance across phones. A future authenticated account/OTP flow can replace it without changing the attendance-record schema.

## Supabase tables

`attendance_students` stores one server-side attendance owner per saved student profile, with a bearer session scoped to the current installation and active profile. `attendance_records` stores one immutable lecture identity per student/date/lecture key, with an upsertable `status` of `present` or `absent`. Unmarked lectures are not counted as present or absent.

The unique key is `(student_id, attendance_date, lecture_key)`. The client derives `lecture_key` deterministically from subsection, date, start/end minutes, subject, and venue. Each record also stores subject, teacher, venue, and start/end minutes so history remains readable even after a timetable refresh.

RLS is enabled and direct table/function access is revoked from `public`, `anon`, and `authenticated`. Only the existing server-side Supabase service-role connection may access these tables through narrow attendance API routes.

## API

- `POST /api/attendance/session`: rate-limited registration/rotation; returns an access token. No credentials are required, but the token is returned only once and is never logged.
- `GET /api/attendance?from=YYYY-MM-DD&to=YYYY-MM-DD&target=75`: bearer-authenticated records and deterministic summary.
- `POST /api/attendance/records`: bearer-authenticated idempotent upsert of a present/absent mark.
- `DELETE /api/attendance/records/:date/:lectureKey`: bearer-authenticated unmark operation.

Percentage is `present / (present + absent) * 100`; unmarked lectures are excluded. For target `q`, maximum further misses while maintaining target is `max(0, floor(present / q - markedTotal))`. If below target, the response also reports the number of consecutive classes that must be attended to recover to target.

## Android UX

- Profile gets a `View attendance` action.
- Lecture details gets a `Mark attendance` card with `Present` and `Absent` choices; updating an existing mark is allowed.
- Attendance screen shows percentage, present/absent/unmarked meaning, target percentage controls, maximum affordable misses, and recovery guidance. The selected target is persisted locally in DataStore and restored whenever the attendance screen is reopened.
- A date selector and date-wise lecture list allow backfilling previous lectures. The list is generated from the locally saved subsection timetable for the selected weekday, while the marked state comes from the server.
- A compact calendar/history section shows marked dates with present/absent color states and opens the selected date’s lecture list.
- Marking requires connectivity so the server remains authoritative. Failed writes remain visible as an error and never pretend that a mark was stored.
