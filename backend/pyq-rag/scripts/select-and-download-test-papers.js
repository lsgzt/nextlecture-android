import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readCatalog, toDownloadUrl } from '../src/catalog.js';
import { inspectPdf } from '../src/pdf.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outputDir = path.join(root, 'test-fixtures');
await fs.mkdir(outputDir, { recursive: true });
const { papers } = await readCatalog();
const byCourse = new Map();
for (const paper of papers) {
  if (!byCourse.has(paper.course_code)) byCourse.set(paper.course_code, []);
  byCourse.get(paper.course_code).push(paper);
}
const repeatedCourse = [...byCourse.entries()]
  .sort((left, right) => new Set(right[1].map((paper) => paper.exam_session)).size - new Set(left[1].map((paper) => paper.exam_session)).size)[0];
const repeatedCourseCode = repeatedCourse?.[0];
const repeatedCoursePapers = (repeatedCourse?.[1] || []).filter((paper, index, list) => list.findIndex((item) => item.exam_session === paper.exam_session) === index).slice(0, 5);
const selectedSeed = [...repeatedCoursePapers];
const usedSessions = new Set(selectedSeed.map((paper) => paper.exam_session));
for (const paper of papers) {
  if (selectedSeed.length >= 14) break;
  if (!usedSessions.has(paper.exam_session) && !selectedSeed.some((item) => item.id === paper.id)) {
    selectedSeed.push(paper);
    usedSessions.add(paper.exam_session);
  }
}
const inspected = [];
for (const paper of selectedSeed) {
  try {
    const response = await fetch(toDownloadUrl(paper.drive_url), { redirect: 'follow', headers: { accept: 'application/pdf' } });
    if (!response.ok) continue;
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes.subarray(0, 5).toString('ascii') !== '%PDF-') continue;
    const inspection = await inspectPdf(bytes);
    inspected.push({ paper, bytes, inspection });
    if (inspected.length >= 14) break;
  } catch (error) {
    console.warn(`skip ${paper.id}: ${error.message}`);
  }
}
const selected = inspected.slice(0, 10);
const manifest = [];
for (const entry of selected) {
  const file = `${entry.paper.id}.pdf`;
  await fs.writeFile(path.join(outputDir, file), entry.bytes);
  manifest.push({ id: entry.paper.id, title: entry.paper.title, session: entry.paper.exam_session, course: entry.paper.course_code, year: entry.paper.year, file, bytes: entry.bytes.length, pageCount: entry.inspection.pageCount, usableText: entry.inspection.usableText, textStats: entry.inspection.textStats, driveUrl: entry.paper.drive_url });
}
await fs.writeFile(path.join(outputDir, 'manifest.json'), JSON.stringify({ repeatedCourseCode, selected: manifest, scannedCandidates: inspected.filter((entry) => !entry.inspection.usableText).map((entry) => ({ id: entry.paper.id, title: entry.paper.title, usableText: entry.inspection.usableText, pageCount: entry.inspection.pageCount, textStats: entry.inspection.textStats })) }, null, 2));
console.log(JSON.stringify({ repeatedCourseCode, downloadedCandidates: inspected.length, selected: manifest.length, scannedCandidates: inspected.filter((entry) => !entry.inspection.usableText).length, manifest }, null, 2));
