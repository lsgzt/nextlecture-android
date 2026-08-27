import crypto from 'node:crypto';
import * as cheerio from 'cheerio';
import { config } from './config.js';
import { getNoticeFeedCache, saveNoticeFeedCache } from './db.js';

export const ERP_NOTICE_URL = 'https://erp.gndec.ac.in/notice';
export const GNDEC_HOME_URL = 'https://gndec.ac.in/';

const MAX_NOTICES = 30;
const CACHE_ID = 'global';
const GNDEC_TIME_ZONE = 'Asia/Kolkata';
const MONTHS = 'January|February|March|April|May|June|July|August|September|October|November|December';
const DATE_PATTERNS = [
  new RegExp(`\\b(?:${MONTHS})\\s+\\d{1,2},\\s+\\d{4}\\b`, 'i'),
  /\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b/,
  /\b\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4}\b/i,
];
const ANNOUNCEMENT_SIGNALS = /\bnotice|circular|holiday|closure|class(?:es)?|examination|exam|result|deadline|office order|announcement|document(?:s)? submission|schedule\b/i;
const EXCLUDED_HOME_ITEMS = [
  /spot\s+counsel(?:l|)ing/i,
  /waiting\s+list/i,
  /enquir(?:y|ies).*registration/i,
  /programs?\s+offered/i,
  /fee\s+structure/i,
  /admission\s+helpline/i,
  /whatsapp/i,
  /checklist\s+of\s+documents?/i,
  /registration\s+module/i,
];

let memoryCache = null;
let refreshPromise = null;

