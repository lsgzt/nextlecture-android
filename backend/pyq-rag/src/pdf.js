import * as pdfjsLib from 'pdfjs-dist/legacy/build/pdf.mjs';
import { config } from './config.js';

function assertPdfBytes(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length < 5 || buffer.subarray(0, 5).toString('ascii') !== '%PDF-') {
    throw new Error('Downloaded content is not a PDF');
  }
  if (buffer.length > config.maxPdfBytes) throw new Error(`PDF exceeds ${config.maxPdfBytes} byte limit`);
}

function pageTextFromContent(content) {
  return content.items
    .map((item) => typeof item?.str === 'string' ? item.str : '')
    .join(' ')
    .replace(/[ \t]+/g, ' ')
    .replace(/\u0000/g, '')
    .trim();
}

export async function inspectPdf(buffer) {
  assertPdfBytes(buffer);
  const loadingTask = pdfjsLib.getDocument({ data: new Uint8Array(buffer), useWorkerFetch: false, isEvalSupported: false });
  const document = await loadingTask.promise;
  const pageCount = document.numPages;
  if (!Number.isInteger(pageCount) || pageCount < 1 || pageCount > config.maxPdfPages) {
    await document.destroy();
    throw new Error(`PDF page count must be between 1 and ${config.maxPdfPages}`);
  }
  const pages = [];
  try {
    for (let pageNumber = 1; pageNumber <= pageCount; pageNumber += 1) {
      const page = await document.getPage(pageNumber);
      const content = await page.getTextContent({ includeMarkedContent: false });
      const text = pageTextFromContent(content);
      const letters = (text.match(/[A-Za-z\u00C0-\u024F]/g) || []).length;
      const digits = (text.match(/\d/g) || []).length;
      pages.push({ page: pageNumber, text, letters, digits });
      page.cleanup();
    }
  } finally {
    await document.destroy();
  }
  const meaningfulPageCount = pages.filter((page) => page.letters >= 20 || page.text.length >= 80).length;
  const letterCount = pages.reduce((sum, page) => sum + page.letters, 0);
  const normalizedLength = pages.reduce((sum, page) => sum + page.text.replace(/\s/g, '').length, 0);
  const usableText = normalizedLength >= Math.max(160, pageCount * 35)
    && letterCount >= Math.max(100, pageCount * 20)
    && meaningfulPageCount >= Math.max(1, Math.ceil(pageCount * 0.45));
  return {
    pageCount,
    pages,
    usableText,
    textStats: { normalizedLength, letterCount, meaningfulPageCount },
  };
}

export async function downloadKnownPdf(downloadUrl) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 60_000);
  try {
    const response = await fetch(downloadUrl, {
      redirect: 'follow',
      signal: controller.signal,
      headers: { accept: 'application/pdf,application/octet-stream;q=0.9' },
    });
    if (!response.ok) throw new Error(`Drive download HTTP ${response.status}`);
    const declaredLength = Number(response.headers.get('content-length') || 0);
    if (declaredLength > config.maxPdfBytes) throw new Error('PDF content-length exceeds configured limit');
    const bytes = Buffer.from(await response.arrayBuffer());
    assertPdfBytes(bytes);
    return bytes;
  } catch (error) {
    if (error?.name === 'AbortError') throw new Error('Drive PDF download timed out');
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

export function pageBlocksForGemini(pages, maxPagesPerCall = 10) {
  const blocks = [];
  for (let offset = 0; offset < pages.length; offset += maxPagesPerCall) {
    const chunk = pages.slice(offset, offset + maxPagesPerCall)
      .map((page) => `PAGE ${page.page}\n${page.text}`)
      .join('\n\n');
    if (chunk.trim()) blocks.push({ firstPage: pages[offset].page, lastPage: pages[Math.min(offset + maxPagesPerCall - 1, pages.length - 1)].page, text: chunk });
  }
  return blocks;
}

export function normalizeTextQuestion(question) {
  return {
    ...question,
    question_text: question.question_text.replace(/\s+/g, ' ').trim(),
    normalized_question: question.question_text.toLowerCase().replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim(),
  };
}
