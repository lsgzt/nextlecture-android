import json
import re
import subprocess
from pathlib import Path

manifest = json.loads(Path("/tmp/target_course_downloads.json").read_text())
out_dir = Path("/home/ubuntu/apk-work/backend/pyq-rag/work/target-text")
out_dir.mkdir(parents=True, exist_ok=True)
results = []
for paper in manifest:
    source = Path(paper["localPath"])
    text_path = out_dir / f"{paper['id']}.txt"
    pdfinfo = subprocess.run(["pdfinfo", str(source)], capture_output=True, text=True, timeout=30)
    page_match = re.search(r"^Pages:\s+(\d+)", pdfinfo.stdout, re.MULTILINE)
    page_count = int(page_match.group(1)) if page_match else None
    extracted = subprocess.run(["pdftotext", "-layout", str(source), str(text_path)], capture_output=True, text=True, timeout=90)
    text = text_path.read_text(errors="replace") if text_path.exists() else ""
    letters = len(re.findall(r"[A-Za-z]", text))
    meaningful_pages = sum(1 for page in re.split("\f", text) if len(re.findall(r"[A-Za-z]", page)) >= 20)
    status = "text_eligible" if extracted.returncode == 0 and letters >= 80 and meaningful_pages >= 1 else "low_text"
    results.append({**paper, "pageCount": page_count, "normalizedLength": len(" ".join(text.split())), "letterCount": letters, "meaningfulPageCount": meaningful_pages, "extractReturnCode": extracted.returncode, "status": status, "textPath": str(text_path)})

Path("/tmp/target_course_text_stats.json").write_text(json.dumps(results, indent=2, ensure_ascii=False))
from collections import Counter
print(json.dumps({"entries": len(results), "statusCounts": dict(Counter(item["status"] for item in results)), "courseStatusCounts": {course: dict(Counter(item["status"] for item in results if item["course"] == course)) for course in sorted({item["course"] for item in results})}}, indent=2))
