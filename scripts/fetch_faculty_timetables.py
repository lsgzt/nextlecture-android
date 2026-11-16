#!/usr/bin/env python3
"""
Dynamic GNDEC faculty-timetable discovery + parser (prototype / verification).

NEVER hardcodes a timetable file URL. For every department it:
  1. fetches the department's fixed INDEX page (the only stable URL),
  2. discovers the newest teacher-timetable link by keyword/path evidence,
  3. downloads it, checks the FET "generated on" session window,
  4. parses every per-teacher table (all published FET dialects),
  5. matches faculty_enriched.json names to table captions.

Run:  python3 scripts/fetch_faculty_timetables.py [--dept me] [--json-out FILE]
"""
import argparse
import json
import re
import sys
import html as htmlmod
from datetime import date
from pathlib import Path
from urllib.parse import urljoin, unquote

try:
    import requests
except ImportError:  # fall back to urllib
    import urllib.request as _rq

    class requests:  # minimal shim
        @staticmethod
        def get(url, timeout=30, **_):
            class R:
                def __init__(self, b):
                    self.content = b
                    self.status_code = 200

                @property
                def text(self):
                    return self.content.decode("utf-8-sig", errors="replace")

            with _rq.urlopen(_rq.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=timeout) as resp:
                return R(resp.read())


# ---------------------------------------------------------------------------
# Fixed index pages (stable) — the ONLY URLs ever hardcoded.
# ---------------------------------------------------------------------------
DEPARTMENT_ROOTS = {
    "appsc": "https://appsc.gndec.ac.in/time_tables",
    "it":    "https://it.gndec.ac.in/?q=node/5",
    "cse":   "https://cse.gndec.ac.in/?q=node/5",
    "ee":    "https://ee.gndec.ac.in/?q=node/5",
    "me":    "https://me.gndec.ac.in/?q=node/5",
    "ce":    "https://ce.gndec.ac.in/?q=node/5",
    "ece":   "https://ece.gndec.ac.in/?q=node/5",
}

JSON_DEPARTMENT_TO_ROOT = {
    "Applied Science": "appsc",
    "Information Technology": "it",
    "Computer Science & Engg.": "cse",
    "Electrical Engineering": "ee",
    "Mechanical Engineering": "me",
    "Production Engineering": "me",
    "Civil Engineering": "ce",
    "Electronics & Communication Engineering": "ece",
}

ANCHOR_OPEN_RE = re.compile(r"<a\b[^>]*?href=[\"']([^\"']+)[\"'][^>]*>", re.S | re.I)
BR_RE = re.compile(r"<br\s*/?>", re.I)
TAG_RE = re.compile(r"<[^>]+>")


def iter_anchors(page: str):
    """Yields (href, label) like Jsoup does — a new <a> start tag closes the
    previous anchor, which is how ME's malformed (never-closed) anchors behave
    in a real HTML parser. Label = text between this anchor and the next one."""
    opens = list(ANCHOR_OPEN_RE.finditer(page))
    for i, m in enumerate(opens):
        start = m.end()
        end = opens[i + 1].start() if i + 1 < len(opes := opens) else start + 4000
        seg = page[start:end]
        # a well-formed anchor's label ends at </a>; ME-style malformed anchors
        # carry their label in the text up to the NEXT anchor (which may include
        # <li>…</li> markup) — so only </a> acts as a hard boundary.
        cut = len(seg)
        sm = re.search(r"</a>", seg, re.I)
        if sm:
            cut = sm.start()
        label = clean_label(seg[:cut])
        yield m.group(1), label


GENERATED_ON = re.compile(
    r"generated with FET\s+\S+\s+on\s+(\d{1,2})/(\d{1,2})/(\d{2,4})\s+(\d{1,2}):(\d{2})[\u202F\u00A0 ]*([AaPp][Mm])?")
TIME_IN_LABEL = re.compile(r"(\d{1,2})[.:](\d{2})(?:\s*([AaPp][Mm]))?")
EMPTY_CELL_TEXT = {"---", "-x-", "-", "--", "x", "not available", "na", "n/a"}
TITLES = {"dr", "er", "prof", "professor", "pf", "mrs", "mr", "ms", "miss", "sh", "smt", "shri"}


def text_of(fragment: str) -> str:
    return htmlmod.unescape(TAG_RE.sub(" ", fragment)).strip()


def clean_label(fragment: str) -> str:
    return re.sub(r"\s+", " ", htmlmod.unescape(TAG_RE.sub(" ", fragment))).strip()


