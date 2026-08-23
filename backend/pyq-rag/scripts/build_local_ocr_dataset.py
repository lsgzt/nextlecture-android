import hashlib
import json
import re
from collections import defaultdict
from difflib import SequenceMatcher
from pathlib import Path

TARGET_COURSES = {"ESC-101", "ESC-103", "BSC-103", "BSC-102", "HSMC-101"}
# These are the five HSMC-101 rows already completed by the Gemini-backed pipeline.
ALREADY_COMPLETED = {
    "17wbTuFKfItdhlsPA82HSo1eHdUaiScds",
    "198lN5v3LqVWPpXIK4PA_ZTM12I00WZOt",
    "1Ddl3AKBSq1P0kgVUhTLjzOBmY5Gfy-no",
    "1TVQhSwD_hhB5jnTcnJKEEM7Gw87j4BdA",
    "1vySl2iSoGRr8MnJnl-Tn9sflfbohONAT",
}
STOP_WORDS = {
    "the", "and", "for", "with", "from", "that", "this", "what", "which", "into", "are", "was", "were",
    "how", "why", "when", "where", "your", "their", "then", "than", "have", "has", "had", "not", "all",
    "any", "each", "show", "write", "give", "using", "used", "use", "following", "explain", "describe",
    "discuss", "define", "state", "draw", "differentiate", "distinguish", "mention", "suitable", "examples",
    "example", "short", "note", "notes", "also", "does", "will", "can", "may", "should", "must", "from",
    "part", "marks", "question", "questions", "calculate", "find", "determine", "illustrate", "analyze",
    "analyse", "examine", "consider", "design", "develop", "construct", "explain", "briefly", "detailed",
}


def normalize_text(text):
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9\s]", " ", text.lower())).strip()


def stem(token):
    if len(token) > 6 and token.endswith("ies"):
        return token[:-3] + "y"
    if len(token) > 5 and token.endswith("ing"):
        return token[:-3]
    if len(token) > 4 and token.endswith("ed"):
        return token[:-2]
    if len(token) > 4 and token.endswith("s") and not token.endswith("ss"):
        return token[:-1]
    return token


def content_tokens(text):
    return {stem(token) for token in re.findall(r"[a-z][a-z0-9]{2,}", normalize_text(text)) if token not in STOP_WORDS}


def clean_line(line):
    line = re.sub(r"\b(?:P\.?T\.?O\.?|PAGE\s*\d+\s+OF\s+\d+)\b", " ", line, flags=re.I)
    line = re.sub(r"[|{}]+", " ", line)
    return re.sub(r"\s+", " ", line).strip()


def page_and_section_lines(text):
    page = 1
    section = None
    out = []
    for raw in text.splitlines():
        marker = re.search(r"\bPAGE\s+(\d+)\b", raw, re.I)
        if marker:
            page = max(1, int(marker.group(1)))
        part = re.search(r"\bPART\s*[-—~ ]*\s*([ABC])\b", raw, re.I)
        if part:
            section = f"Part-{part.group(1).upper()}"
        line = clean_line(raw)
        if not line or re.match(r"^(?:PART|SECTION)\b", line, re.I) or re.match(r"^(?:P\.?T\.?O\.?|PAGE\s+\d+)\b", line, re.I):
            continue
        if re.search(r"Part\s*[-—~ ]*C\s+has|Total\s+No\.?\s+of\s+Questions|Program\s*:|Semester\s*:|Name\s+of\s+Subject|Subject\s+Code|Paper\s+ID|Time\s+Allowed|Scientific\s+calculator|Uni\.?\s*Roll", line, re.I):
            continue
        out.append({"text": line, "page": page, "section": section})
    return out


def question_markers(line):
    markers = []
    for match in re.finditer(r"(?<![A-Za-z0-9])Q\s*([1-9])\s*[.):]?", line, re.I):
        markers.append((match.start(), int(match.group(1)), match.end()))
    for match in re.finditer(r"(?<![A-Za-z0-9])Q\s*[lI]\s*[.):]", line):
        markers.append((match.start(), 1, match.end()))
    # Common OCR confusion in a single source paper: QS. means Q5.
    for match in re.finditer(r"(?<![A-Za-z0-9])QS\s*[.):]", line):
        markers.append((match.start(), 5, match.end()))
    # Several older scans omit the Q prefix and OCR the top-level marker as "2.", "3.", etc.
    # Restrict this fallback to a period so numbered subparts such as "1)" remain inside Q1.
    for match in re.finditer(r"(?m)^\s*([2-9])\.\s+", line):
        markers.append((match.start(), int(match.group(1)), match.end()))
    return sorted(set(markers))


