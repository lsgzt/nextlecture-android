import json
from collections import Counter, defaultdict
from pathlib import Path

rows = json.loads(Path("/tmp/target_course_text_stats.json").read_text())
summary = defaultdict(lambda: {"papers": 0, "pages": 0, "low_text": 0, "eligible": 0, "lengths": []})
for row in rows:
    item = summary[row["course"]]
    item["papers"] += 1
    item["pages"] += row.get("pageCount") or 0
    item["low_text"] += row["status"] == "low_text"
    item["eligible"] += row["status"] == "text_eligible"
    item["lengths"].append(row["normalizedLength"])
for course in sorted(summary):
    item = summary[course]
    print(course, json.dumps({"papers": item["papers"], "pages": item["pages"], "low_text": item["low_text"], "text_eligible": item["eligible"], "max_text_length": max(item["lengths"] or [0]), "min_text_length": min(item["lengths"] or [0])}))
