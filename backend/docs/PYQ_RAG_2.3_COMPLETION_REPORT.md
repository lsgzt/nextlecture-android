# GNDEC Previous-Year-Papers RAG — Completion Report

## Executive summary

The production Previous-year-papers RAG add-on is implemented and released as Android **2.3**. The existing **Previous year papers** browser was preserved: its bundled catalog, search behavior, session grouping, metadata, original Google Drive links, and fallback behavior remain independent of the analysis service. A compact **🔥 Frequently Asked** entry point has been added above that browser, with course filtering, repeated-question groups, distinct-paper frequency, exact source-page provenance, exam sessions, and original PDF opening.

The production service is live at [https://gndec-pyq-rag-api.vercel.app](https://gndec-pyq-rag-api.vercel.app). The full catalog of **1,628 known Drive PDFs** has been imported idempotently into Supabase. The initial ten-paper validation gate passed before the full catalog seed: **10/10 validation papers completed, 171 questions extracted, 8 papers routed through vision extraction, and 2 through conventional text extraction**. Additional protected production batch checks completed four more papers. The current database state is **14 completed, 1,614 pending, 0 failed, and 0 skipped**, with 233 indexed questions. The remaining papers are intentionally pending for resumable batches; the system has not claimed that all 1,628 PDFs were processed.

> **Critical scanned-PDF behavior:** a paper is not failed merely because conventional text extraction is empty. The pipeline records the PDF’s page count and text quality, then sends low-text/image-only PDFs to a vision-capable Gemini document route. Every extracted question is validated against the PDF page count and stored with a required 1-indexed `source_page`.

## Delivered architecture

| Layer | Delivered implementation | Production behavior |
|---|---|---|
| Android | Kotlin/Compose client, `PyqRagClient`, Frequently Asked list and group-detail screens | Uses the public API only when requested; the original Drive browser remains available if RAG is unavailable |
| API | Isolated Node/Express service under `backend/pyq-rag` | Deployed separately from the existing timetable normalizer and existing web project |
| Database | Supabase project `gndec-pyq-rag`, ref `dwxsrudypzismkrfsizy`, Mumbai region | pgvector question embeddings, source-page fields, processing states, groups, memberships, and analysis cache |
| Ingestion | Known-catalog URL validation, bounded download, content hashing, PDF page inspection, text-first extraction, Gemini vision fallback | Idempotent and resumable; no arbitrary URL ingestion and no all-corpus Gemini prompt |
| Embeddings | Server-side `gemini-embedding-2`, 768 dimensions | One consistent vector dimension for indexed questions and retrieval; batch processing is bounded |
| Grouping | Course-restricted vector RPC plus conservative similarity and keyword-overlap checks | Same-paper duplicates are excluded from cross-paper frequency; uncertain matches remain ungrouped |
| Security | Vercel-only Gemini/Supabase/admin variables, protected admin routes, strict request validation, rate limits, strict browser-origin allowlisting | No server credential is included in the APK or GitHub source |
| Deployment | Git-linked Vercel production deployment with stable alias | GitHub `main` pushes trigger deployment; the existing `nextlecture` web project was not overwritten |

The design follows Gemini’s documented native PDF understanding and Files/document-processing model, including support for text, images, diagrams, charts, and tables, with documented PDF limits of 50 MB or 1,000 pages.[1] Embeddings use the documented configurable 768-dimensional option for `gemini-embedding-2`.[2] Supabase similarity uses pgvector cosine distance and applies course metadata filtering inside the SQL RPC, rather than filtering after a limited result set.[3] [4]

## API surface

The Android client uses the stable base URL `https://gndec-pyq-rag-api.vercel.app`.

| Method and path | Purpose | Protection |
|---|---|---|
| `GET /health` | Returns service version and non-secret dependency readiness | Public; reports only boolean readiness, never credentials |
| `GET /api/pyq/frequently-asked?course=BTAM101&from=2021&to=2025&limit=50` | Returns repeated-question groups; hyphenated and non-hyphenated course inputs are canonicalized | Public, rate-limited, cached |
| `GET /api/pyq/frequently-asked/:groupId` | Returns group details, distinct-paper frequency, source pages, sessions, similarity scores, and exact Drive URLs | Public, rate-limited |
| `POST /api/pyq/ask` | Retrieves top evidence for one course question and asks Gemini to answer only from that evidence with page citations | Public, separately rate-limited |
| `POST /api/admin/seed` | Imports a bounded catalog slice using idempotent upserts | `x-pyq-admin-token` or bearer token |
| `GET /api/admin/status` | Returns processing counts and recovers stale processing rows | Protected |
| `POST /api/admin/process-batch` | Claims and processes a bounded batch; production default is one paper per call on the Hobby plan | Protected |
| `POST /api/admin/process-one` | Processes one known paper by stable catalog ID | Protected |
| `POST /api/admin/retry-failed` | Resets only a bounded number of failed papers for retry | Protected |

The production batch endpoint was deliberately exercised with one paper at a time. This aligns with Vercel’s current Hobby execution constraints, for which the documentation lists a 300-second Function maximum duration.[5] [6] The API also uses atomic Supabase claim and stale-recovery functions so concurrent admin calls do not claim the same paper.

## Scanned-paper and provenance validation

The ten-paper validation set was selected from real PDFs in the supplied Drive catalog, across multiple sessions and years, with five papers from the same course to exercise repeated-question grouping. The set deliberately retained low-text candidates. The final database verification showed the following behavior.

| Validation property | Evidence |
|---|---:|
| Real papers selected and completed | 10/10 |
| Questions extracted | 171 |
| Vision extraction in the validation set | 8 papers |
| Conventional text extraction in the validation set | 2 papers |
| Papers with stored `source_page` values in range | 10/10 |
| Failed papers after retry recovery | 0 |
| Example cross-paper group | BTAM-101, frequency 4, 4 distinct papers |
| Same-paper inflation in checked groups | Not observed; stored frequency equaled `count(distinct paper_id)` |

The stored provenance query showed source pages beginning at page 1 and never exceeding the corresponding paper’s page count. For example, the two-page scanned papers stored questions on pages 1–2, and the three-page scanned paper stored questions on pages 1–3. The API group detail response includes records such as `sourcePage: 2`, the exact paper title, exam session, and the original Drive URL.

One initial production batch exposed a Vercel-specific PDF.js worker/runtime incompatibility. It was fixed by lazy-loading PDF.js, installing a narrow DOMMatrix compatibility shim, explicitly bundling the worker module, disabling worker startup for extraction, and then retrying the paper successfully. This is why the final system has both a low-text local runtime smoke test and a live protected batch test.

## Production verification evidence

The stable production alias passed the following checks after the final deployment.

| Check | Result |
|---|---|
| `GET /health` | HTTP 200; `supabase: true`, `gemini: true` |
| Public `/api/pyq/frequently-asked` | HTTP 200 with real BTAM-101 groups and canonicalized course code |
| Public group detail | HTTP 200 with frequency, occurrences, source pages, sessions, and Drive links |
| Live `/api/pyq/ask` | HTTP 200 with retrieved evidence and page citations |
| Admin request without token | HTTP 401 `unauthorized` |
| Admin status with token | HTTP 200; final observed state 14 completed, 1,614 pending, 0 failed |
| Full catalog seed | HTTP 200; 1,628 selected and 1,628 idempotent upserts |
| CORS policy | Disallowed browser origins return HTTP 403; native Android requests work without an Origin header |
| Backend unit tests | 5/5 passed |
| Android debug build | Successful |
| Android signed release build | Successful with R8 enabled |
| Published APK checksum | Local and GitHub release assets match |

## Android 2.3 release

The signed release is available at [GitHub release 2.3](https://github.com/lsgzt/nextlecture-android/releases/tag/2.3). The direct APK download is [gndec-timetable.apk](https://github.com/lsgzt/nextlecture-android/releases/download/2.3/gndec-timetable.apk).

| Release property | Value |
|---|---|
| Application ID | `com.gndec.timetable` |
| Version name | `2.3.0` |
| Version code | `23` |
| GitHub release marker | `2.3` |
| APK size | 15,416,890 bytes |
| APK SHA-256 | `51ad88be13a539b5200b2c3d300b3687d690a90854c348afeddb97130077aa60` |
| Signature verification | APK Signature Scheme v2, one signer |
| R8 | Enabled via `isMinifyEnabled = true` |
| Latest source commit | `b312c1aac75df7db5526d2f5afc9de0581142423` |

The source is pushed to [https://github.com/lsgzt/nextlecture-android](https://github.com/lsgzt/nextlecture-android). The APK was downloaded again from the GitHub release and its SHA-256 matched the locally built artifact.

## Operating the remaining ingestion

The full catalog is seeded, but only the validated subset and a few additional production batches are processed. This is intentional: processing the remaining 1,614 PDFs should be performed through repeated protected one-paper batch calls, with status checks and retries, rather than from one request or one Gemini prompt. The operational pattern is:

```text
POST /api/admin/process-batch
Header: x-pyq-admin-token: <server-side admin token>
Body: {"limit":1,"includeFailed":false}
```

After a batch call, query `GET /api/admin/status`. If a transient provider or network error creates a failed row, use `POST /api/admin/retry-failed` with a bounded limit, then process the returned pending row again. The app’s public browsing and already-indexed Frequently Asked results do not depend on these background batches completing; incomplete coverage is represented by the processing state rather than by breaking the original browser.

## Security follow-up

The Gemini key, Supabase service-role key, and admin token were used only through temporary server-side validation/deployment configuration and were not added to GitHub, the Android source, the APK, or the completion report. Because the credentials and admin token were pasted into this conversation, the prudent post-release action is to rotate all three values in Google AI Studio/Supabase/Vercel and then update the corresponding Vercel Production variables. The Android APK does not need to change for that rotation.

The current production CORS policy is intentionally strict. Native Android requests do not carry an Origin header, so they are unaffected. If a future browser frontend is added, set `ALLOWED_ORIGINS` in Vercel to an explicit comma-separated list of approved origins; do not use a wildcard for authenticated browser use.

## References

[1]: https://ai.google.dev/gemini-api/docs/document-processing "Gemini API document processing"

[2]: https://ai.google.dev/gemini-api/docs/embeddings "Gemini API embeddings"

[3]: https://supabase.com/docs/guides/ai/semantic-search "Supabase semantic search"

[4]: https://supabase.com/docs/guides/database/extensions/pgvector "Supabase pgvector extension"

[5]: https://vercel.com/docs/plans/hobby "Vercel Hobby plan"

[6]: https://vercel.com/docs/functions/limitations "Vercel Functions limitations"
