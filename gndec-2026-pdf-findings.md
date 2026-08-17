# GNDEC 2026 temporary-section PDF findings

Source page: https://appsc.gndec.ac.in/time_tables

The official page labels the relevant section **Sem 1 (Jul 2026 - Dec 2026)** and **2026 Batch Temporary Section details**. It provides one branch PDF each for CE, CS, EC, EE, IT, ME, and RAI.

| Branch | PDF | Columns observed | Section examples |
|---|---|---|---|
| CE | `CE Branch Temporary Sections 2026_0.pdf` | Sr. No., Candidate Name, Registration No., Branch, T-Section, T-Subsection, Mentor Name | CEA/CEA1, CEA2, CEB/CEB1, CEB2 |
| CS | `CS Branch Temporary Sections 2026_0.pdf` | Sr. No., Candidate Name, Registration No., Branch, T-Section, T-subsection, Mentor Name | CSA/CSA1, CSA2, CSB/CSB1, CSB2, CSC, CSD, CSE, CSF |
| EC | `EC Branch Temporary Sections 2026_0.pdf` | Sr. No., Candidate Name, Registration No., Branch, T-Section, T-Subsection, Mentor Name | ECA/ECA1, ECA2, ECB/ECB1, ECB2 |
| EE | `EE Branch Temporary Sections 2026_0.pdf` | Sr. No., Candidate Name, Registration No., Branch, T-Section, T-Subsection, Mentor Name | EEA/EEA1, EEA2, EEB/EEB1, EEB2 |
| IT | `IT Branch Temporary Sections 2026_0.pdf` | Sr. No., Candidate Name, Registration No., Branch, T-Section, T-Subsection, Mentor Name | ITA/ITA1, ITA2, ITB/ITB1, ITB2, ITC/ITC1, ITC2 |
| ME | `ME Branch Temporary Sections 2026_0.pdf` | Sr. No., Candidate Name, Registration No., Branch, T-Section, T-Subsection, Mentor Name | MEA/MEA1, MEA2, MEB/MEB1, MEB2 |
| RAI | `RAI Branch Temporary Sections 2026_0.pdf` | Sr. No., Candidate Name, Registration No., Branch, T-Section, T-Subsection, Mentor | RAI/RAI1, RAI2 |

Important implementation facts:

1. Search should be case-insensitive and accent/whitespace tolerant. The PDFs contain duplicate names with different registration numbers, so the UI must show `NAME (registration)` for duplicate-name candidates.
2. On selection, persist at minimum: name, registration number, Sr. No. as roll number, branch, T-Section, T-Subsection, and mentor name. The PDFs do not provide a separate field named roll number; **Sr. No. is the requested roll-number source**.
3. Some extracted PDF text has missing whitespace between the name and registration number, for example `GUNPREET KAUR RANDHAWA26011932` and `DEEPANKARPREET SINGH MAAN26011965`. A parser should use the 8-digit registration number as the delimiter and parse the preceding name safely.
4. The branch PDF routing is deterministic: CE → CE PDF, CS → CS PDF, EC → EC PDF, EE → EE PDF, IT → IT PDF, ME → ME PDF, RAI → RAI PDF. The temporary section (e.g. ITB2) is selected from the matched student record, not used to choose a separate PDF.
5. The app should download and cache the parsed branch records locally, refresh them from Profile/onboarding when requested, and continue using the cached records when offline. If downloading/parsing fails, manual profile entry remains available.

Reference PDFs:
- https://appsc.gndec.ac.in/sites/default/files/2026-08/CE%20Branch%20Temporary%20Sections%202026_0.pdf
- https://appsc.gndec.ac.in/sites/default/files/2026-08/CS%20Branch%20Temporary%20Sections%202026_0.pdf
- https://appsc.gndec.ac.in/sites/default/files/2026-08/EC%20Branch%20Temporary%20Sections%202026_0.pdf
- https://appsc.gndec.ac.in/sites/default/files/2026-08/EE%20Branch%20Temporary%20Sections%202026_0.pdf
- https://appsc.gndec.ac.in/sites/default/files/2026-08/IT%20Branch%20Temporary%20Sections%202026_0.pdf
- https://appsc.gndec.ac.in/sites/default/files/2026-08/ME%20Branch%20Temporary%20Sections%202026_0.pdf
- https://appsc.gndec.ac.in/sites/default/files/2026-08/RAI%20Branch%20Temporary%20Sections%202026_0.pdf
