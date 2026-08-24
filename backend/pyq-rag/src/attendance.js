import crypto from 'node:crypto';
import { z } from 'zod';
import { getDb } from './db.js';

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/).refine((value) => {
  const parsed = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}, 'invalid calendar date');

const text = (max) => z.string().trim().max(max).default('');

export const attendanceSessionSchema = z.object({
  installationId: z.string().trim().min(16).max(200),
  profileFingerprint: z.string().trim().min(16).max(128),
  branch: text(80),
  subsection: text(40),
  timetableGroup: text(40),
}).strict();

export const attendanceRecordSchema = z.object({
  attendanceDate: isoDate,
  lectureKey: z.string().trim().min(16).max(128),
  status: z.enum(['present', 'absent']),
  subject: text(240),
  teacher: text(240),
  venue: text(240),
  startMinutes: z.number().int().min(0).max(1439),
  endMinutes: z.number().int().min(1).max(1440),
}).strict().refine((value) => value.endMinutes > value.startMinutes, {
  path: ['endMinutes'],
  message: 'endMinutes must be after startMinutes',
});

const attendanceQuerySchema = z.object({
  from: isoDate.optional(),
  to: isoDate.optional(),
  target: z.coerce.number().min(0).max(100).default(75),
}).refine((value) => !value.from || !value.to || value.from <= value.to, {
  path: ['range'],
  message: 'from must not be after to',
});

const hash = (value) => crypto.createHash('sha256').update(String(value)).digest('hex');
const token = () => crypto.randomBytes(32).toString('base64url');

function todayUtc() {
  return new Date().toISOString().slice(0, 10);
}

function maxAllowedDate() {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

function dateDaysAgo(days) {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() - days);
  return date.toISOString().slice(0, 10);
}

function summarize(records, target) {
  const present = records.filter((record) => record.status === 'present').length;
  const absent = records.filter((record) => record.status === 'absent').length;
  const markedTotal = present + absent;
  const percentage = markedTotal ? (present / markedTotal) * 100 : null;
  const targetRatio = target / 100;
  let affordableMisses = null;
  let lecturesToAttend = null;
  if (targetRatio > 0 && targetRatio < 1) {
    affordableMisses = Math.max(0, Math.floor((present / targetRatio) - markedTotal + 1e-9));
    if (markedTotal && percentage < target) {
      lecturesToAttend = Math.ceil(((targetRatio * markedTotal) - present) / (1 - targetRatio));
    }
  } else if (targetRatio === 1) {
    affordableMisses = present === markedTotal ? 0 : 0;
  } else {
    affordableMisses = 0;
  }
  return {
    present,
    absent,
    markedTotal,
    percentage,
    target,
    affordableMisses,
    lecturesToAttend,
  };
}

function bearer(req) {
  const value = req.get('authorization') || '';
  return value.startsWith('Bearer ') ? value.slice(7).trim() : '';
}

export async function authenticateAttendance(req) {
  const raw = bearer(req);
  if (raw.length < 32 || raw.length > 200) return null;
  const accessTokenHash = hash(raw);
  const { data, error } = await getDb()
    .from('attendance_students')
    .select('id,branch,subsection,timetable_group')
    .eq('access_token_hash', accessTokenHash)
    .maybeSingle();
  if (error) throw new Error(`attendance session lookup failed: ${error.message}`);
  if (!data) return null;
  await getDb().from('attendance_students').update({ last_seen_at: new Date().toISOString() }).eq('id', data.id);
  return data;
}