# ---------------------------------------------------------------------------
# Discovery
# ---------------------------------------------------------------------------
def classify_anchor(label: str, href: str, root_url: str) -> bool:
    """True when the anchor points at this root's teacher timetable document.

    Filename evidence ALWAYS wins over label evidence: ME publishes one visual
    block whose combined label mentions Class+Teacher+Room but whose href points
    at the GROUPS file; judging by label alone would pick the wrong document.
    """
    host = root_url.split("/")[2].lower()
    target = urljoin(root_url, href)
    if not target.lower().startswith("https://" + host + "/") and not target.lower().startswith(
            "http://" + host + "/"):
        return False
    path = unquote(target.split("?")[0]).lower()
    if not path.endswith(".html"):
        return False
    if "free_period" in path or "combined" in path:  # CE free-periods / ECE combined views
        return False
    # 1) filename evidence (authoritative)
    if "teachers" in path or "teacher" in path:
        return True
    if any(k in path for k in ("groups", "years", "rooms", "subgroups", "subjects",
                               "classes", "class")):
        return False
    # 2) label evidence (only when the filename is uninformative)
    l = label.lower()
    if "teacher" in l or "teaches" in l:
        return True
    if "faculty" in l and ("time table" in l or "timetable" in l):
        return True
    return False


def discover_teacher_url(root_key: str):
    root_url = DEPARTMENT_ROOTS[root_key]
    page = requests.get(root_url, timeout=30).text
    seen = set()
    for href, label in iter_anchors(page):
        label = label.replace("\u00A0", " ")
        target = urljoin(root_url, href)
        if target in seen:
            continue
        seen.add(target)
        if classify_anchor(label, target, root_url):
            return target, label  # page order = newest first
    return None, None


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------
def slot_start_minutes(label: str):
    m = TIME_IN_LABEL.search(label)
    if not m:
        return None
    hour, minute = int(m.group(1)), int(m.group(2))
    mer = (m.group(3) or "").upper()
    if mer == "PM" and hour != 12:
        hour += 12
    elif mer == "AM" and hour == 12:
        hour = 0
    elif not mer and hour <= 7:
        hour += 12
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None
    return hour * 60 + minute


def day_index(label: str, col: int, ncols: int):
    token = label.split(" ")[0].upper()
    table = {"MONDAY": 0, "TUESDAY": 1, "TUES": 1, "TUE": 1, "WEDNESDAY": 2, "WED": 2, "THURSDAY": 3,
             "THURS": 3, "THU": 3, "FRIDAY": 4, "FRI": 4, "SATURDAY": 5, "SAT": 5, "SUNDAY": 6, "SUN": 6,
             "M": 0, "T": 1, "W": 2, "TH": 3, "F": 4}
    if token in table:
        return table[token]
    if len(token) <= 2 and 5 <= ncols <= 6 and col < 6:
        return col
    return None


def parse_cell_html(td_html: str):
    lines = [text_of(x) for x in BR_RE.split(td_html)]
    lines = [x.strip() for x in lines if x.strip() and x.strip().lower() not in EMPTY_CELL_TEXT]
    if not lines:
        return None
    if len(lines) == 1:
        return {"busy": True, "subject": lines[0], "room": None, "students": None}
    if len(lines) == 2:
        return {"busy": True, "subject": lines[0], "room": lines[-1], "students": None}
    return {"busy": True, "subject": lines[-2], "room": lines[-1], "students": lines[0]}


