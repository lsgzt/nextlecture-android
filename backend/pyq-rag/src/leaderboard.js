import { z } from 'zod';
import { getDb } from './db.js';

const GNDEC_TIME_ZONE = 'Asia/Kolkata';
const scopeSchema = z.enum(['subsection', 'section', 'branch', 'all']);
const valueSchema = z.string().trim().max(80).default('');
const leaderboardQuerySchema = z.object({
  scope: scopeSchema.default('subsection'),
  value: valueSchema,
}).strict();

const dateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: GNDEC_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

function localDateOf(value) {
  if (!value) return '';
  const parts = dateFormatter.formatToParts(new Date(value));
  const values = Object.fromEntries(parts.filter((part) => part.type !== 'literal').map((part) => [part.type, part.value]));
  return values.year && values.month && values.day ? `${values.year}-${values.month}-${values.day}` : '';
}

function todayInGndec() {
  return localDateOf(new Date());
}

function dateDaysAgo(days, today = todayInGndec()) {
  const date = new Date(`${today}T00:00:00.000Z`);
  date.setUTCDate(date.getUTCDate() - days);
  return date.toISOString().slice(0, 10);
}

function normalizeName(value) {
  return String(value || '').trim().replace(/\s+/g, ' ');
}

export function leaderboardQuery(input) {
  return leaderboardQuerySchema.parse(input || {});
}

/**
 * A record counts toward the public board only when it was entered or changed
 * on the same GNDEC calendar day as the lecture. Historical correction remains
 * available in private attendance history but cannot retroactively inflate rank.
 */
export function isLeaderboardEligible(record, today = todayInGndec()) {
  if (!record || !record.attendance_date || record.attendance_date > today) return false;
  const changedAt = record.updated_at || record.created_at;
  return Boolean(changedAt) && localDateOf(changedAt) === record.attendance_date;
}

export function summarizeLeaderboardRecords(records, today = todayInGndec()) {
  const eligible = (records || []).filter((record) => isLeaderboardEligible(record, today));
  const present = eligible.filter((record) => record.status === 'present').length;
  const absent = eligible.filter((record) => record.status === 'absent').length;
  const markedTotal = present + absent;
  const percentage = markedTotal ? (present / markedTotal) * 100 : null;
  const days = new Map();
  for (const record of eligible) {
    const day = days.get(record.attendance_date) || { present: 0, absent: 0 };
    if (record.status === 'present') day.present += 1;
    else if (record.status === 'absent') day.absent += 1;
    days.set(record.attendance_date, day);
  }

  const isWeekend = (date) => {
    const weekday = new Date(`${date}T00:00:00.000Z`).getUTCDay();
    return weekday === 0 || weekday === 6;
  };
  let currentStreak = 0;
  let cursor = [...days.keys()].sort().at(-1) || today;
  while (cursor >= dateDaysAgo(365, today)) {
    if (isWeekend(cursor) && !days.has(cursor)) {
      cursor = dateDaysAgo(1, cursor);
      continue;
    }
    const day = days.get(cursor);
    if (!day || day.absent > 0 || day.present === 0) break;
    currentStreak += 1;
    cursor = dateDaysAgo(1, cursor);
  }

  const lastMarkedAt = eligible
    .map((record) => record.updated_at || record.created_at)
    .filter(Boolean)
    .sort()
    .at(-1) || null;
  return { present, absent, markedTotal, percentage, currentStreak, lastMarkedAt };
}

export function rankLeaderboard(rows) {
  return [...(rows || [])]
    .map((row) => ({ ...row, displayName: normalizeName(row.displayName) }))
    .filter((row) => row.displayName && row.stats.markedTotal > 0)
    .sort((left, right) => {
      const percentage = (right.stats.percentage ?? -1) - (left.stats.percentage ?? -1);
      if (Math.abs(percentage) > 1e-9) return percentage;
      const streak = right.stats.currentStreak - left.stats.currentStreak;
      if (streak !== 0) return streak;
      const marked = right.stats.markedTotal - left.stats.markedTotal;
      if (marked !== 0) return marked;
      return left.displayName.localeCompare(right.displayName, undefined, { sensitivity: 'base' });
    })
    .map((row, index) => ({
      rank: index + 1,
      name: row.displayName,
      percentage: Number(row.stats.percentage.toFixed(1)),
      present: row.stats.present,
      absent: row.stats.absent,
      markedTotal: row.stats.markedTotal,
      currentStreak: row.stats.currentStreak,
      selfReported: true,
      lastMarkedAt: row.stats.lastMarkedAt,
      studentId: row.studentId,
    }));
}