export async function createAttendanceSession(input) {
  const parsed = attendanceSessionSchema.parse(input);
  const installationHash = hash(parsed.installationId);
  const accessToken = token();
  const row = {
    installation_hash: installationHash,
    access_token_hash: hash(accessToken),
    profile_fingerprint: parsed.profileFingerprint,
    branch: parsed.branch,
    subsection: parsed.subsection,
    timetable_group: parsed.timetableGroup,
    last_seen_at: new Date().toISOString(),
  };
  const db = getDb();
  const { data: installationExisting, error: installationError } = await db
    .from('attendance_students')
    .select('id')
    .eq('installation_hash', installationHash)
    .maybeSingle();
  if (installationError) throw new Error(`attendance installation lookup failed: ${installationError.message}`);

  // A reinstall receives a new installation ID. Reclaim the same server-side owner
  // through the saved student profile fingerprint so its attendance records survive.
  const profileLookup = installationExisting ? null : await db
    .from('attendance_students')
    .select('id,last_seen_at')
    .eq('profile_fingerprint', parsed.profileFingerprint)
    .order('last_seen_at', { ascending: false })
    .limit(50);
  if (profileLookup?.error) throw new Error(`attendance profile lookup failed: ${profileLookup.error.message}`);

  let existing = installationExisting || null;
  if (!existing && profileLookup?.data?.length) {
    const candidateIds = profileLookup.data.map((candidate) => candidate.id);
    const recordLookup = await db
      .from('attendance_records')
      .select('student_id')
      .in('student_id', candidateIds)
      .limit(5000);
    if (recordLookup.error) throw new Error(`attendance profile records lookup failed: ${recordLookup.error.message}`);
    existing = chooseAttendanceOwner(profileLookup.data, recordLookup.data || []);
  }

  const result = existing
    ? await db.from('attendance_students').update(row).eq('id', existing.id).select('id').single()
    : await db.from('attendance_students').insert(row).select('id').single();
  if (result.error) throw new Error(`attendance session write failed: ${result.error.message}`);
  return { studentId: result.data.id, accessToken, issuedAt: new Date().toISOString() };
}

export async function listAttendance(studentId, query) {
  const parsed = attendanceQuerySchema.parse(query || {});
  const from = parsed.from || dateDaysAgo(365);
  const to = parsed.to || todayUtc();
  if (from > to) throw new Error('attendance date range is invalid');
  const { data, error } = await getDb()
    .from('attendance_records')
    .select('attendance_date,lecture_key,status,subject,teacher,venue,start_minutes,end_minutes,created_at,updated_at')
    .eq('student_id', studentId)
    .gte('attendance_date', from)
    .lte('attendance_date', to)
    .order('attendance_date', { ascending: true })
    .order('start_minutes', { ascending: true })
    .limit(1000);
  if (error) throw new Error(`attendance records read failed: ${error.message}`);
  const records = data || [];
  return { from, to, records, summary: summarize(records, parsed.target) };
}

export async function upsertAttendance(studentId, input) {
  const parsed = attendanceRecordSchema.parse(input);
  if (parsed.attendanceDate > maxAllowedDate()) throw new Error('future attendance cannot be marked');
  const payload = {
    student_id: studentId,
    attendance_date: parsed.attendanceDate,
    lecture_key: parsed.lectureKey,
    status: parsed.status,
    subject: parsed.subject,
    teacher: parsed.teacher,
    venue: parsed.venue,
    start_minutes: parsed.startMinutes,
    end_minutes: parsed.endMinutes,
  };
  const { data, error } = await getDb()
    .from('attendance_records')
    .upsert(payload, { onConflict: 'student_id,attendance_date,lecture_key' })
    .select('attendance_date,lecture_key,status,subject,teacher,venue,start_minutes,end_minutes,created_at,updated_at')
    .single();
  if (error) throw new Error(`attendance record write failed: ${error.message}`);
  return data;
}

export async function removeAttendance(studentId, attendanceDate, lectureKey) {
  const parsedDate = isoDate.parse(attendanceDate);
  const parsedKey = z.string().trim().min(16).max(128).parse(lectureKey);
  const { error } = await getDb()
    .from('attendance_records')
    .delete()
    .eq('student_id', studentId)
    .eq('attendance_date', parsedDate)
    .eq('lecture_key', parsedKey);
  if (error) throw new Error(`attendance record delete failed: ${error.message}`);
}

export function chooseAttendanceOwner(candidates, records) {
  const counts = new Map();
  for (const record of records || []) counts.set(record.student_id, (counts.get(record.student_id) || 0) + 1);
  return [...(candidates || [])].sort((left, right) => {
    const countDifference = (counts.get(right.id) || 0) - (counts.get(left.id) || 0);
    if (countDifference !== 0) return countDifference;
    return new Date(right.last_seen_at || 0).getTime() - new Date(left.last_seen_at || 0).getTime();
  })[0] || null;
}

export { summarize };