def split_question_blocks(lines):
    blocks = []
    current = None
    for line_index, item in enumerate(lines):
        markers = question_markers(item["text"])
        if markers:
            first_start, number, first_end = markers[0]
            prefix = item["text"][:first_start].strip()
            if current is not None:
                blocks.append(current)
            current = {"number": number, "start": line_index, "page": item["page"], "section": item["section"], "lines": []}
            remainder = item["text"][first_end:].strip()
            if remainder:
                current["lines"].append(remainder)
            continue
        if current is not None:
            current["lines"].append(item["text"])
    if current is not None:
        blocks.append(current)
    return blocks


def split_subparts(block):
    parts = []
    current_label = None
    current_lines = []
    for raw in block["lines"]:
        line = re.sub(r"^[\s\[{(]*", "", raw)
        match = re.match(r"([a-f])\s*[.):]\s*(.*)$", line, re.I)
        if match:
            if current_label is not None:
                parts.append((current_label, current_lines))
            current_label = match.group(1).lower()
            current_lines = [match.group(2).strip()] if match.group(2).strip() else []
        else:
            current_lines.append(raw)
    if current_label is not None:
        parts.append((current_label, current_lines))
    return parts


def plausible_question(text):
    letters = len(re.findall(r"[A-Za-z]", text))
    if letters < 20:
        return False
    normalized = normalize_text(text)
    if normalized in {"or", "pt o", "evening", "morning"}:
        return False
    if re.fullmatch(r"(?:page|of|marks|part|evening|morning|please check).{0,80}", normalized):
        return False
    return True


def build_questions(row):
    text = Path(row["ocrTextPath"]).read_text(errors="replace")
    lines = page_and_section_lines(text)
    blocks = split_question_blocks(lines)
    questions = []
    for block in blocks:
        block_lines = [line for line in block["lines"] if line.strip()]
        subparts = split_subparts(block)
        candidates = []
        if len(subparts) >= 2:
            for label, part_lines in subparts:
                candidates.append((f"Q{block['number']}{label}", part_lines))
        else:
            candidates.append((f"Q{block['number']}", block_lines))
        for question_number, candidate_lines in candidates:
            raw_text = re.sub(r"\s+", " ", " ".join(candidate_lines)).strip(" -:;,.|")
            raw_text = re.split(r"\b(?:PART|SECTION)\s*[-—~ ]*\s*[ABC]\b", raw_text, maxsplit=1, flags=re.I)[0]
            raw_text = re.split(r"\bPAGE\s+\d+\b", raw_text, maxsplit=1, flags=re.I)[0]
            raw_text = re.split(r"\b(?:MORNING|EVENING|PAPER\s+ID|TOTAL\s+NO|PROGRAM|SEMESTER|SUBJECT\s+CODE|TIME\s+ALLOWED|SCIENTIFIC\s+CALCULATOR|UNI\.?\s*ROLL)\b", raw_text, maxsplit=1, flags=re.I)[0]
            raw_text = re.sub(r"\b(?:P\.?T\.?O\.?)\b", " ", raw_text, flags=re.I)
            raw_text = re.sub(r"\s+", " ", raw_text).strip(" -:;,.|")
            alternatives = re.split(r"\s+\bOR\b\s+", raw_text, flags=re.I)
            for alternative_index, alternative_text in enumerate(alternatives, 1):
                question_text = re.sub(r"\s+", " ", alternative_text).strip(" -:;,.|")
                if not plausible_question(question_text):
                    continue
                question_label = question_number if len(alternatives) == 1 else f"{question_number}-{alternative_index}"
                question_ref = f"{row['id']}::{question_label}::{max(1, int(block['page']))}::{hashlib.sha1(question_text.encode('utf-8')).hexdigest()[:12]}"
                questions.append({
                    "question_ref": question_ref,
                    "paper_id": row["id"],
                    "course_code": row["course"],
                    "question_number": question_label,
                    "question_text": question_text[:4000],
                    "normalized_question": normalize_text(question_text)[:4000],
                    "section": block["section"],
                    "marks": {"Part-A": 2, "Part-B": 4, "Part-C": 12}.get(block["section"]),
                    "unit": None,
                    "source_page": max(1, int(block["page"])),
                    "extraction_method": "ocr",
                    "extraction_confidence": 0.78,
                })
    # Remove exact duplicates within a paper/page while preserving order.
    seen = set()
    result = []
    for question in questions:
        key = (question["source_page"], question["normalized_question"])
        if len(question["normalized_question"]) >= 8 and key not in seen:
            seen.add(key)
            result.append(question)
    return result[:300]