def parse_doc(html_text: str):
    """Returns list of {teacher, code, slots} per table.

    The document's own Table of Contents is the primary name source: ME writes
    "CODE (Full Name)", EE/ECE write "Full Name (CODE)", appsc/IT/CSE write
    plain full names, CE writes bare codes. Captions are the fallback."""
    toc = {int(i): clean_label(lbl) for i, lbl in
           re.findall(r'<a href="#table_(\d+)">([^<]+)</a>', html_text)}
    tables = [(m.group(1), m.group(0)) for m in
              re.finditer(r"<table[^>]*id=[\"']table_(\d+)[\"'][^>]*>.*?</table>", html_text, re.S | re.I)]
    out = []
    for tbl_id, tbl in tables:
        cap = re.search(r"<caption>(.*?)</caption>", tbl, re.S | re.I)
        name = None
        if cap:
            m = re.search(r"<span[^>]*class=[\"']name[\"'][^>]*>(.*?)</span>", cap.group(1), re.S | re.I)
            name = clean_label(m.group(1)) if m else None  # bare <caption> text is the institution
        if not name:
            colspan = re.search(r"<th[^>]*colspan=[\"']?\d+[\"']?[^>]*>(.*?)</th>", tbl, re.S | re.I)
            if colspan:
                name = clean_label(colspan.group(1))  # CSE dialect B
        label = toc.get(int(tbl_id)) or name
        if not label:
            continue
        full, code = extract_full_name(label)

        # day columns
        xaxis = re.findall(r"<th[^>]*class=[\"']xAxis[\"'][^>]*>(.*?)</th>", tbl, re.S | re.I)
        days = [day_index(text_of(x), i, len(xaxis)) for i, x in enumerate(xaxis)]
        if not any(d is not None and d <= 5 for d in days):
            continue

        # rows
        body_rows = re.findall(r"<tr[^>]*>(.*?)</tr>", tbl, re.S | re.I)
        slots = []
        for row in body_rows:
            ym = re.search(r"<th[^>]*class=[\"']yAxis[\"'][^>]*>(.*?)</th>", row, re.S | re.I)
            if not ym:
                continue
            start = slot_start_minutes(text_of(ym.group(1)))
            if start is None:
                continue
            tds = re.findall(r"<td[^>]*>(.*?)</td>", row, re.S | re.I)
            col = 0
            i = 0
            while col < len(days) and i < len(tds):
                d = days[col]
                td = tds[i]
                i += 1
                rowspan = re.search(r"rowspan=[\"']?(\d+)", td)
                txt = text_of(td)
                cell = None
                if txt and txt.lower() not in EMPTY_CELL_TEXT:
                    cell = parse_cell_html(td)
                if cell and d is not None and d <= 6:
                    slots.append({"day": d, "start": start, **cell})
                    if rowspan and int(rowspan.group(1)) > 1:
                        # rowspan carry is exact in the Kotlin port; prototype keeps it simple
                        pass
                col += 1
        out.append({"teacher": full or label, "code": code, "slots": slots})
    return out


PARENS_TAIL = re.compile(r"^([^(]+)\s*\(([^)]+)\)\s*$")


def is_code(s: str) -> bool:
    """Short uppercase alphabetic teacher code, optionally with - or _ glue."""
    core = s.replace("-", "").replace("_", "").replace(" ", "")
    return 2 <= len(core) <= 5 and core.isalpha() and core.isupper()


def is_name_like(s: str) -> bool:
    toks = s.replace("_", " ").split()
    if len(toks) >= 2:
        return True
    return len(s) >= 8  # single long word can still be a name


def extract_full_name(label: str):
    """'SSK (Dr. Sehijpal Singh)'  -> ('Dr. Sehijpal Singh', 'SSK')
    'Dr. Kanwardeep Singh (KDS)' -> ('Dr. Kanwardeep Singh', 'KDS')
    'Dr. Akshay Girdhar-AG'      -> ('Dr. Akshay Girdhar', 'AG')
    'HVS'                        -> (None, 'HVS')   bare code, no full name
    'GDKL(math)'                 -> (None, 'GDKL')  code + dept annotation"""
    label = label.strip()
    m = PARENS_TAIL.match(label)
    if m:
        left, right = m.group(1).strip(), m.group(2).strip()
        # right side may be 'CODE DEPT' ('JDP MPE') — take the leading code token
        right_parts = right.replace("_", " ").split()
        right_core = right_parts[0] if right_parts else ""
        if is_code(right_core):
            return (left if is_name_like(left) else None), right_core
        left_parts = left.replace("_", " ").split()
        left_core = left_parts[0] if left_parts else ""
        if is_code(left_core) and len(left_parts) <= 2:
            if is_name_like(right):
                return right, left_core
            return None, left_core  # 'GDKL(math)': both sides non-name — keep the code
        if is_name_like(left):
            return left, None
        if is_name_like(right):
            return right, None
        return None, None
    # '-AG' style suffix
    cm = re.search(r"[-\u2013]\s*([A-Za-z]{2,5})\s*$", label)
    if cm:
        base = label[: cm.start()].strip()
        return (base if is_name_like(base) else None), cm.group(1)
    if is_code(label):
        return None, label.replace("-", "").replace("_", "").replace(" ", "")
    return label, None


# ---------------------------------------------------------------------------
# Name matching
# ---------------------------------------------------------------------------
def name_tokens(name: str):
    toks = re.sub(r"[^a-z0-9]+", " ", name.lower()).split()
    return [t for t in toks if t not in TITLES]


