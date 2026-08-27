import crypto from 'node:crypto';
import { config } from './config.js';
import { getHolidayFeedCache, saveHolidayFeedCache } from './db.js';
import { downloadKnownPdf, inspectPdf } from './pdf.js';

export const HOLIDAY_PAGE_URL = 'https://gndec.ac.in/?q=holidays';
export const HOLIDAY_PDF_URL = 'https://gndec.ac.in/sites/default/files/LoH26.pdf';

const CACHE_ID = 'global';
const MONTHS = 'January|February|March|April|May|June|July|August|September|October|November|December';
const DAY_NAMES = 'Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday';
const ROW_PATTERN = new RegExp(`^\\s*\\d+\\s+(.+?)\\s+(\\d{1,2})\\s+(${MONTHS})(?:\\s*\\(([^)]+)\\))?(?:\\s+(${DAY_NAMES}))?\\s*$`, 'i');
const DATE_PATTERN = new RegExp(`\\b(\\d{1,2})\\s+(${MONTHS})\\b`, 'i');

let memoryCache = null;
let refreshPromise = null;

function cleanText(value) {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function repairPdfText(value) {
  return cleanText(value)
    .replace(/\bBir\s+hday\b/gi, 'Birthday')
    .replace(/\bJayan\s+i\b/gi, 'Jayanti')
    .replace(/\bShivra\s+ri\b/gi, 'Shivratri')
    .replace(/\bFi\s+er\b/gi, 'Fiter')
    .replace(/\bMar\s+yrdom\b/gi, 'Martyrdom')
    .replace(/\bU\s+sav\b/gi, 'Utsav')
    .replace(/\bAsh\s+ami\b/gi, 'Ashtami')
    .replace(/\bMaha\s+ma\b/gi, 'Mahatma')
    .replace(/\bAggarsain\s+Jayan\s+i\b/gi, 'Aggarsain Jayanti')
    .replace(/\bOc\s+ober\b/gi, 'October')
    .replace(/\bSep\s+ember\b/gi, 'September')
    .replace(/\bNov\s+ember\b/gi, 'November')
    .replace(/\bDec\s+ember\b/gi, 'December')
    .replace(/\bSa\s+urday\b/gi, 'Saturday')
    .replace(/\bFa\s+ehgarh\b/gi, 'Fatehgarh')
    .replace(/\bGran\s+h\b/gi, 'Granth')
    .replace(/\bChris\s+mas\b/gi, 'Christmas')
    .replace(/\bKir\s+an\b/gi, 'Kirtan')
    .replace(/\bSarabha\s+Ji\b/gi, 'Sarabha Ji')
    .replace(/\bKar\s+ar\b/gi, 'Kartar')
    .replace(/\bres\s+ric\s+ed\b/gi, 'restricted')
    .replace(/\bwo\s+res\b/gi, 'two restricted')
    .replace(/\bGurparab\b/gi, 'Gurparab')
    .replace(/\bIn\s+respec\b/gi, 'In respect');
}

function yearFromText(text, fallbackYear = new Date().getUTCFullYear()) {
  const years = [...String(text || '').matchAll(/\b(20\d{2})\b/g)].map((match) => Number(match[1]));
  return years.find((year) => year >= 2020 && year <= 2100) || fallbackYear;
}

function isoDate(day, month, year) {
  const date = new Date(`${month} ${day}, ${year} 00:00:00Z`);
  return Number.isNaN(date.getTime()) ? '' : date.toISOString().slice(0, 10);
}

function stableId(date, name, category) {
  return `gndec-holiday-${crypto.createHash('sha256').update(`${date}|${name}|${category}`).digest('hex').slice(0, 20)}`;
}

function makeHoliday({ name, day, month, weekday, category, year }) {
  const cleanName = repairPdfText(name);
  const date = isoDate(day, month, year);
  if (!cleanName || !date) return null;
  const computedWeekday = new Intl.DateTimeFormat('en-US', { weekday: 'long', timeZone: 'UTC' }).format(new Date(`${date}T00:00:00Z`));
  return {
    id: stableId(date, cleanName, category),
    name: cleanName,
    date,
    displayDate: new Intl.DateTimeFormat('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${date}T00:00:00Z`)),
    weekday: repairPdfText(weekday) || computedWeekday,
    category,
    year,
    source: HOLIDAY_PAGE_URL,
  };
}

export function parseHolidayPdfText(text, fallbackYear = new Date().getUTCFullYear()) {
  const rawLines = String(text || '').split(/\r?\n/).map(repairPdfText).filter(Boolean);
  const year = yearFromText(text, fallbackYear);
  const holidays = [];
  let category = 'Public holiday';
  let previous = null;
  for (const line of rawLines) {
    const lower = line.toLowerCase();
    const startsRestrictedSection = lower.includes('restricted holidays');
    const startsHalfDaySection = lower.includes('half day holidays') || lower.includes('second half-day holidays') || lower.includes('second half day holidays');
    const isSectionBoundary = startsRestrictedSection || startsHalfDaySection || lower.includes('in respect') || lower.includes('following four') || lower.includes('nagar kirtan') || lower.includes('sobha yatra');
    if (startsRestrictedSection) category = 'Restricted holiday';
    if (startsHalfDaySection || lower.includes('in respect')) category = 'Half-day holiday';
    if (isSectionBoundary || /^sr\.no\.|^name of holiday/i.test(line)) previous = null;
    const match = line.match(ROW_PATTERN);
    if (match) {
      const holiday = makeHoliday({ name: match[1], day: match[2], month: match[3], weekday: match[5] || match[4], category, year });
      if (holiday) {
        holidays.push(holiday);
        previous = holiday;
      }
      continue;
    }
    // The PDF wraps long holiday names onto the next physical line.
    const isProseOrHeader = /^(?:sr\.no\.|the following|in respect|nagar|name of holiday)/i.test(line)
      || /(?:half[- ]day holidays|second half[- ]day holidays|following four|will be notified|calendar year|sobha yatra|in connection)/i.test(line);
    if (previous && !isProseOrHeader && !DATE_PATTERN.test(line)) {
      previous.name = repairPdfText(`${previous.name} ${line}`);
      previous.id = stableId(previous.date, previous.name, previous.category);
    }
  }
  return holidays.filter((holiday, index, list) => list.findIndex((candidate) => candidate.id === holiday.id) === index);
}

export function parseHolidayPages(pages, fallbackYear = new Date().getUTCFullYear()) {
  return parseHolidayPdfText((pages || []).map((page) => page.lines?.join('\n') || page.text || '').join('\n'), fallbackYear);
}

async function readCache() {
  try {
    const cached = await getHolidayFeedCache(CACHE_ID);
    if (cached) {
      memoryCache = cached;
      return cached;
    }
  } catch (error) {
    console.warn('[HOLIDAY] persistent cache read unavailable:', error.message);
  }
  return memoryCache;
}

async function writeCache(holidays, fetchedAt) {
  const value = { id: CACHE_ID, holidays, fetchedAt };
  memoryCache = value;
  try {
    await saveHolidayFeedCache(value);
  } catch (error) {
    console.warn('[HOLIDAY] persistent cache write unavailable:', error.message);
  }
  return value;
}

async function refreshHolidayFeedOnce() {
  const pdf = await downloadKnownPdf(HOLIDAY_PDF_URL);
  const inspected = await inspectPdf(pdf);
  const holidays = parseHolidayPages(inspected.pages, new Date().getUTCFullYear());
  if (!holidays.length) throw new Error('Official holiday PDF did not contain readable holiday rows');
  return writeCache(holidays, new Date().toISOString());
}

export async function refreshHolidayFeed() {
  if (!refreshPromise) {
    refreshPromise = refreshHolidayFeedOnce().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

export async function getHolidayFeed({ force = false } = {}) {
  const cached = await readCache();
  const ttlMillis = config.holidayCacheTtlHours * 60 * 60_000;
  const cacheAge = cached?.fetchedAt ? Date.now() - Date.parse(cached.fetchedAt) : Number.POSITIVE_INFINITY;
  if (!force && cached?.holidays?.length && Number.isFinite(cacheAge) && cacheAge < ttlMillis) {
    return { holidays: cached.holidays, fetchedAt: cached.fetchedAt, servedFromCache: true, stale: false };
  }
  try {
    const refreshed = await refreshHolidayFeed();
    return { holidays: refreshed.holidays, fetchedAt: refreshed.fetchedAt, servedFromCache: false, stale: false };
  } catch (error) {
    if (cached?.holidays?.length) return { holidays: cached.holidays, fetchedAt: cached.fetchedAt, servedFromCache: true, stale: true, refreshError: error.message };
    throw error;
  }
}
