import json
from collections import Counter
from pathlib import Path

data = json.loads(Path("/tmp/local_ocr_dataset.json").read_text())
questions = data["questions"]
refs = [q["question_ref"] for q in questions]
assert len(refs) == len(set(refs)), "duplicate question_ref"
assert all(q["course_code"] in {"ESC-101", "ESC-103", "BSC-103", "BSC-102", "HSMC-101"} for q in questions)
assert all(isinstance(q["source_page"], int) and q["source_page"] > 0 for q in questions)
assert all(len(q["question_text"]) >= 20 for q in questions)
by_ref = set(refs)
for group in data["groups"]:
    assert group["frequency"] >= 2
    members = group["members"]
    assert len(members) >= 2
    assert all(member["question_ref"] in by_ref for member in members)
    assert len({next(q["paper_id"] for q in questions if q["question_ref"] == member["question_ref"]) for member in members}) == group["frequency"]
assert len({paper["id"] for paper in data["papers"]}) == len(data["papers"])
print(json.dumps({"valid": True, "papers": len(data["papers"]), "questions": len(questions), "groups": len(data["groups"]), "courseQuestions": dict(Counter(q["course_code"] for q in questions)), "courseGroups": dict(Counter(g["course_code"] for g in data["groups"]))}, indent=2))
