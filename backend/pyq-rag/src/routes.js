import crypto from 'node:crypto';
import express from 'express';
import { z } from 'zod';
import { config } from './config.js';
import { readCatalog } from './catalog.js';
import { answerWithEvidence, embedText } from './gemini.js';
import {
  claimPaperBatch,
  claimSpecificPaper,
  getCache,
  getFrequency,
  getGroup,
  getGroupQuestions,
  getPaper,
  getStatusCounts,
  getDb,
  getProcessedPaperCount,
  importPapers,
  invalidateCourseCache,
  resetStalePapers,
  retryFailedPapers,
  writeCache,
} from './db.js';
import { buildEvidence, processPaper } from './rag.js';

const paperIdSchema = z.string().trim().min(5).max(200);
const courseSchema = z.string().trim().toUpperCase().transform((value) => value.replace(/^([A-Z]{2,12})-?(\d{2,4})$/, '$1-$2')).pipe(z.string().regex(/^[A-Z]{2,12}-\d{2,4}$/));
const yearSchema = z.coerce.number().int().min(1900).max(2100);
function safeEqual(left, right) {
  if (!left || !right) return false;
  const a = Buffer.from(String(left));
  const b = Buffer.from(String(right));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function adminToken(req) {
  const header = req.get('x-pyq-admin-token');
  if (header) return header;
  const auth = req.get('authorization') || '';
  return auth.startsWith('Bearer ') ? auth.slice(7) : '';
}

function requireAdmin(req, res, next) {
  if (!config.adminToken || !safeEqual(adminToken(req), config.adminToken)) return res.status(401).json({ error: 'unauthorized' });
  return next();
}

function validationError(res, result) {
  return res.status(400).json({ error: 'invalid request', details: result.error.issues.map((issue) => issue.path.join('.') || 'request').slice(0, 10) });
}

function parseCourseRange(query) {
  const course = courseSchema.safeParse(query.course);
  if (!course.success) return { error: course };
  const from = query.from == null || query.from === '' ? null : yearSchema.safeParse(query.from);
  const to = query.to == null || query.to === '' ? null : yearSchema.safeParse(query.to);
  const limit = query.limit == null || query.limit === '' ? { success: true, data: 50 } : z.coerce.number().int().min(1).max(100).safeParse(query.limit);
  if ((from && !from.success) || (to && !to.success) || !limit.success || (from?.success && to?.success && from.data > to.data)) {
    return { error: { issues: [{ path: ['range'], message: 'invalid year range' }] } };
  }
  return { course: course.data, from: from?.success ? from.data : null, to: to?.success ? to.data : null, limit: limit.data };
}

function createRateLimiter(maxPerMinute) {
  const hits = new Map();
  return (req, res, next) => {
    const now = Date.now();
    const key = String(req.ip || req.headers['x-forwarded-for'] || 'unknown').split(',')[0].trim().slice(0, 100);
    const previous = hits.get(key) || [];
    const recent = previous.filter((timestamp) => timestamp > now - 60_000).slice(-maxPerMinute);
    if (recent.length >= maxPerMinute) return res.status(429).json({ error: 'rate limit exceeded' });
    recent.push(now);
    hits.set(key, recent);
    if (hits.size > 2000) {
      for (const [entry, timestamps] of hits) if (!timestamps.some((timestamp) => timestamp > now - 60_000)) hits.delete(entry);
    }
    return next();
  };
}

export function buildRouter() {
  const router = express.Router();
  const publicLimit = createRateLimiter(config.publicRateLimitPerMinute);
  const askLimit = createRateLimiter(config.askRateLimitPerMinute);

  router.get('/admin/status', requireAdmin, async (_req, res) => {
    try {
      const recovered = await resetStalePapers();
      return res.json({ ok: true, recoveredStalePapers: recovered, ...(await getStatusCounts()) });
    } catch (error) {
      return res.status(503).json({ error: 'status unavailable' });
    }
  });

  router.post('/admin/seed', requireAdmin, async (req, res) => {
    const parsed = z.object({ limit: z.number().int().min(1).max(1628).default(1628), offset: z.number().int().min(0).max(1627).default(0) }).safeParse(req.body || {});
    if (!parsed.success) return validationError(res, parsed);
    try {
      const catalog = await readCatalog();
      const selected = catalog.papers.slice(parsed.data.offset, parsed.data.offset + parsed.data.limit);
      let upserted = 0;
      for (let offset = 0; offset < selected.length; offset += 100) {
        const result = await importPapers(selected.slice(offset, offset + 100));
        upserted += result.upserted;
      }
      return res.json({ ok: true, sourceFolderUrl: catalog.sourceFolderUrl, sourceCount: catalog.papers.length, selectedCount: selected.length, upserted, offset: parsed.data.offset });
    } catch (error) {
      console.error('[PYQ] seed failed', error.message);
      return res.status(503).json({ error: 'seed failed' });
    }
  });

  router.post('/admin/retry-failed', requireAdmin, async (req, res) => {
    const parsed = z.object({ limit: z.number().int().min(1).max(config.batchMax).default(config.batchMax) }).safeParse(req.body || {});
    if (!parsed.success) return validationError(res, parsed);
    try {
      return res.json({ ok: true, reset: await retryFailedPapers(parsed.data.limit) });
    } catch (error) {
      console.error('[PYQ] retry failed', error.message);
      return res.status(503).json({ error: 'retry unavailable' });
    }
  });

  router.post('/admin/process-one', requireAdmin, async (req, res) => {
    const parsed = z.object({ paperId: paperIdSchema, force: z.boolean().default(false) }).safeParse(req.body || {});
    if (!parsed.success) return validationError(res, parsed);
    try {
      const claimed = await claimSpecificPaper(parsed.data.paperId, parsed.data.force);
      if (!claimed) return res.status(409).json({ error: 'paper is already processing or not eligible' });
      const result = await processPaper(claimed, { force: parsed.data.force });
      await invalidateCourseCache(claimed.course_code);
      return res.json({ ok: true, result });
    } catch (error) {
      console.error('[PYQ] process-one failed', error.message);
      return res.status(502).json({ error: 'paper processing failed', paperId: parsed.data.paperId });
    }
  });

  router.post('/admin/process-batch', requireAdmin, async (req, res) => {
    const parsed = z.object({ limit: z.number().int().min(1).max(config.batchMax).default(Math.min(2, config.batchMax)), includeFailed: z.boolean().default(false) }).safeParse(req.body || {});
    if (!parsed.success) return validationError(res, parsed);
    try {
      const claimed = await claimPaperBatch(parsed.data.limit, parsed.data.includeFailed);
      const results = [];
      for (const paper of claimed) {
        try {
          results.push(await processPaper(paper));
          await invalidateCourseCache(paper.course_code);
        } catch (error) {
          results.push({ id: paper.id, status: 'failed', error: error.message.slice(0, 300) });
        }
      }
      return res.json({ ok: true, claimed: claimed.length, results, resumable: true });
    } catch (error) {
      console.error('[PYQ] process-batch failed', error.message);
      return res.status(503).json({ error: 'batch unavailable' });
    }
  });

  router.get('/pyq/frequently-asked', publicLimit, async (req, res) => {
    const range = parseCourseRange(req.query);
    if (range.error) return validationError(res, range);
    try {
      let rows;
      let servedFromCache = false;
      if (range.from == null && range.to == null) {
        const cached = await getCache(range.course, null, null);
        if (cached && !cached.invalidated_at && Date.now() - Date.parse(cached.generated_at) < 15 * 60_000) {
          rows = cached.analysis?.groups || [];
          servedFromCache = true;
        }
      }
      if (!rows) {
        rows = await getFrequency(range.course, range.from, range.to, range.limit);
        if (range.from == null && range.to == null) {
          const count = await getProcessedPaperCount(range.course);
          await writeCache(range.course, null, null, { groups: rows }, count);
        }
      }
      res.set('cache-control', 'public, max-age=60, stale-while-revalidate=300');
      return res.json({ course: range.course, from: range.from, to: range.to, groups: rows, servedFromCache, generatedAt: new Date().toISOString() });
    } catch (error) {
      console.error('[PYQ] frequently-asked failed', error.message);
      return res.status(503).json({ error: 'analysis unavailable' });
    }
  });

  router.get('/pyq/frequently-asked/:groupId', publicLimit, async (req, res) => {
    const parsed = z.coerce.number().int().positive().safeParse(req.params.groupId);
    if (!parsed.success) return res.status(400).json({ error: 'invalid group id' });
    try {
      const group = await getGroup(parsed.data);
      if (!group) return res.status(404).json({ error: 'group not found' });
      const occurrences = await getGroupQuestions(parsed.data);
      return res.json({ group, frequency: new Set(occurrences.map((item) => item.question?.paper_id).filter(Boolean)).size, occurrences });
    } catch (error) {
      console.error('[PYQ] group detail failed', error.message);
      return res.status(503).json({ error: 'group unavailable' });
    }
  });

  router.post('/pyq/ask', askLimit, async (req, res) => {
    const parsed = z.object({ course: courseSchema, question: z.string().trim().min(3).max(1000), topK: z.number().int().min(1).max(5).default(5) }).safeParse(req.body || {});
    if (!parsed.success) return validationError(res, parsed);
    try {
      const embedding = await embedText(parsed.data.question, 'RETRIEVAL_QUERY');
      const { data, error } = await getDb().rpc('match_pyq_questions', { query_embedding: embedding, match_threshold: 0.45, match_count: parsed.data.topK, filter_course_code: parsed.data.course });
      if (error) throw new Error(`retrieval failed: ${error.message}`);
      const evidence = buildEvidence(data || []);
      const answer = evidence.length ? await answerWithEvidence(parsed.data.question, evidence) : 'I could not find an indexed previous-year question for this course yet. Try again after the course papers have been processed.';
      return res.json({ course: parsed.data.course, answer, evidence });
    } catch (error) {
      console.error('[PYQ] ask failed', error.message);
      return res.status(503).json({ error: 'ask unavailable' });
    }
  });

  return router;
}
