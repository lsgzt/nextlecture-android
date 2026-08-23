import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readCatalog, toDownloadUrl } from '../src/catalog.js';
import { inspectPdf } from '../src/pdf.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outputDir = path.join(root, 'test-fixtures');
await fs.mkdir(outputDir, { recursive: true });
const { papers } = await readCatalog();
const firstBySession = new Map();
for (const paper of papers) if (!firstBySession.has(paper.exam_session)) firstBySession.set(paper.exam_session, paper);
const candidates = [...firstBySession.values()].slice(0, 20);
const inspected = [];

for (const paper of candidates) {
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

const lowText = inspected.filter((entry) => !entry.inspection.usableText);
const selected = [];
for (const entry of [...lowText, ...inspected]) {
  if (selected.length >= 10) break;
  if (!selected.some((item) => item.paper.id === entry.paper.id)) selected.push(entry);
}

const manifest = [];
for (const entry of selected) {
  const file = `${entry.paper.id}.pdf`;
  await fs.writeFile(path.join(outputDir, file), entry.bytes);
  manifest.push({ id: entry.paper.id, title: entry.paper.title, session: entry.paper.exam_session, course: entry.paper.course_code, year: entry.paper.year, file, bytes: entry.bytes.length, pageCount: entry.inspection.pageCount, usableText: entry.inspection.usableText, textStats: entry.inspection.textStats, driveUrl: entry.paper.drive_url });
}
await fs.writeFile(path.join(outputDir, 'manifest.json'), JSON.stringify({ selected: manifest, scannedCandidates: lowText.map((entry) => ({ id: entry.paper.id, title: entry.paper.title, usableText: entry.inspection.usableText, pageCount: entry.inspection.pageCount, textStats: entry.inspection.textStats })) }, null, 2));
console.log(JSON.stringify({ candidateSessions: candidates.map((paper) => paper.exam_session), downloadedCandidates: inspected.length, selected: manifest.length, scannedCandidates: lowText.length, manifest }, null, 2));
