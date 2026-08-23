import json
import re
from collections import Counter, defaultdict
from pathlib import Path

data = json.loads(Path("/tmp/local_ocr_dataset.json").read_text())
by_ref = {q["question_ref"]: q for q in data["questions"]}
print("TOP GROUPS")
for course in sorted({g["course_code"] for g in data["groups"]}):
    print(f"\n[{course}]")
    groups = [g for g in data["groups"] if g["course_code"] == course]
    for group in groups[:12]:
        members = [by_ref[m["question_ref"]] for m in group["members"] if m["question_ref"] in by_ref]
        papers = sorted({m["paper_id"] for m in members})
        print(f"freq={group['frequency']} conf={group['confidence']:.3f} members={len(members)} papers={len(papers)}")
        print("  ", group["representative_title"])
        for member in members[:4]:
            print("   -", member["paper_id"], member["question_number"], member["question_text"][:220])
print("\nSUSPICIOUS QUESTIONS")
suspicious = []
for q in data["questions"]:
    text = q["question_text"]
    score = 0
    if len(text) > 1200: score += 1
    if re.search(r"\b(?:PAGE|EVENING|MORNING|P\.T\.O\.|Total No)\b", text, re.I): score += 2
    if text.count("?") > 6: score += 1
    if re.search(r"\b(?:Q[1-9]|Part[- ]?[ABC])\b", text[10:], re.I): score += 1
    if score >= 2:
        suspicious.append((score, q))
for score, q in sorted(suspicious, key=lambda item: (-item[0], -len(item[1]["question_text"])))[:40]:
    print(score, q["course_code"], q["paper_id"], q["question_number"], q["source_page"], q["question_text"][:500])
print("\nQUESTION LENGTHS")
for course in sorted({q["course_code"] for q in data["questions"]}):
    lengths = [len(q["question_text"]) for q in data["questions"] if q["course_code"] == course]
    print(course, "count", len(lengths), "min", min(lengths), "median", sorted(lengths)[len(lengths)//2], "max", max(lengths))
