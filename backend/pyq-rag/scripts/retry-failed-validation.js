import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readCatalog } from '../src/catalog.js';
import { claimSpecificPaper, getStatusCounts } from '../src/db.js';
import { processPaper } from '../src/rag.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(await fs.readFile(path.join(root, 'test-fixtures', 'manifest.json'), 'utf8'));
const targetId = process.argv[2] || '1rqfElKZTqRRca9epIpW1wrGmI0FYQuVG';
const catalog = await readCatalog();
const paper = catalog.papers.find((item) => item.id === targetId);
if (!paper) throw new Error(`Unknown validation paper ${targetId}`);
for (let attempt = 1; attempt <= 3; attempt += 1) {
  const claimed = await claimSpecificPaper(paper.id, true);
  try {
    const result = await processPaper(claimed, { force: true });
    console.log(JSON.stringify({ attempt, result, status: await getStatusCounts() }, null, 2));
    process.exit(0);
  } catch (error) {
    console.error(JSON.stringify({ attempt, error: error.message.slice(0, 500) }));
    if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, attempt * 5000));
  }
}
console.log(JSON.stringify(await getStatusCounts(), null, 2));
process.exit(1);
