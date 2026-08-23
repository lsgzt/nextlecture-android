import crypto from 'node:crypto';
import { config } from './config.js';
import { toDownloadUrl } from './catalog.js';
import { downloadKnownPdf, inspectPdf, normalizeTextQuestion, pageBlocksForGemini } from './pdf.js';
import { embedTexts, extractQuestionsFromPdfVision, extractQuestionsFromText } from './gemini.js';
import {
  addGroupMember,
  createQuestionGroup,
  deleteQuestionsForPaper,
  findNearestQuestion,
  getQuestionGroups,
  insertQuestions,
  markPaperCompleted,
  markPaperFailed,
  refreshGroupFrequency,
  updateQuestionEmbedding,
} from './db.js';

const STOP_WORDS = new Set(['the', 'and', 'for', 'with', 'from', 'that', 'this', 'what', 'which', 'into', 'are', 'was', 'were', 'how', 'why', 'when', 'where', 'your', 'their', 'then', 'than', 'have', 'has', 'had', 'not', 'all', 'any', 'each', 'show', 'write', 'give', 'using', 'use', 'following', 'following']);

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

function keywordSet(text) {
  return new Set((String(text).toLowerCase().match(/[a-z][a-z0-9]{2,}/g) || []).filter((word) => !STOP_WORDS.has(word)));
}

export function keywordOverlap(a, b) {
  const left = keywordSet(a);
  const right = keywordSet(b);
  if (!left.size || !right.size) return 0;
  let intersection = 0;
  for (const word of left) if (right.has(word)) intersection += 1;
  return intersection / Math.min(left.size, right.size);
}

function parseMarks(value) {
  if (value == null) return null;
  const match = String(value).match(/\d+(?:\.\d+)?/);
  return match ? Number(match[0]) : null;
}

function dedupeQuestions(questions) {
  const seen = new Set();
  const result = [];
  for (const raw of questions) {
    const question = normalizeTextQuestion(raw);
    const key = `${question.source_page}|${question.normalized_question}`;
    if (question.normalized_question.length < 8 || seen.has(key)) continue;
    seen.add(key);
    result.push({ ...question, marks: parseMarks(question.marks) });
  }
  return result.slice(0, config.maxQuestionsPerPaper);
}

async function extractPaperQuestions(bytes, inspection) {
  if (inspection.usableText) {
    try {
      const questions = [];
      for (const block of pageBlocksForGemini(inspection.pages, 8)) {
        const chunk = await extractQuestionsFromText(block.text, inspection.pageCount);
        questions.push(...chunk);
      }
      const deduped = dedupeQuestions(questions);
      if (deduped.length) return { questions: deduped, method: 'text' };
    } catch (textError) {
      // A text extraction/model failure is explicitly not terminal. The original PDF goes through vision below.
      console.warn(`[PYQ] text route failed; trying document vision: ${textError.message}`);
    }
  }
  const visionQuestions = await extractQuestionsFromPdfVision(bytes, inspection.pageCount);
  const deduped = dedupeQuestions(visionQuestions);
  if (!deduped.length) throw new Error('Gemini document vision returned no exam questions');
  return { questions: deduped, method: 'vision' };
}

async function groupQuestion(question, courseCode) {
  const candidates = await findNearestQuestion(question, courseCode, config.groupUncertainThreshold);
  const ranked = candidates
    .filter((candidate) => candidate.paper_id !== question.paper_id)
    .map((candidate) => ({ ...candidate, keywordOverlap: keywordOverlap(question.question_text, candidate.question_text) }))
    .sort((left, right) => Number(right.similarity) - Number(left.similarity));
  const best = ranked[0];
  if (!best || Number(best.similarity) < config.groupStrongThreshold || best.keywordOverlap < config.groupKeywordOverlapMin) {
    return { grouped: false, reason: best ? 'uncertain' : 'no-neighbor', similarity: best ? Number(best.similarity) : null };
  }
  const existingGroups = await getQuestionGroups(best.question_id);
  let groupId = existingGroups[0]?.group_id;
  if (!groupId) {
    const group = await createQuestionGroup({
      courseCode,
      title: `Repeated question: ${question.question_text.slice(0, 140)}`,
      description: 'Conservatively grouped by embedding similarity and keyword overlap. Frequency counts distinct papers.',
      confidence: Number(best.similarity),
    });
    groupId = group.id;
    await addGroupMember(groupId, best.question_id, Number(best.similarity));
  }
  await addGroupMember(groupId, question.id, Number(best.similarity));
  await refreshGroupFrequency(groupId);
  return { grouped: true, groupId, similarity: Number(best.similarity) };
}

export async function processPaper(paper, { force = false } = {}) {
  if (!paper?.id) throw new Error('Paper row is required');
  try {
    const bytes = await downloadKnownPdf(toDownloadUrl(paper.drive_url));
    const contentHash = sha256(bytes);
    if (!force && paper.processing_status === 'completed' && paper.content_hash === contentHash) {
      return { id: paper.id, status: 'completed', skipped: true, reason: 'content-unchanged', pageCount: paper.page_count };
    }
    const inspection = await inspectPdf(bytes);
    const extracted = await extractPaperQuestions(bytes, inspection);
    await deleteQuestionsForPaper(paper.id);
    let insertedCount = 0;
    const rows = extracted.questions.map((question) => ({
      paper_id: paper.id,
      question_number: question.question_number || 'unknown',
      question_text: question.question_text,
      normalized_question: question.normalized_question,
      section: question.section,
      marks: question.marks,
      unit: question.unit,
      source_page: question.source_page,
      extraction_method: extracted.method,
      extraction_confidence: question.extraction_confidence,
    }));
    for (let offset = 0; offset < rows.length; offset += 50) {
      const batch = rows.slice(offset, offset + 50);
      const inserted = await insertQuestions(batch);
      insertedCount += inserted.length;
      const embeddings = await embedTexts(inserted.map((question) => question.question_text), 'CLUSTERING');
      for (const [index, question] of inserted.entries()) {
        const embedding = embeddings[index];
        await updateQuestionEmbedding(question.id, embedding);
        await groupQuestion({ ...question, embedding }, paper.course_code);
      }
    }
    await markPaperCompleted(paper.id, { content_hash: contentHash, page_count: inspection.pageCount });
    return { id: paper.id, status: 'completed', skipped: false, pageCount: inspection.pageCount, questionCount: insertedCount, extractionMethod: extracted.method, textStats: inspection.textStats };
  } catch (error) {
    await markPaperFailed(paper.id, error.message);
    throw error;
  }
}

export function buildEvidence(rows) {
  return rows.map((row) => ({
    questionId: row.question_id,
    question: row.question_text,
    sourcePage: row.source_page,
    paperId: row.paper_id,
    paperTitle: row.paper_title,
    session: row.exam_session,
    year: row.exam_year,
    driveUrl: row.drive_url,
    similarity: Number(row.similarity),
  }));
}