function cleanText(value) {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function canonicalUrl(value, baseUrl) {
  try {
    const url = new URL(String(value || ''), baseUrl);
    if (!['http:', 'https:'].includes(url.protocol)) return '';
    url.hash = '';
    return url.toString();
  } catch {
    return '';
  }
}

function normalizeTitle(value) {
  return cleanText(value).toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

function parseDateToken(value, fallbackDate) {
  const raw = cleanText(value);
  const monthDate = raw.match(new RegExp(`^(${MONTHS})\\s+(\\d{1,2}),\\s+(\\d{4})$`, 'i'));
  if (monthDate) {
    const date = new Date(`${monthDate[1]} ${monthDate[2]}, ${monthDate[3]} 00:00:00Z`);
    if (!Number.isNaN(date.getTime())) return date.toISOString().slice(0, 10);
  }
  const numeric = raw.match(/^(\d{1,2})[./-](\d{1,2})[./-](\d{2,4})$/);
  if (numeric) {
    const year = Number(numeric[3]) < 100 ? 2000 + Number(numeric[3]) : Number(numeric[3]);
    const month = Number(numeric[2]);
    const day = Number(numeric[1]);
    const date = new Date(Date.UTC(year, month - 1, day));
    if (date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day) return date.toISOString().slice(0, 10);
  }
  const shortMonth = raw.match(/^(\d{1,2})\s+([A-Za-z]+)\s+(\d{4})$/);
  if (shortMonth) {
    const date = new Date(`${shortMonth[2]} ${shortMonth[1]}, ${shortMonth[3]} 00:00:00Z`);
    if (!Number.isNaN(date.getTime())) return date.toISOString().slice(0, 10);
  }
  return fallbackDate;
}

function extractPublishedDate(text, fallbackDate) {
  for (const pattern of DATE_PATTERNS) {
    const match = cleanText(text).match(pattern);
    if (match) return parseDateToken(match[0], fallbackDate);
  }
  return fallbackDate;
}

function displayDate(isoDate) {
  if (!isoDate) return 'Official update';
  const date = new Date(`${isoDate}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) return 'Official update';
  return new Intl.DateTimeFormat('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' }).format(date);
}

function stableId(source, title, publishedDate, url) {
  // Homepage content has no trustworthy publication date; its identity must remain
  // stable across daily refreshes so first-seen metadata is not reset.
  const identityDate = source === 'GNDEC ERP Notice Board' ? (publishedDate || '') : '';
  const value = `${source}|${normalizeTitle(title)}|${identityDate}|${url || ''}`;
  return `${source.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-${crypto.createHash('sha256').update(value).digest('hex').slice(0, 20)}`;
}

function makeNotice({ source, title, publishedDate, url, author = '' }) {
  const cleanTitle = cleanText(title);
  const cleanUrl = canonicalUrl(url, source === 'GNDEC homepage' ? GNDEC_HOME_URL : ERP_NOTICE_URL) || (source === 'GNDEC homepage' ? GNDEC_HOME_URL : ERP_NOTICE_URL);
  return {
    id: stableId(source, cleanTitle, publishedDate, cleanUrl),
    title: cleanTitle,
    publishedDate,
    displayDate: displayDate(publishedDate),
    url: cleanUrl,
    author: cleanText(author),
    source,
    firstSeenAt: '',
    bannerStartDate: '',
    bannerUntilDate: '',
  };
}

function localDateForInstant(instant) {
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('en-CA', { timeZone: GNDEC_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
}

function addCalendarDays(isoDate, days) {
  const date = new Date(`${isoDate}T12:00:00Z`);
  if (Number.isNaN(date.getTime())) return isoDate;
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function noticeMatches(left, right) {
  return left?.id === right?.id || (
    left?.source !== 'GNDEC ERP Notice Board' &&
    right?.source !== 'GNDEC ERP Notice Board' &&
    normalizeTitle(left?.title) === normalizeTitle(right?.title) &&
    canonicalUrl(left?.url, GNDEC_HOME_URL) === canonicalUrl(right?.url, GNDEC_HOME_URL)
  );
}

export function attachHomepageSeenWindow(notice, previousNotices, fetchedAt) {
  if (notice.source === 'GNDEC ERP Notice Board') return notice;
  const previous = (previousNotices || []).find((candidate) => noticeMatches(candidate, notice));
  const firstSeenAt = previous?.firstSeenAt || (previous?.publishedDate ? `${previous.publishedDate}T12:00:00.000Z` : fetchedAt);
  const bannerStartDate = previous?.bannerStartDate || localDateForInstant(firstSeenAt) || localDateForInstant(fetchedAt);
  const bannerUntilDate = previous?.bannerUntilDate || addCalendarDays(bannerStartDate, 1);
  return {
    ...notice,
    firstSeenAt,
    bannerStartDate,
    bannerUntilDate,
    publishedDate: bannerStartDate,
    displayDate: displayDate(bannerStartDate),
  };
}

export function parseErpNotices(html, fetchedDate = new Date().toISOString().slice(0, 10)) {
  const $ = cheerio.load(String(html || ''), { decodeEntities: true });
  const result = $('div.website-list div.result').first();
  const cards = result.length ? result.children().toArray() : [];
  return cards.map((card) => {
    const element = $(card);
    const anchor = element.find('a[href*="noticeboard/"]').first();
    if (!anchor.length) return null;
    const title = cleanText(anchor.text());
    if (!title) return null;
    const dateMatch = cleanText(element.text()).match(new RegExp(`(?:${MONTHS})\\s+\\d{1,2},\\s+\\d{4}`, 'i'));
    const publishedDate = dateMatch ? parseDateToken(dateMatch[0], fetchedDate) : fetchedDate;
    const author = cleanText(element.find('p').first().text()).replace(dateMatch?.[0] || '', '').trim();
    return makeNotice({
      source: 'GNDEC ERP Notice Board',
      title,
      publishedDate,
      url: anchor.attr('href'),
      author,
    });
  }).filter(Boolean);
}

function homepageSection($, heading) {
  const title = $('h2.block-title').filter((_, element) => cleanText($(element).text()).toLowerCase().includes(heading)).first();
  if (!title.length) return $('body');
  const block = title.closest('.block-wrapper');
  return block.length ? block : title.parent();
}

function homepagePanel($) {
  const title = $('h2.block-title').filter((_, element) => cleanText($(element).text()).toLowerCase().includes('admission')).first();
  if (!title.length) return $('body');
  const block = title.closest('.block-wrapper');
  return block.length ? block : title.parent();
}

function parseHomepageSectionNotices($, root, source, fetchedDate, { requireSignal = true } = {}) {
  const candidates = root.find('.content p a, p a').toArray();
  return candidates.map((anchor) => {
    const link = $(anchor);
    const title = cleanText(link.text());
    if (!title || EXCLUDED_HOME_ITEMS.some((pattern) => pattern.test(title))) return null;
    if (requireSignal && !ANNOUNCEMENT_SIGNALS.test(title) && !DATE_PATTERNS.some((pattern) => pattern.test(title))) return null;
    return makeNotice({
      source,
      title,
      publishedDate: fetchedDate,
      url: link.attr('href') || GNDEC_HOME_URL,
    });
  }).filter(Boolean);
}

export function parseHomepageNotices(html, fetchedDate = new Date().toISOString().slice(0, 10)) {
  const $ = cheerio.load(String(html || ''), { decodeEntities: true });
  const roots = [homepagePanel($)];
  $('div.marquee').each((_, element) => roots.push($(element)));
  const notices = roots.flatMap((root) => parseHomepageSectionNotices($, root, 'GNDEC homepage', fetchedDate));
  const studentCorner = homepageSection($, 'student corner');
  const studentNotices = parseHomepageSectionNotices($, studentCorner, 'GNDEC Student Corner', fetchedDate, { requireSignal: false })
    .filter((notice) => /notice|scholarship|fee\s+notice|original documents|document(?:s)? submission/i.test(notice.title))
    .map((notice) => ({ ...notice, source: 'GNDEC Student Corner', id: stableId('GNDEC Student Corner', notice.title, '', notice.url) }));
  return [...notices, ...studentNotices]
    .filter((notice, index, list) => list.findIndex((candidate) => noticeMatches(candidate, notice)) === index);
}

export function mergeNoticeFeeds(erpNotices, homepageNotices) {
  const seen = new Set();
  const merged = [...(erpNotices || []), ...(homepageNotices || [])]
    .filter((notice) => notice?.title)
    .sort((left, right) => {
      const dateOrder = String(right.publishedDate || '').localeCompare(String(left.publishedDate || ''));
      return dateOrder || (left.source === 'GNDEC ERP Notice Board' ? -1 : 1);
    })
    .filter((notice) => {
      const key = `${normalizeTitle(notice.title)}|${notice.publishedDate || ''}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  return merged.slice(0, MAX_NOTICES);
}

async function fetchHtml(url) {
  const response = await fetch(url, {
    headers: { 'User-Agent': 'GNDEC-Timetable-Notice-Service/1.0', Accept: 'text/html,application/xhtml+xml' },
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) throw new Error(`${url} returned HTTP ${response.status}`);
  return response.text();
}

async function readCache() {
  try {
    const cached = await getNoticeFeedCache(CACHE_ID);
    if (cached) {
      memoryCache = cached;
      return cached;
    }
  } catch (error) {
    console.warn('[NOTICE] persistent cache read unavailable:', error.message);
  }
  return memoryCache;
}

async function writeCache(notices, fetchedAt) {
  const value = { id: CACHE_ID, notices, fetchedAt };
  memoryCache = value;
  try {
    await saveNoticeFeedCache(value);
  } catch (error) {
    console.warn('[NOTICE] persistent cache write unavailable:', error.message);
  }
  return value;
}

async function refreshNoticeFeedOnce() {
  const fetchedAt = new Date().toISOString();
  const fetchedDate = fetchedAt.slice(0, 10);
  const [erpResult, homepageResult] = await Promise.allSettled([fetchHtml(ERP_NOTICE_URL), fetchHtml(GNDEC_HOME_URL)]);
  const erpNotices = erpResult.status === 'fulfilled' ? parseErpNotices(erpResult.value, fetchedDate) : [];
  const homepageNotices = homepageResult.status === 'fulfilled' ? parseHomepageNotices(homepageResult.value, fetchedDate) : [];
  const rawNotices = mergeNoticeFeeds(erpNotices, homepageNotices);
  const previousNotices = (await readCache())?.notices || [];
  const notices = rawNotices.map((notice) => attachHomepageSeenWindow(notice, previousNotices, fetchedAt));
  if (!notices.length) {
    const reasons = [erpResult, homepageResult].filter((result) => result.status === 'rejected').map((result) => result.reason?.message).filter(Boolean);
    throw new Error(`No official notices found${reasons.length ? ` (${reasons.join('; ')})` : ''}`);
  }
  return writeCache(notices, fetchedAt);
}

export async function refreshNoticeFeed() {
  if (!refreshPromise) {
    refreshPromise = refreshNoticeFeedOnce().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

export async function getNoticeFeed({ force = false } = {}) {
  const cached = await readCache();
  const ttlMillis = config.noticeCacheTtlMinutes * 60_000;
  const cacheAge = cached?.fetchedAt ? Date.now() - Date.parse(cached.fetchedAt) : Number.POSITIVE_INFINITY;
  if (!force && cached?.notices?.length && Number.isFinite(cacheAge) && cacheAge < ttlMillis) {
    return { notices: cached.notices, fetchedAt: cached.fetchedAt, servedFromCache: true, stale: false };
  }
  try {
    const refreshed = await refreshNoticeFeed();
    return { notices: refreshed.notices, fetchedAt: refreshed.fetchedAt, servedFromCache: false, stale: false };
  } catch (error) {
    if (cached?.notices?.length) {
      return { notices: cached.notices, fetchedAt: cached.fetchedAt, servedFromCache: true, stale: true, refreshError: error.message };
    }
    throw error;
  }
}
