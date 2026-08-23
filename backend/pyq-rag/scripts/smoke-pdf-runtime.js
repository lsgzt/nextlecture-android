import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { inspectPdf } from '../src/pdf.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(await fs.readFile(path.join(root, 'test-fixtures', 'manifest.json'), 'utf8'));
const target = manifest.selected.find((row) => !row.usableText) || manifest.selected[0];
const bytes = await fs.readFile(path.join(root, 'test-fixtures', target.file));
const result = await inspectPdf(bytes);
console.log(JSON.stringify({ id: target.id, pageCount: result.pageCount, usableText: result.usableText, textStats: result.textStats }));
