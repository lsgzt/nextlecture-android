# GNDEC Room Timetable Sources — Research Findings (2026-08-31)

## The bug (F19 / F-119)
NextLecture v2.4.24 derived vacant rooms from ONLY the Applied Sciences "whole-college"
file (`appsc.gndec.ac.in .../30_08_2026 FINAL_FILE_rooms_days_horizontal.html`, 97 rooms).
That file does NOT contain department-level classes for several programs (B.Tech 2nd/3rd/4th
year dept sections, D4 electives, MCA evenings, MBA...). IT department's own file shows
F-119 heavily occupied (e.g. Mon 08:30 D2IT_A DL, Mon 09:30 D2IT_A DCCN, Mon 10:30 D1IT_A2 MC)
while the appsc file shows F119 FREE at those slots -> app wrongly reported F119 vacant.
(The user's "F19" = F119; no plain "F19" room exists in any file; app search "F19"
substring-matches F119.)

## Where room timetables live (discovery roots, newest-listed-first verified)
| Dept | Root | Room link label | Newest file (2026-08-31) | FET dialect | generated |
|------|------|-----------------|--------------------------|-------------|-----------|
| AppSc (college-wide) | https://appsc.gndec.ac.in/time_tables | "Room Time Table" | 30_08_2026 FINAL_FILE_rooms_days_horizontal.html | A (FET 7.6.4) | 8/30/26 |
| CSE | https://cse.gndec.ac.in/?q=node/5 | "Class Rooms" | TT July December 2026_rooms_days_horizontal (1).html | **B (FET 6.13.2)** | 8/24/26 |
| ECE | https://ece.gndec.ac.in/?q=node/5 | "Rooms Time Table" | rooms tt_3.html | A | 8/14/26 |
| EE  | https://ee.gndec.ac.in/?q=node/5 | "Room Time Table for Aug 2026-Dec 2026" | TT aug2026 (1)_rooms_days_horizontal_0.html | A | 8/11/26 |
| ME  | https://me.gndec.ac.in/?q=node/5 | **none** (only Class/Teacher) | fallback: july to dec 2026_groups_days_horizontal_0.html (room divs inside cells) | A | - |
| CE  | https://ce.gndec.ac.in/?q=node/5 | "Rooms [w.e.f 10.08.2026]" | TT_10_8_26_rooms_days_horizontal.html | A | 8/9/26 |
| IT  | https://it.gndec.ac.in/?q=node/5 | "Room Time Table" | july-dec 2026-27final26-27-8_rooms_days_horizontal.html | A | 8/27/26 |
| MCA | https://mca.gndec.ac.in/?q=node/5 | "Room Wise Time Table for Session Jul-Dec" | ca_July26_rooms_days_horizontal.html | A (10 slots!) | 7/26/26 |
| MBA | https://mba.gndec.ac.in/?q=node/5 | "Room Wise TimeTable ." | T2_DAT_1.FET_rooms_days_horizontal.html — **STALE (generated 3/3/26, Jan-May 2026 session)** | A | REJECT |

## FET HTML dialects
- **Dialect A (FET 7.x)**: `<caption><span class="institution">…</span><br/><span class="name">ROOM</span></caption>`;
  cells `<td class="empty">---` or `<td class="s_N at_N ss_N t_N">` with `span.subject`,
  `div.teacher`, `div.studentsset`, `span.activitytag`, and **room spans in groups files**:
  `<div class="room line3"><span class="r_N">F119</span></div>`.
  yAxis variants: "08:30" (appsc/CSE-no wait CSE is B), "8:30", "1:30" (IT = 12h PM implied),
  "8.30 AM (1ST)" (CE), ranges NO.
- **Dialect B (FET 6.x, CSE)**: caption = institution only; room name = `<th colspan="N">G12</th>`
  in first thead row; yAxis = "08:30-09:30" ranges (9 slots incl 16:30-17:30); cells plain text
  `<td>D3 CS C<br />Dr. … (MKM)<br />DAA L<br /></td>`, empty = `---` or blank, no classes.
- Footer both dialects: `Timetable generated with FET x.y.z on M/D/YY H:MM[ ]AM/PM`
  (used for staleness check). NBSP U+202F narrow space sometimes before PM.

## Slot systems
- appsc/CSE-ish: 8:30..15:30 hourly (appsc 8 slots). CSE: 08:30-17:30 (9 slots).
- IT/ECE/EE: 8:30..15:30 with 12h labels 1:30/2:30/3:30.
- CE: "8.30 AM (1ST)" style. MCA: 10 slots 8:30..17:30. Merge = union of slot start minutes.
- Rule: no meridiem & hour<=7 -> PM. "08:30-09:30" -> start 08:30.

## Room naming across sources (needs normalization)
F119 == F-119 (IT) ; G1 == G-1 (CE) ; S-220 == S220 ; "G 3A" == "G-3A (NR)" ;
"F102(AUTOMOBILE BLOCK)" == "F102 AUTO BLK" == F102 ; "G10 (MPE Dept.)" == G10 ;
"W/S SEMINAR HALL"(appsc) == "WS SEMINAR HALL"(CSE) == "W/S SEM HALL"(EE) == "W/SHOP SEM HALL"(MBA) ;
"PE LAB (BEE LAB 2)" == "PE LAB/ BEE LAB 2" ; "TnP Seminar Hall" == "TNP SEMINAR HALL" ;
"MEAS. LAB" == "MEASUREMENT LAB" ; "ADV. MEAS. LAB" == "ADVANCE MEASUREMENT LAB" ;
"COMP/L(EC)" == "COMP LAB EC" ; "COMP/L(EE)" == "COMP. LAB EE" == "COMP LAB EE" ;
"DBMS_Lab" == "DBMS LAB" ; "f104" == "F104" ; "MBA COMP LAB"(CSE) == "COMP LAB MBA"(appsc) == "COMP LAB"(MBA file) ;
CSE caption "S202, S203" = merged cell using BOTH rooms.
Placeholders to hide from UI: GHOST ROOM (MBA), TEACH OFFICE/TEACH OFFICE1/FACULTY ROOM/"A|B|C(other Deptt)"/First year room (CE), "   " blank (EE).
F111 (EE + ME groups) and F116 (MBA) exist ONLY in dept files — never in appsc file.

## Coverage gaps (accepted, surfaced as "no data")
- MBA: no current-session file -> contributes nothing (safe).
- ME: no rooms file -> groups-file cell extraction used instead.
- Rooms absent from all valid sources (none known today) simply never appear.

## Semantics
- Room BUSY if ANY valid source has an activity in it at (day, slot-start).
- Vacant ONLY if at least one source covers that (day,slot) for the room and none busy
  ("covered && !busy"). Uncovered slot -> unknown, never shown as vacant.
- Staleness: reject any source file whose FET generation date falls outside the current
  semester window (Jun-Dec / Jan-May of today's date). MBA 3/3/26 -> rejected 2026-08-31.
