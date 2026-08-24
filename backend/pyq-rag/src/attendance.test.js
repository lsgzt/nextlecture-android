import test from 'node:test';
import assert from 'node:assert/strict';
import { attendanceRecordSchema, chooseAttendanceOwner, scopedInstallationHash, summarize } from './attendance.js';

test('attendance summary excludes unmarked lectures and calculates affordable misses', () => {
  const records = [
    { status: 'present' },
    { status: 'present' },
    { status: 'absent' },
  ];
  const summary = summarize(records, 75);
  assert.equal(summary.present, 2);
  assert.equal(summary.absent, 1);
  assert.equal(summary.markedTotal, 3);
  assert.equal(summary.percentage, 66.66666666666666);
  assert.equal(summary.affordableMisses, 0);
  assert.equal(summary.lecturesToAttend, 1);
});

test('attendance summary reports one affordable absence at exactly 80 percent', () => {
  const summary = summarize([{ status: 'present' }, { status: 'present' }, { status: 'present' }, { status: 'present' }], 75);
  assert.equal(summary.percentage, 100);
  assert.equal(summary.affordableMisses, 1);
  assert.equal(summary.lecturesToAttend, null);
});

test('installation identity is scoped to the active profile', () => {
  assert.notEqual(scopedInstallationHash('same-installation', 'a'.repeat(64)), scopedInstallationHash('same-installation', 'b'.repeat(64)));
  assert.equal(scopedInstallationHash('same-installation', 'a'.repeat(64)), scopedInstallationHash('same-installation', 'a'.repeat(64)));
});

test('profile recovery chooses the candidate that already owns attendance records', () => {
  const selected = chooseAttendanceOwner(
    [
      { id: 'empty', last_seen_at: '2026-08-24T10:00:00.000Z' },
      { id: 'marked', last_seen_at: '2026-08-23T10:00:00.000Z' },
    ],
    [{ student_id: 'marked' }]
  );
  assert.equal(selected.id, 'marked');
});

test('attendance record schema rejects future-shaped invalid dates and reversed times', () => {
  assert.throws(() => attendanceRecordSchema.parse({
    attendanceDate: '2026-02-31',
    lectureKey: 'a'.repeat(64),
    status: 'present',
    subject: 'Maths',
    teacher: '',
    venue: '',
    startMinutes: 500,
    endMinutes: 400,
  }));
});
