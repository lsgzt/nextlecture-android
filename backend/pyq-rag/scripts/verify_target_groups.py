import json
import subprocess
from pathlib import Path

courses = ["ESC-101", "ESC-103", "BSC-103", "BSC-102", "HSMC-101"]
output = []
for course in courses:
    raw = subprocess.check_output([
        "curl", "-sS", "--max-time", "30",
        f"https://gndec-pyq-rag-api.vercel.app/api/pyq/frequently-asked?course={course}"
    ], text=True)
    data = json.loads(raw)
    groups = data.get("groups", [])
    top = groups[:3]
    details = []
    for group in top[:1]:
        detail_raw = subprocess.check_output([
            "curl", "-sS", "--max-time", "30",
            f"https://gndec-pyq-rag-api.vercel.app/api/pyq/frequently-asked/{group['group_id']}"
        ], text=True)
        detail = json.loads(detail_raw)
        occurrences = detail.get("occurrences", [])
        details.append({
            "group_id": group.get("group_id"),
            "title": group.get("representative_title"),
            "frequency": group.get("frequency"),
            "occurrenceCount": len(occurrences),
            "sourcePages": [occurrence.get("sourcePage") for occurrence in occurrences[:10]],
            "extractionMethods": sorted({occurrence.get("extractionMethod") for occurrence in occurrences}),
        })
    output.append({"course": course, "topGroups": top, "verifiedDetail": details})
print(json.dumps(output, indent=2, ensure_ascii=False))
Path("/tmp/target_group_provenance.json").write_text(json.dumps(output, indent=2, ensure_ascii=False))
