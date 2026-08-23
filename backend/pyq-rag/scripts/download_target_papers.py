import json
import re
import sys
import time
from pathlib import Path
from urllib.request import Request, urlopen

TARGET_LIST = Path("/tmp/target_course_papers.json")
OUT_DIR = Path("/home/ubuntu/apk-work/backend/pyq-rag/work/target-pdfs")
OUT_DIR.mkdir(parents=True, exist_ok=True)
entries = json.loads(TARGET_LIST.read_text())["papers"]
manifest = []
for index, paper in enumerate(entries, 1):
    safe_name = re.sub(r"[^A-Za-z0-9._-]+", "_", paper["fileName"] or paper["id"])
    destination = OUT_DIR / f"{paper['id']}_{safe_name}"
    status = "existing"
    size = destination.stat().st_size if destination.exists() else 0
    if size < 1024:
        request = Request(paper["downloadUrl"], headers={"User-Agent": "GNDEC-PYQ-local-verifier/1.0"})
        try:
            with urlopen(request, timeout=60) as response, destination.open("wb") as output:
                total = 0
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > 50 * 1024 * 1024:
                        raise ValueError("download exceeded 50 MiB safety bound")
                    output.write(chunk)
            size = destination.stat().st_size
            status = "downloaded"
        except Exception as exc:
            if destination.exists():
                destination.unlink()
            status = f"error:{type(exc).__name__}"
            size = 0
            print(f"[{index}/{len(entries)}] {paper['course']} {paper['id']} {status}", file=sys.stderr)
    manifest.append({**paper, "localPath": str(destination), "size": size, "status": status})
    print(f"[{index}/{len(entries)}] {paper['course']} {status} {size} bytes")
    time.sleep(0.05)

Path("/tmp/target_course_downloads.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
print(f"manifest=/tmp/target_course_downloads.json entries={len(manifest)}")
