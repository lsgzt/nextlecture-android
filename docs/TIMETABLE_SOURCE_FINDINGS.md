# Timetable source findings

Checked on 2026-08-24.

The permanent official page is https://appsc.gndec.ac.in/time_tables. Its newest timetable block is headed “Revised Time Table w.e.f. 24-08-2026” and its first “Sub-section wise Time Table” link points to:
https://appsc.gndec.ac.in/sites/default/files/2026-08/23_08_2026%20FINAL_FILE%20R4_subgroups_days_horizontal.html

The page lists timetable revisions in newest-first order, with a “Sub-section wise Time Table” link in each revision block. The first matching sub-section timetable link in document order is therefore the current source as of this check. Older links include the 09-08-2026 and February 2026 revisions.

Proposed behavior: resolve the current sub-section timetable link from the permanent index on every foreground refresh, use the configured server URL only as fallback if index discovery fails, and avoid shipping a hard-coded dated URL in the APK. The resolver must restrict matches to anchor text containing “Sub-section wise” / “Sub-section wise Time Table” and allow only HTTPS appsc.gndec.ac.in URLs to prevent arbitrary redirect sources.
