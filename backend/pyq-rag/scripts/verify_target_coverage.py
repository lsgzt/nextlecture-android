import json
import subprocess
from pathlib import Path

courses = ["ESC-101", "ESC-103", "BSC-103", "BSC-102", "HSMC-101"]
rows = []
for course in courses:
    raw = subprocess.check_output([
        "curl", "-sS", "--max-time", "30",
        f"https://gndec-pyq-rag-api.vercel.app/api/pyq/frequently-asked?course={course}"
    ], text=True)
    data = json.loads(raw)
    rows.append({
        "course": course,
        "coverage": data.get("coverage"),
        "groupCount": len(data.get("groups", [])),
        "servedFromCache": data.get("servedFromCache"),
        "generatedAt": data.get("generatedAt"),
    })
print(json.dumps(rows, indent=2))
Path("/tmp/target_public_coverage.json").write_text(json.dumps(rows, indent=2))