def initials_of(name: str):
    return "".join(t[0] for t in name_tokens(name))


def norm_name(name: str):
    return " ".join(name_tokens(name))


def strip_caption_decorations(caption: str):
    s = re.sub(r"\(([^)]*)\)\s*$", "", caption).strip()   # "(PS)"
    s = re.sub(r"[-\u2013]\s*[A-Za-z]{1,4}\s*$", "", s).strip()  # "-AG"
    return s


DEPT_OF_CODE = {
    "ee": "Electrical Engineering", "ec": "Electronics & Communication Engineering",
    "ece": "Electronics & Communication Engineering", "me": "Mechanical Engineering",
    "mpe": "Mechanical Engineering", "ce": "Civil Engineering", "it": "Information Technology",
    "cse": "Computer Science & Engg.", "cs": "Computer Science & Engg.",
    "ta": "Workshops", "ws": "Workshops", "as": "Applied Science", "aps": "Applied Science",
    "appsc": "Applied Science", "applied": "Applied Science", "mba": "Business Administration",
    "mca": "Computer Applications", "ca": "Computer Applications",
}

# labels that mark a table as a pseudo-entry, not a teacher
NON_TEACHER_HINTS = ("m.tech", "mtech", "self study", "faculty", "deptt", "workshop", "oe",
                     "combined", "guide", "detained")


def label_tokens(s: str):
    return re.split(r"[^A-Za-z0-9]+", s.lower())


def dept_hint_of(label: str):
    """Department annotation carried in the label ('JJK_ME', '(AS)', 'Teachers (MBA)')…"""
    for tok in label_tokens(label):
        if tok in DEPT_OF_CODE:
            return DEPT_OF_CODE[tok]
    return None


def title_of(name: str):
    first = re.split(r"[^A-Za-z]+", name.strip())[0].lower() if name.strip() else ""
    return first if first in TITLES else ""


def name_score(caption: str, json_name: str) -> int:
    """Score a full teacher name against a JSON faculty name."""
    if norm_name(caption) == norm_name(json_name):
        return 100
    if "".join(name_tokens(caption)) == "".join(name_tokens(json_name)):
        return 100  # 'SATWINDER JIT' == 'SATWINDERJIT'
    cap_tokens = name_tokens(caption)
    json_tokens = name_tokens(json_name)
    if not cap_tokens or not json_tokens:
        return 0
    if len(cap_tokens) == len(json_tokens):
        if all(ct == jt or (len(ct) == 1 and jt.startswith(ct)) for ct, jt in zip(cap_tokens, json_tokens)):
            return 85  # 'D S PATHANIA' vs 'DALJEEP SINGH PATHANIA'
        if all(token_similar(ct, jt) for ct, jt in zip(cap_tokens, json_tokens)):
            return 70  # 'Kulwinder' ~ 'Kulvinder', 'Jaswal' ~ 'Jaiswal'
        if all(token_similar(ct, jt, 0.75) for ct, jt in zip(cap_tokens, json_tokens)):
            return 65  # looser spelling variant
    # caption is a leading token-subset of the JSON name ('AMARJOT KAUR' ⊂ 'AMARJOT KAUR RANDHAWA')
    if len(cap_tokens) < len(json_tokens) and json_tokens[: len(cap_tokens)] == cap_tokens:
        return 78
    # single given name ('PRAGYA' → 'PRAGYA GOYAL', 'Aman' → 'AMANDEEP SINGH')
    if len(cap_tokens) == 1 and len(json_tokens) >= 2 and len(cap_tokens[0]) >= 4:
        if json_tokens[0].startswith(cap_tokens[0]):
            return 75
    return 0


def match_score(name_or_code, code, json_name: str) -> int:
    """Score a table's (full name, code) against a JSON faculty name."""
    if name_or_code:
        return name_score(name_or_code, json_name)
    return 0


def is_subseq(needle, hay):
    it = iter(hay)
    return all(c in it for c in needle)


def token_similar(a: str, b: str, threshold: float = 0.8) -> bool:
    if a == b:
        return True
    if abs(len(a) - len(b)) > 2:
        return False
    return damerau(a, b) / max(len(a), len(b)) <= (1 - threshold)


