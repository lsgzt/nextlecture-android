import json
import re
from collections import Counter
from pathlib import Path

wanted = {"ESC-101", "ESC-103", "BSC-103", "BSC-102", "HSMC-101"}
catalog_path = Path(__file__).resolve().parents[1] / "data" / "previous_year_papers.json"
catalog = json.loads(catalog_path.read_text())
selected = []
for paper in catalog["papers"]:
    match = re.match(r"^([A-Z]{2,12})-?(\d{2,4})\b", paper.get("title", ""))
    if not match:
        continue
    code = f"{match.group(1)}-{match.group(2)}"
    if code in wanted:
        selected.append({
            "id": paper["id"],
            "course": code,
            "session": paper.get("session"),
            "title": paper.get("title"),
            "fileName": paper.get("fileName"),
            "downloadUrl": paper.get("downloadUrl"),
        })

print(json.dumps({"counts": dict(Counter(item["course"] for item in selected)), "papers": selected}, indent=2, ensure_ascii=False))
