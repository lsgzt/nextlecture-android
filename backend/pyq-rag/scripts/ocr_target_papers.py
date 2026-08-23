import json
import re
import shutil
import subprocess
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

manifest = json.loads(Path("/tmp/target_course_text_stats.json").read_text())
out_dir = Path("/home/ubuntu/apk-work/backend/pyq-rag/work/target-ocr")
out_dir.mkdir(parents=True, exist_ok=True)
results = []
for index, paper in enumerate(manifest, 1):
    source = Path(paper["localPath"])
    page_count = int(paper.get("pageCount") or 0)
    destination = out_dir / f"{paper['id']}.txt"
    page_methods = []
    page_texts = []
    with tempfile.TemporaryDirectory(prefix="pyq-ocr-") as temp_dir:
        for page in range(1, page_count + 1):
            direct = subprocess.run(["pdftotext", "-layout", "-f", str(page), "-l", str(page), str(source), "-"], capture_output=True, text=True, timeout=60)
            direct_text = direct.stdout if direct.returncode == 0 else ""
            direct_letters = len(re.findall(r"[A-Za-z]", direct_text))
            if direct_letters >= 20:
                text = direct_text
                method = "text"
            else:
                prefix = str(Path(temp_dir) / f"page_{page}")
                rendered = subprocess.run(["pdftoppm", "-f", str(page), "-l", str(page), "-r", "180", "-png", "-singlefile", str(source), prefix], capture_output=True, text=True, timeout=90)
                image = Path(prefix + ".png")
                if rendered.returncode == 0 and image.exists():
                    ocr = subprocess.run(["tesseract", str(image), "stdout", "--psm", "6", "-l", "eng"], capture_output=True, text=True, timeout=90)
                    text = ocr.stdout if ocr.returncode == 0 else ""
                else:
                    text = ""
                method = "ocr" if len(re.findall(r"[A-Za-z]", text)) >= 20 else "empty"
            page_methods.append(method)
            page_texts.append(f"PAGE {page}\n{text.strip()}\n")
    combined = "\n\n".join(page_texts)
    destination.write_text(combined, errors="replace")
    letters = len(re.findall(r"[A-Za-z]", combined))
    meaningful_pages = sum(1 for page_text in page_texts if len(re.findall(r"[A-Za-z]", page_text)) >= 20)
    status = "eligible" if letters >= 80 and meaningful_pages >= 1 else "insufficient"
    results.append({**paper, "ocrTextPath": str(destination), "ocrLength": len(" ".join(combined.split())), "ocrLetterCount": letters, "ocrMeaningfulPageCount": meaningful_pages, "pageMethods": page_methods, "status": status})
    print(f"[{index}/{len(manifest)}] {paper['course']} {paper['id']} {status} pages={page_count} letters={letters} methods={','.join(page_methods)}")

Path("/tmp/target_course_ocr_stats.json").write_text(json.dumps(results, indent=2, ensure_ascii=False))
course_summary = defaultdict(Counter)
for row in results:
    course_summary[row["course"]][row["status"]] += 1
print(json.dumps({"entries": len(results), "statusCounts": dict(Counter(row["status"] for row in results)), "courseStatusCounts": {course: dict(counts) for course, counts in sorted(course_summary.items())}}, indent=2))
