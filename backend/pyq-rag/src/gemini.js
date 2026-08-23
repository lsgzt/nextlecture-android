import { config, hasGemini } from './config.js';

const GEMINI_API_ROOT = 'https://generativelanguage.googleapis.com/v1beta/models';

function modelPath(model) {
  const clean = String(model || '').replace(/^models\//, '').trim();
  if (!/^[A-Za-z0-9._-]{2,120}$/.test(clean)) throw new Error('Invalid Gemini model name');
  return clean;
}

async function fetchJson(url, body, timeoutMs = 120_000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const raw = await response.text();
    let data = null;
    try { data = raw ? JSON.parse(raw) : null; } catch { /* handled below */ }
    if (!response.ok) {
      const message = typeof data?.error?.message === 'string' ? data.error.message.slice(0, 500) : `Gemini HTTP ${response.status}`;
      throw new Error(message);
    }
    return data;
  } catch (error) {
    if (error?.name === 'AbortError') throw new Error('Gemini request timed out');
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

function extractText(data) {
  const parts = data?.candidates?.[0]?.content?.parts;
  if (!Array.isArray(parts)) return '';
  return parts.map((part) => typeof part?.text === 'string' ? part.text : '').join('');
}

function parseJsonObject(text) {
  const trimmed = String(text || '').trim();
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)\s*```/i)?.[1];
  const candidate = fenced || trimmed;
  try { return JSON.parse(candidate); } catch {
    const first = candidate.indexOf('{');
    const last = candidate.lastIndexOf('}');
    if (first >= 0 && last > first) return JSON.parse(candidate.slice(first, last + 1));
    throw new Error('Gemini returned invalid JSON');
  }
}

function normalizeQuestions(payload, pageCount) {
  const raw = Array.isArray(payload) ? payload : payload?.questions;
  if (!Array.isArray(raw)) throw new Error('Gemini JSON did not contain a questions array');
  return raw.map((question, index) => {
    const sourcePage = Number(question?.source_page ?? question?.sourcePage ?? question?.page);
    const questionText = typeof question?.question_text === 'string' ? question.question_text.trim() : typeof question?.questionText === 'string' ? question.questionText.trim() : '';
    if (!Number.isInteger(sourcePage) || sourcePage < 1 || sourcePage > pageCount) throw new Error(`Gemini returned invalid source page for question ${index + 1}`);
    if (!questionText || questionText.length > 8000) throw new Error(`Gemini returned invalid question text for question ${index + 1}`);
    return {
      question_number: String(question?.question_number ?? question?.questionNumber ?? `${index + 1}`).trim().slice(0, 80),
      question_text: questionText,
      section: typeof question?.section === 'string' ? question.section.trim().slice(0, 160) : null,
      marks: question?.marks == null ? null : String(question.marks).trim().slice(0, 30),
      unit: typeof question?.unit === 'string' ? question.unit.trim().slice(0, 160) : null,
      source_page: sourcePage,
      extraction_confidence: Math.max(0, Math.min(1, Number(question?.extraction_confidence ?? question?.confidence ?? 0.8) || 0.8)),
    };
  }).filter((question) => question.question_text.length >= 8);
}

const JSON_SCHEMA = {
  type: 'OBJECT',
  properties: {
    questions: {
      type: 'ARRAY',
      items: {
        type: 'OBJECT',
        properties: {
          question_number: { type: 'STRING' },
          question_text: { type: 'STRING' },
          section: { type: 'STRING', nullable: true },
          marks: { type: 'STRING', nullable: true },
          unit: { type: 'STRING', nullable: true },
          source_page: { type: 'INTEGER' },
          extraction_confidence: { type: 'NUMBER' },
        },
        required: ['question_number', 'question_text', 'source_page', 'extraction_confidence'],
      },
    },
  },
  required: ['questions'],
};

export async function extractQuestionsFromText(pageBlocks, pageCount) {
  if (!hasGemini()) throw new Error('Gemini server configuration is missing');
  const prompt = [
    'Extract every actual exam question from the following machine-readable pages of one university previous-year question paper.',
    'Do not summarize, merge, invent, or omit questions. Keep subparts as part of their parent question when they are printed together.',
    'Return ONLY JSON matching the supplied schema. Every question must use the exact 1-indexed PDF page number from the PAGE marker, not a guessed page.',
    'Ignore cover-page metadata, instructions, blank pages, answer keys, and repeated headers unless they are actual questions.',
    pageBlocks,
  ].join('\n\n');
  const data = await fetchJson(`${GEMINI_API_ROOT}/${modelPath(config.geminiDocumentModel)}:generateContent?key=${encodeURIComponent(config.geminiApiKey)}`, {
    contents: [{ role: 'user', parts: [{ text: prompt }] }],
    generationConfig: {
      temperature: 0,
      responseMimeType: 'application/json',
      responseSchema: JSON_SCHEMA,
    },
  });
  return normalizeQuestions(parseJsonObject(extractText(data)), pageCount);
}

export async function extractQuestionsFromPdfVision(pdfBuffer, pageCount) {
  if (!hasGemini()) throw new Error('Gemini server configuration is missing');
  if (!Buffer.isBuffer(pdfBuffer) || pdfBuffer.length === 0) throw new Error('PDF bytes are empty');
  const prompt = [
    'Read this entire university previous-year question paper as a document, including scanned/image-only pages.',
    'Extract every actual exam question without summarizing, merging unrelated questions, inventing text, or omitting any visible topic/unit/subpart.',
    'Return ONLY JSON matching the supplied schema. Every question must include source_page as the exact 1-indexed PDF page where the question begins. If it spans pages, use the page where it begins.',
    `The PDF has exactly ${pageCount} pages. source_page must be an integer from 1 through ${pageCount}.`,
    'Ignore cover metadata, instructions, blank pages, answer keys, and repeated headers unless they are actual questions. Preserve question numbering, sections, marks, and unit information when visible.',
  ].join('\n');
  const data = await fetchJson(`${GEMINI_API_ROOT}/${modelPath(config.geminiDocumentModel)}:generateContent?key=${encodeURIComponent(config.geminiApiKey)}`, {
    contents: [{ role: 'user', parts: [
      { inline_data: { mime_type: 'application/pdf', data: pdfBuffer.toString('base64') } },
      { text: prompt },
    ] }],
    generationConfig: {
      temperature: 0,
      responseMimeType: 'application/json',
      responseSchema: JSON_SCHEMA,
    },
  }, 180_000);
  return normalizeQuestions(parseJsonObject(extractText(data)), pageCount);
}

export async function answerWithEvidence(question, evidence) {
  if (!hasGemini()) throw new Error('Gemini server configuration is missing');
  const sourceText = evidence.map((item, index) => [
    `SOURCE ${index + 1}`,
    `Paper: ${item.paperTitle}`,
    `Session: ${item.session || 'unknown'}; Year: ${item.year || 'unknown'}`,
    `Page: ${item.sourcePage}`,
    `Question: ${item.question}`,
    `Drive URL: ${item.driveUrl}`,
  ].join('\n')).join('\n\n');
  const prompt = [
    'Answer the student using only the supplied previous-year-paper evidence.',
    'Be precise and concise. If the evidence is insufficient, say so rather than inventing an answer.',
    'When referring to a paper question, cite it inline as [Paper title — Page N]. Do not cite a page that is not present in the evidence.',
    `Student question: ${String(question).trim()}`,
    'Evidence:',
    sourceText,
  ].join('\n\n');
  const data = await fetchJson(`${GEMINI_API_ROOT}/${modelPath(config.geminiDocumentModel)}:generateContent?key=${encodeURIComponent(config.geminiApiKey)}`, {
    contents: [{ role: 'user', parts: [{ text: prompt }] }],
    generationConfig: { temperature: 0.1, maxOutputTokens: 1200 },
  }, 90_000);
  const answer = extractText(data).trim();
  if (!answer) throw new Error('Gemini returned an empty evidence-grounded answer');
  return answer.slice(0, 12_000);
}

export async function embedTexts(texts, taskType = 'CLUSTERING') {
  const cleanTexts = texts.map((text) => String(text || '').trim());
  if (!cleanTexts.length || cleanTexts.some((text) => !text)) throw new Error('Cannot batch-embed empty question text');
  if (cleanTexts.length > 50) throw new Error('Embedding batch exceeds 50 texts');
  if (!hasGemini()) throw new Error('Gemini server configuration is missing');
  const model = modelPath(config.geminiEmbeddingModel);
  try {
    const data = await fetchJson(`${GEMINI_API_ROOT}/${model}:batchEmbedContents?key=${encodeURIComponent(config.geminiApiKey)}`, {
      requests: cleanTexts.map((text) => ({ model: `models/${model}`, content: { parts: [{ text }] }, taskType, outputDimensionality: config.geminiEmbeddingDimension })),
    }, 120_000);
    const embeddings = data?.embeddings;
    if (!Array.isArray(embeddings) || embeddings.length !== cleanTexts.length) throw new Error('Gemini batch embedding response length mismatch');
    return embeddings.map((item) => validateEmbedding(item?.values));
  } catch (error) {
    console.warn(`[PYQ] batch embedding unavailable; falling back to bounded single embeddings: ${error.message}`);
    const results = [];
    for (const text of cleanTexts) results.push(await embedText(text, taskType));
    return results;
  }
}

function validateEmbedding(values) {
  if (!Array.isArray(values) || values.length !== config.geminiEmbeddingDimension || values.some((value) => !Number.isFinite(Number(value)))) {
    throw new Error(`Gemini embedding response was not ${config.geminiEmbeddingDimension}-dimensional`);
  }
  return values.map(Number);
}

export async function embedText(text, taskType = 'CLUSTERING') {
  if (!hasGemini()) throw new Error('Gemini server configuration is missing');
  const cleanText = String(text || '').trim();
  if (!cleanText) throw new Error('Cannot embed empty question text');
  const model = modelPath(config.geminiEmbeddingModel);
  const data = await fetchJson(`${GEMINI_API_ROOT}/${model}:embedContent?key=${encodeURIComponent(config.geminiApiKey)}`, {
    model: `models/${model}`,
    content: { parts: [{ text: cleanText }] },
    taskType,
    outputDimensionality: config.geminiEmbeddingDimension,
  }, 60_000);
  const values = data?.embedding?.values || data?.embeddings?.[0]?.values;
  return validateEmbedding(values);
}
