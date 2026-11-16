#!/usr/bin/env python3
"""Merge a Knimbus "Previous Question Paper" scrape into the app's PYQ catalog.

Usage:
    python3 scripts/merge_gndec_papers.py <scrape.json>

<scrape.json> is the JSON produced by fetch_gndec_papers.py against
gndec.knimbus.com — a `years` map of { "2025": [ {title, publication_year,
exam_session, pdf_url, unique_id}, ... ] }.

Rules:
  * Existing Drive-linked papers are kept untouched — their links work.
  * A scrape record whose course code + year + session matches an existing
    paper is dropped (the Drive version already covers that paper).
  * New records are added as:
      id          = scrape unique_id (uuid — never collides with Drive ids)
      session     = "May 2025" / "November 2013" (app convention)
      sortKey     = year * 100 + 10 (May) / 20 (November)
      title       = "<code> · <Month Year> question paper"
      fileName    = the S3 object basename (e.g. "BBA-631.pdf")
      pdfUrl      = the Knimbus portal deep link for that paper. The scrape's
                    signed S3 urls expire within half an hour, so they are
                    NOT stored; the portal link is stable and opens the paper
                    in the college library viewer (students sign in once).
      downloadUrl = the unsigned S3 object URL (canonical object path).

The merged catalog is written to BOTH app/src/main/assets/previous_year_papers.json
and backend/pyq-rag/data/previous_year_papers.json (kept byte-identical).

Re-run this script after a fresh scrape to pick up newly published papers.
"""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ASSET = REPO / "app/src/main/assets/previous_year_papers.json"
BACKEND = REPO / "backend/pyq-rag/data/previous_year_papers.json"

PORTAL = "https://gndec.knimbus.com/portal/v2/default/viewDetail?searchResultType=SECTION_CONTENT&uniqueId="

SESSION_CODE = {"May": 10, "November": 20}


def course_code(raw_title: str) -> str:
    """Normalize a scrape title to its course code for matching."""
    code = raw_title.strip().upper()
    code = re.sub(r"\.PDF$", "", code)
    code = re.sub(r"[_ ]+A\d{3,}$", "", code)
    return code


def existing_keys(papers):
    """(code, year, month) for every non-makeup, non-PhD paper already shipped."""
    keys = set()
    for paper in papers:
        match = re.search(r"(May|November) (20\d\d)", paper.get("session", ""))
        if not match or paper.get("sortKey", 0) == 0:
            continue
        code = paper.get("title", "").split(" ·")[0].strip().upper()
        keys.add((code, match.group(2), match.group(1)))
    return keys


def main(scrape_path: str) -> None:
    scrape = json.loads(Path(scrape_path).read_text(encoding="utf-8"))
    catalog = json.loads(ASSET.read_text(encoding="utf-8"))
    papers = catalog["papers"]

    have = existing_keys(papers)
    seen_ids = {p["id"] for p in papers}

    new_by_sort_key = {}
    dropped_dupes = 0
    added = 0
    skipped_unknown_session = 0
    for year, records in scrape["years"].items():
        for record in records:
            month = record.get("exam_session")
            code = course_code(record.get("title", ""))
            if month not in SESSION_CODE or not code:
                skipped_unknown_session += 1
                continue
            if (code, str(year), month) in have:
                dropped_dupes += 1
                continue
            unique_id = record.get("unique_id", "")
            if not unique_id or unique_id in seen_ids:
                dropped_dupes += 1
                continue
            # within-scrape duplicates (same code+year+session) are skipped too
            session = f"{month} {year}"
            sort_key = int(year) * 100 + SESSION_CODE[month]
            signed_url = record.get("pdf_url", "")
            unsigned = signed_url.split("?")[0] if signed_url else ""
            basename = unsigned.rsplit("/", 1)[-1] if unsigned else f"{code}.pdf"
            if "%20" in basename:
                basename = basename.replace("%20", " ")
            new_by_sort_key.setdefault(sort_key, []).append(
                {
                    "id": unique_id,
                    "session": session,
                    "sortKey": sort_key,
                    "title": f"{code} · {session} question paper",
                    "fileName": basename,
                    "pdfUrl": PORTAL + unique_id,
                    "downloadUrl": unsigned,
                }
            )
            have.add((code, str(year), month))
            seen_ids.add(unique_id)
            added += 1

    # Keep existing order inside every session; append the new rows sorted by title.
    merged = sorted(papers, key=lambda p: -p.get("sortKey", 0))
    out = []
    for paper in merged:
        out.append(paper)
        group = new_by_sort_key.pop(paper["sortKey"], None)
        if group:
            out.extend(sorted(group, key=lambda p: p["title"]))
    # Any session the Drive catalog never had (e.g. a brand-new exam sitting)
    for sort_key in sorted(new_by_sort_key, reverse=True):
        out.extend(sorted(new_by_sort_key[sort_key], key=lambda p: p["title"]))

    # Safety: the app LazyColumn keys papers by id — duplicates crash the list.
    ids = [p["id"] for p in out]
    assert len(ids) == len(set(ids)), "duplicate paper id produced by merge"

    catalog["papers"] = out
    blob = json.dumps(catalog, ensure_ascii=False, separators=(",", ":"))
    ASSET.write_text(blob, encoding="utf-8")
    BACKEND.write_text(blob, encoding="utf-8")

    print(f"papers before: {len(papers)}")
    print(f"scrape records: {sum(len(v) for v in scrape['years'].values())}")
    print(f"dropped (already in catalog / dupes): {dropped_dupes}")
    print(f"added: {added}")
    print(f"papers now: {len(out)}")
    print(f"asset: {ASSET} ({ASSET.stat().st_size:,} bytes)")
    print(f"backend copy: {BACKEND} (byte-identical: {ASSET.read_bytes() == BACKEND.read_bytes()})")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: merge_gndec_papers.py <scrape.json>")
    main(sys.argv[1])