def pair_score(left, right):
    if left["paper_id"] == right["paper_id"]:
        return 0.0
    a = content_tokens(left["question_text"])
    b = content_tokens(right["question_text"])
    if len(a) < 3 or len(b) < 3:
        return 0.0
    intersection = len(a & b)
    jaccard = intersection / len(a | b)
    containment = intersection / min(len(a), len(b))
    sequence = SequenceMatcher(None, left["normalized_question"], right["normalized_question"]).ratio()
    if (containment >= 0.84 and jaccard >= 0.60) or (sequence >= 0.90 and jaccard >= 0.52):
        return min(0.98, max(0.84, 0.50 * containment + 0.30 * jaccard + 0.20 * sequence))
    return 0.0


def group_questions(questions):
    by_course = defaultdict(list)
    for question in questions:
        by_course[question["course_code"]].append(question)
    groups = []
    for course, course_questions in sorted(by_course.items()):
        parent = list(range(len(course_questions)))
        scores = defaultdict(list)

        def find(index):
            while parent[index] != index:
                parent[index] = parent[parent[index]]
                index = parent[index]
            return index

        def union(left, right):
            left_root, right_root = find(left), find(right)
            if left_root != right_root:
                parent[right_root] = left_root

        for left in range(len(course_questions)):
            for right in range(left + 1, len(course_questions)):
                score = pair_score(course_questions[left], course_questions[right])
                if score:
                    union(left, right)
                    scores[(left, right)].append(score)
        components = defaultdict(list)
        for index in range(len(course_questions)):
            components[find(index)].append(index)
        for members in components.values():
            paper_ids = {course_questions[index]["paper_id"] for index in members}
            if len(paper_ids) < 2:
                continue
            member_scores = []
            for left_pos, left in enumerate(members):
                for right in members[left_pos + 1:]:
                    score = pair_score(course_questions[left], course_questions[right])
                    if score:
                        member_scores.append(score)
            if not member_scores:
                continue
            representative = max((course_questions[index] for index in members), key=lambda item: len(item["question_text"]))
            groups.append({
                "course_code": course,
                "representative_title": f"Repeated question: {representative['question_text']}",
                "representative_description": "Grouped locally from page-preserved OCR using conservative lexical overlap; no Gemini or embeddings were used.",
                "frequency": len(paper_ids),
                "confidence": round(min(member_scores), 4),
                "members": [{"question_ref": course_questions[index]["question_ref"], "similarity_score": round(pair_score(representative, course_questions[index]) or max(member_scores), 4)} for index in members],
            })
    groups.sort(key=lambda item: (-item["frequency"], -item["confidence"], item["representative_title"]))
    return groups

rows = json.loads(Path("/tmp/target_course_ocr_stats.json").read_text())
rows = [row for row in rows if row["course"] in TARGET_COURSES and row["id"] not in ALREADY_COMPLETED]
questions = []
processed_papers = []
for row in rows:
    paper_questions = build_questions(row)
    questions.extend(paper_questions)
    content_hash = hashlib.sha256(Path(row["localPath"]).read_bytes()).hexdigest()
    processed_papers.append({"id": row["id"], "course_code": row["course"], "page_count": row.get("pageCount"), "content_hash": content_hash, "question_count": len(paper_questions), "source_file_name": row.get("fileName"), "status": row.get("status")})

groups = group_questions(questions)
output = {"papers": processed_papers, "questions": questions, "groups": groups}
Path("/tmp/local_ocr_dataset.json").write_text(json.dumps(output, indent=2, ensure_ascii=False))
from collections import Counter
print(json.dumps({"papers": len(processed_papers), "questions": len(questions), "groups": len(groups), "paperStatus": dict(Counter(row["status"] for row in processed_papers)), "coursePapers": dict(Counter(row["course_code"] for row in processed_papers)), "courseQuestions": dict(Counter(row["course_code"] for row in questions)), "courseGroups": dict(Counter(group["course_code"] for group in groups))}, indent=2))