def damerau(a: str, b: str) -> int:
    """Damerau-Levenshtein distance (optimal string alignment)."""
    d = {(i, j): 0 for i in range(len(a) + 1) for j in range(len(b) + 1)}
    for i in range(len(a) + 1):
        d[(i, 0)] = i
    for j in range(len(b) + 1):
        d[(0, j)] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            d[(i, j)] = min(d[(i - 1, j)] + 1, d[(i, j - 1)] + 1, d[(i - 1, j - 1)] + cost)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                d[(i, j)] = min(d[(i, j)], d[(i - 2, j - 2)] + 1)  # transposition
    return d[(len(a), len(b))]


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def session_current(generated_text, today=None):
    m = GENERATED_ON.search(generated_text or "")
    if not m:
        return None
    month, day, year = int(m.group(1)), int(m.group(2)), int(m.group(3))
    if year < 100:
        year += 2000
    today = today or date.today()
    gen = date(year, month, day)
    if gen > today:
        return True
    w = lambda d: (d.year, 2 if d.month >= 6 else 1)
    return w(gen) == w(today)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dept", choices=list(DEPARTMENT_ROOTS))
    ap.add_argument("--json-out")
    ap.add_argument("--faculty-json", default="faculty_enriched.json")
    args = ap.parse_args()

    repo = Path(__file__).resolve().parent.parent
    faculty = json.loads((repo / args.faculty_json).read_text(encoding="utf-8"))

    result = {}
    depts = [args.dept] if args.dept else list(DEPARTMENT_ROOTS)
    for dept in depts:
        url, label = discover_teacher_url(dept)
        print(f"[{dept}] discovered: {url}  (label: {label!r})")
        if not url:
            result[dept] = {"error": "no teacher timetable link found"}
            continue
        html_text = requests.get(url, timeout=60).text
        current = session_current(html_text)
        print(f"[{dept}] session-current: {current}")
        teachers = parse_doc(html_text)
        print(f"[{dept}] parsed {len(teachers)} teacher tables")

        # match against JSON faculty of mapped departments (deduped by name)
        mapped = [d for d, r in JSON_DEPARTMENT_TO_ROOT.items() if r == dept]
        pool = {}
        for f in faculty:
            if f.get("department") in mapped:
                pool.setdefault(norm_name(f["name"]), f)
        pool = list(pool.values())

        def is_real_teacher(t):
            blob = (t.get("teacher") or "") + " " + (t.get("code") or "")
            low = blob.lower()
            return not any(h in low for h in NON_TEACHER_HINTS)

        teachers = [t for t in teachers if is_real_teacher(t)]

        # pass 1: full-name matches (exact > prefix > fuzzy spelling variants),
        # with a dept-annotation penalty and a title-agreement tie-break
        assignment = {}  # faculty norm-name -> (teacher table, score)
        for f in pool:
            f_dept = f.get("department")
            f_title = title_of(f["name"])
            best, best_score, tie = None, 0, False
            for t in teachers:
                s = match_score(t.get("teacher"), None, f["name"])
                if s > 0:
                    hint = dept_hint_of((t.get("teacher") or "") + " " + (t.get("code") or ""))
                    if hint is not None and hint != f_dept:
                        s = max(0, s - 40)
                    if s > 0 and title_of(t.get("teacher") or "") == f_title and f_title:
                        s += 3
                if s > best_score:
                    best, best_score, tie = t, s, False
                elif s == best_score and s > 0:
                    tie = True
            if best and best_score >= 65 and not tie:
                assignment[norm_name(f["name"])] = (best, best_score)

        # pass 2: code-subsequence match for the rest, with GLOBAL uniqueness and
        # fixpoint iteration — each round shrinks the candidate set, resolving
        # earlier ambiguity ('PTG' was ambiguous until 'PJS' claimed PRABHJOT).
        changed = True
        while changed:
            changed = False
            for t in teachers:
                code = t.get("code")
                if not code or len(code) < 2 or code.lower() in DEPT_OF_CODE:
                    continue
                hits = [f for f in pool if norm_name(f["name"]) not in assignment and
                        is_subseq(code.lower(), "".join(name_tokens(f["name"])))]
                if len(hits) == 1:
                    assignment[norm_name(hits[0]["name"])] = (t, 60)
                    changed = True

        matched = len(assignment)
        unmatched = [f["name"] for f in pool if norm_name(f["name"]) not in assignment]
        print(f"[{dept}] JSON faculty: {len(pool)}, matched: {matched}, unmatched: {len(unmatched)}")
        for u in unmatched[:12]:
            print(f"    - {u}")
        result[dept] = {"url": url, "current": current, "teachers": len(teachers),
                        "json_faculty": len(pool), "matched": matched}

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(result, indent=2))
        print(f"\nSaved -> {args.json_out}")


if __name__ == "__main__":
    main()
