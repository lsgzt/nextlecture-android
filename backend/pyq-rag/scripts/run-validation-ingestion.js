import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readCatalog } from '../src/catalog.js';
import { claimSpecificPaper, getStatusCounts, importPapers } from '../src/db.js';
import { processPaper } from '../src/rag.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(await fs.readFile(path.join(root, 'test-fixtures', 'manifest.json'), 'utf8'));
const count = Math.max(1, Math.min(10, Number(process.argv[2] || 10)));
const catalog = await readCatalog();
const selected = manifest.selected.slice(0, count).map((row) => catalog.papers.find((paper) => paper.id === row.id)).filter(Boolean);
if (selected.length !== count) throw new Error(`Could not resolve ${count} manifest papers in the catalog`);
await importPapers(selected);
const results = [];
for (const selectedPaper of selected) {
  const claimed = await claimSpecificPaper(selectedPaper.id, true);
  if (!claimed) throw new Error(`Could not claim ${selectedPaper.id}`);
  try {
    const result = await processPaper(claimed, { force: true });
    results.push(result);
    console.log(JSON.stringify(result));
  } catch (error) {
    results.push({ id: selectedPaper.id, status: 'failed', error: error.message.slice(0, 500) });
    console.error(JSON.stringify(results.at(-1)));
  }
}
console.log(JSON.stringify({ results, status: await getStatusCounts() }, null, 2));
