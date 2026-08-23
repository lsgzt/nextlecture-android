import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeCourseCode, extractYear, normalizePaper } from './catalog.js';
import { keywordOverlap } from './rag.js';
import { pageBlocksForGemini } from './pdf.js';

test('normalizes catalog course identifiers without losing the paper number', () => {
  assert.equal(normalizeCourseCode('16376_PCME_110_Makeup_Nov_2025.pdf'), 'PCME-110');
  assert.equal(normalizeCourseCode('PCCE-111 · November 2024 question paper'), 'PCCE-111');
  assert.equal(normalizeCourseCode('not a course'), null);
});

test('extracts the academic year from session/title values', () => {
  assert.equal(extractYear('May · 2025'), 2025);
  assert.equal(extractYear('course paper', 'PCME-110 Nov 2024'), 2024);
  assert.equal(extractYear('unknown'), null);
});

test('normalizes a known paper into the database import contract', () => {
  const paper = normalizePaper({
    id: 'drive-id',
    session: 'May · 2025',
    title: 'PCME-110 · May · 2025 question paper',
    fileName: '16376_PCME_110_May_2025.pdf',
    pdfUrl: 'https://drive.google.com/file/d/drive-id/view?usp=sharing',
  });
  assert.deepEqual(paper, {
    id: 'drive-id', course_code: 'PCME-110', course_name: null, year: 2025,
    exam_session: 'May · 2025', title: 'PCME-110 · May · 2025 question paper',
    drive_url: 'https://drive.google.com/file/d/drive-id/view?usp=sharing',
    source_folder_id: '11ywkOKyeixCPihsCzqZDyzy2msLXxx6w',
    source_file_name: '16376_PCME_110_May_2025.pdf',
  });
});

test('keeps exact 1-indexed page labels when making text extraction chunks', () => {
  const blocks = pageBlocksForGemini([
    { page: 1, text: 'cover', letters: 5, digits: 0 },
    { page: 2, text: 'Question one', letters: 11, digits: 0 },
    { page: 3, text: 'Question two', letters: 11, digits: 0 },
  ], 2);
  assert.equal(blocks.length, 2);
  assert.match(blocks[0].text, /^PAGE 1\ncover[\s\S]*PAGE 2\nQuestion one$/);
  assert.match(blocks[1].text, /^PAGE 3\nQuestion two$/);
});

test('uses conservative lexical overlap alongside semantic similarity', () => {
  assert.equal(keywordOverlap('Explain binary search tree traversal', 'Explain binary search tree traversal with example'), 1);
  assert.equal(keywordOverlap('Define operating system', 'Integrate a polynomial'), 0);
});