function scopeColumn(scope) {
  if (scope === 'subsection') return 'subsection';
  if (scope === 'section') return 'section';
  if (scope === 'branch') return 'branch';
  return null;
}

function scopeLabel(scope, value) {
  if (scope === 'all') return 'All branches';
  const title = scope[0].toUpperCase() + scope.slice(1);
  return value ? `${title}: ${value}` : title;
}

export function authorizedScopeValue(student, parsed) {
  if (parsed.scope === 'all') return '';
  return {
    subsection: student?.subsection,
    section: student?.section,
    branch: student?.branch,
  }[parsed.scope] || '';
}

const eligibilityMessage = 'Self-reported attendance. Only marks entered on the same GNDEC day count. A streak day requires every lecture you marked that day to be present; unmarked timetable lectures cannot be independently verified by this server.';

export async function getLeaderboard(student, input) {
  const parsed = leaderboardQuery(input);
  const value = authorizedScopeValue(student, parsed);
  if (parsed.scope !== 'all' && !value) {
    return {
      scope: parsed.scope,
      scopeValue: '',
      scopeLabel: scopeLabel(parsed.scope, ''),
      participants: 0,
      rows: [],
      me: null,
      eligibility: eligibilityMessage,
    };
  }

  const db = getDb();
  let studentQuery = db
    .from('attendance_students')
    .select('id,display_name,branch,section,subsection,timetable_group')
    .limit(5000);
  const column = scopeColumn(parsed.scope);
  if (column) studentQuery = studentQuery.eq(column, value);
  const studentResult = await studentQuery;
  if (studentResult.error) throw new Error(`leaderboard students read failed: ${studentResult.error.message}`);
  const students = (studentResult.data || []).filter((candidate) => normalizeName(candidate.display_name));
  if (!students.length) {
    return {
      scope: parsed.scope,
      scopeValue: value,
      scopeLabel: scopeLabel(parsed.scope, value),
      participants: 0,
      rows: [],
      me: null,
      eligibility: eligibilityMessage,
    };
  }

  const studentIds = students.map((candidate) => candidate.id);
  const recordResult = await db
    .from('attendance_records')
    .select('student_id,attendance_date,lecture_key,status,created_at,updated_at')
    .in('student_id', studentIds)
    .gte('attendance_date', dateDaysAgo(365))
    .lte('attendance_date', todayInGndec())
    .order('attendance_date', { ascending: true })
    .limit(50000);
  if (recordResult.error) throw new Error(`leaderboard records read failed: ${recordResult.error.message}`);

  const byStudent = new Map();
  for (const record of recordResult.data || []) {
    const records = byStudent.get(record.student_id) || [];
    records.push(record);
    byStudent.set(record.student_id, records);
  }
  const today = todayInGndec();
  const computed = students.map((candidate) => ({
    studentId: candidate.id,
    displayName: normalizeName(candidate.display_name),
    stats: summarizeLeaderboardRecords(byStudent.get(candidate.id) || [], today),
  }));
  const ranked = rankLeaderboard(computed).slice(0, 100);
  const me = ranked.find((row) => row.studentId === student.id) || null;
  return {
    scope: parsed.scope,
    scopeValue: value,
    scopeLabel: scopeLabel(parsed.scope, value),
    participants: ranked.length,
    rows: ranked.map(({ studentId, ...row }) => row),
    me: me ? { ...me, studentId: undefined } : null,
    eligibility: eligibilityMessage,
  };
}

export { todayInGndec };
