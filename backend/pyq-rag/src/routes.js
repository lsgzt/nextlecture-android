import crypto from 'node:crypto';
import express from 'express';
import { z } from 'zod';
import { config } from './config.js';
import { readCatalog } from './catalog.js';
import { answerWithEvidence, embedText } from './gemini.js';
import {
  claimCoursePaper,
  claimCourseRetry,
  claimPaperBatch,
  claimSpecificPaper,
  getCache,
  getCourseCoverage,
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
import { getNoticeFeed } from './notices.js';
import { getHolidayFeed } from './holidays.js';
import {
  authenticateAttendance,
  attendanceRecordSchema,
  attendanceSessionSchema,
  createAttendanceSession,
  listAttendance,
  removeAttendance,
  upsertAttendance,
} from './attendance.js';
import { getLeaderboard, leaderboardQuery } from './leaderboard.js';

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

function isAllowedTimetableUrl(value) {
  try {
    const url = new URL(String(value || '').trim());
    return url.protocol === 'https:' && url.hostname === 'appsc.gndec.ac.in' && url.pathname.toLowerCase().endsWith('.html') ? url.toString() : null;
  } catch {
    return null;
  }
}

function retryFailureReason(error) {
  const message = String(error?.message || '').toLowerCase();
  return /quota|rate limit|resource exhausted|too many requests|invalid api key|api key not valid|unauthorized|forbidden/.test(message) ? 'provider_quota' : 'processing_error';
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
  // NOTE (2026-11-11): Heavy RAG paper processing, seeding, batch jobs, public retry,
  // and live /pyq/ask (Gemini + embeddings) are intentionally disabled to reduce
  // Vercel Hobby usage. GET /pyq/frequently-asked and group detail endpoints that
  // serve already-indexed FAQ data from Supabase remain fully active.
  const router = express.Router();
  const publicLimit = createRateLimiter(config.publicRateLimitPerMinute);
  const askLimit = createRateLimiter(config.askRateLimitPerMinute);
  const retryLimit = createRateLimiter(2);
  const attendanceSessionLimit = createRateLimiter(8);
  const attendanceMutationLimit = createRateLimiter(120);
  const leaderboardLimit = createRateLimiter(30);

  router.get('/admin/status', requireAdmin, async (_req, res) => {
    try {
      const recovered = await resetStalePapers();
      return res.json({ ok: true, recoveredStalePapers: recovered, ...(await getStatusCounts()) });
    } catch (error) {
      return res.status(503).json({ error: 'status unavailable' });
    }
  });

  // HEAVY RAG disabled to reduce Vercel Hobby usage (seed / catalog import)
  router.post('/admin/seed', requireAdmin, async (_req, res) => {
    return res.status(503).json({ error: 'rag processing temporarily disabled', message: 'Paper seeding/processing is paused to conserve Vercel Hobby limits. Already-indexed FAQ reads remain available.' });
  });

  // HEAVY RAG disabled
  router.post('/admin/retry-failed', requireAdmin, async (_req, res) => {
    return res.status(503).json({ error: 'rag processing temporarily disabled', message: 'Retry/failed-paper processing is paused. FAQ reads remain available.' });
  });

  router.post('/attendance/session', attendanceSessionLimit, async (req, res) => {
    const parsed = attendanceSessionSchema.safeParse(req.body || {});
    if (!parsed.success) return validationError(res, parsed);
    try {
      return res.status(201).set('cache-control', 'no-store').json(await createAttendanceSession(parsed.data));
    } catch (error) {
      console.error('[ATTENDANCE] session failed', error.message);
      return res.status(503).json({ error: 'attendance session unavailable' });
    }
  });

  router.get('/attendance', attendanceMutationLimit, async (req, res) => {
    try {
      const student = await authenticateAttendance(req);
      if (!student) return res.status(401).json({ error: 'attendance authentication required' });
      const result = await listAttendance(student.id, req.query);
      return res.set('cache-control', 'no-store').json(result);
    } catch (error) {
      if (error?.name === 'ZodError') return res.status(400).json({ error: 'invalid attendance request' });
      console.error('[ATTENDANCE] list failed', error.message);
      return res.status(503).json({ error: 'attendance unavailable' });
    }
  });

  router.get('/attendance/leaderboard', leaderboardLimit, async (req, res) => {
    try {
      const student = await authenticateAttendance(req);
      if (!student) return res.status(401).json({ error: 'attendance authentication required' });
      const parsed = leaderboardQuery(req.query);
      const result = await getLeaderboard(student, parsed);
      return res.set('cache-control', 'no-store').json(result);
    } catch (error) {
      if (error?.name === 'ZodError') return res.status(400).json({ error: 'invalid leaderboard request' });
      console.error('[ATTENDANCE] leaderboard failed', error.message);
      return res.status(503).json({ error: 'leaderboard unavailable' });
    }
  });

  router.post('/attendance/records', attendanceMutationLimit, async (req, res) => {
    try {
      const student = await authenticateAttendance(req);
      if (!student) return res.status(401).json({ error: 'attendance authentication required' });
      const parsed = attendanceRecordSchema.safeParse(req.body || {});
      if (!parsed.success) return validationError(res, parsed);
      const record = await upsertAttendance(student.id, parsed.data);
      return res.status(200).set('cache-control', 'no-store').json({ record });
    } catch (error) {
      if (error?.name === 'ZodError') return res.status(400).json({ error: 'invalid attendance request' });
      console.error('[ATTENDANCE] upsert failed', error.message);
      return res.status(503).json({ error: 'attendance write unavailable' });
    }
  });

  router.delete('/attendance/records', attendanceMutationLimit, async (req, res) => {
    try {
      const student = await authenticateAttendance(req);
      if (!student) return res.status(401).json({ error: 'attendance authentication required' });
      await removeAttendance(student.id, req.query.date, req.query.lectureKey);
      return res.status(204).set('cache-control', 'no-store').send();
    } catch (error) {
      if (error?.name === 'ZodError') return res.status(400).json({ error: 'invalid attendance request' });
      console.error('[ATTENDANCE] delete failed', error.message);
      return res.status(503).json({ error: 'attendance delete unavailable' });
    }
  });

  router.get('/timetable-source', publicLimit, async (_req, res) => {
    const url = isAllowedTimetableUrl(config.timetableUrl);
    return res.json({ url, source: url ? 'server-config' : 'none', fetchedAt: new Date().toISOString() });
  });

  router.get('/notices', publicLimit, async (req, res) => {
    try {
      const force = ['1', 'true'].includes(String(req.query.refresh || '').toLowerCase());
      const feed = await getNoticeFeed({ force });
      return res.set('cache-control', 'public, max-age=60, stale-while-revalidate=300').json({
        ...feed,
        sources: { erp: 'https://erp.gndec.ac.in/notice', homepage: 'https://gndec.ac.in/' },
      });
    } catch (error) {
      console.error('[NOTICE] feed failed', error.message);
      return res.status(503).json({ error: 'notice feed unavailable' });
    }
  });

  router.get('/holidays', publicLimit, async (req, res) => {
    try {
      const force = ['1', 'true'].includes(String(req.query.refresh || '').toLowerCase());
      const feed = await getHolidayFeed({ force });
      return res.set('cache-control', 'public, max-age=300, stale-while-revalidate=3600').json({
        ...feed,
        source: 'https://gndec.ac.in/?q=holidays',
      });
    } catch (error) {
      console.error('[HOLIDAY] feed failed', error.message);
      return res.status(503).json({ error: 'holiday feed unavailable' });
    }
  });

  // HEAVY RAG disabled — public single-paper retry (Gemini + PDF processing)
  router.post('/pyq/retry-course', retryLimit, async (req, res) => {
    const parsed = z.object({ course: courseSchema }).strict().safeParse(req.body || {});
    if (!parsed.success) return validationError(res, parsed);
    try {
      const coverage = await getCourseCoverage(parsed.data.course);
      return res.status(503).json({
        ok: false,
        accepted: false,
        status: 'rag_disabled',
        message: 'Paper processing is temporarily disabled to conserve Vercel Hobby limits. Already-indexed Frequently Asked data remains available.',
        coverage,
      });
    } catch (error) {
      return res.status(503).json({ error: 'rag processing temporarily disabled' });
    }
  });

  // HEAVY RAG disabled
  router.post('/admin/process-one', requireAdmin, async (_req, res) => {
    return res.status(503).json({ error: 'rag processing temporarily disabled', message: 'Single-paper processing is paused. FAQ reads remain available.' });
  });

  // HEAVY RAG disabled
  router.post('/admin/process-batch', requireAdmin, async (_req, res) => {
    return res.status(503).json({ error: 'rag processing temporarily disabled', message: 'Batch paper processing is paused. FAQ reads remain available.' });
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
      const coverage = await getCourseCoverage(range.course);
      res.set('cache-control', 'public, max-age=60, stale-while-revalidate=300');
      return res.json({ course: range.course, from: range.from, to: range.to, groups: rows, coverage, servedFromCache, generatedAt: new Date().toISOString() });
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

  // HEAVY RAG disabled — /pyq/ask does live embeddings + Gemini generation
  router.post('/pyq/ask', askLimit, async (_req, res) => {
    return res.status(503).json({
      error: 'rag ask temporarily disabled',
      message: 'Live question answering (embeddings + Gemini) is paused to conserve Vercel Hobby limits. Use Frequently Asked for already-indexed groups.',
    });
  });

  return router;
}
