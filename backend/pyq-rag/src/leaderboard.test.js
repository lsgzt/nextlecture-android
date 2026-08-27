import test from 'node:test';
import assert from 'node:assert/strict';
import {
  authorizedScopeValue,
  isLeaderboardEligible,
  leaderboardQuery,
  rankLeaderboard,
  summarizeLeaderboardRecords,
} from './leaderboard.js';

const marked = (date, status, updatedAt = `${date}T10:00:00.000Z`) => ({
  attendance_date: date,
  status,
  created_at: updatedAt,
  updated_at: updatedAt,
});

test('leaderboard query defaults to subsection scope', () => {
  assert.deepEqual(leaderboardQuery({}), { scope: 'subsection', value: '' });
  assert.deepEqual(leaderboardQuery({ scope: 'all' }), { scope: 'all', value: '' });
});

test('leaderboard query rejects unknown scopes', () => {
  assert.throws(() => leaderboardQuery({ scope: 'student' }));
  assert.throws(() => leaderboardQuery({ scope: 'branch', unexpected: 'value' }));
});

test('non-all scope values cannot scrape another group', () => {
  const student = { subsection: 'ITB2', section: 'IT', branch: 'Information Technology' };
  assert.equal(authorizedScopeValue(student, { scope: 'subsection', value: 'ITB1' }), 'ITB2');
  assert.equal(authorizedScopeValue(student, { scope: 'section', value: 'CSE' }), 'IT');
  assert.equal(authorizedScopeValue(student, { scope: 'branch', value: 'CSE' }), 'Information Technology');
  assert.equal(authorizedScopeValue(student, { scope: 'all', value: 'secret' }), '');
});

test('only same-day self-reported records are eligible', () => {
  assert.equal(isLeaderboardEligible(marked('2026-08-27', 'present'), '2026-08-27'), true);
  assert.equal(isLeaderboardEligible(marked('2026-08-26', 'present', '2026-08-27T10:00:00.000Z'), '2026-08-27'), false);
  assert.equal(isLeaderboardEligible(marked('2026-08-28', 'present', '2026-08-28T10:00:00.000Z'), '2026-08-27'), false);
  assert.equal(isLeaderboardEligible(marked('2026-08-27', 'present', '2026-08-26T17:00:00.000Z'), '2026-08-27'), false);
});

test('streak counts consecutive all-present marked days and skips unmarked weekends', () => {
  const records = [
    marked('2026-08-24', 'present'),
    marked('2026-08-25', 'present'),
    marked('2026-08-26', 'present'),
    marked('2026-08-27', 'present'),
  ];
  assert.equal(summarizeLeaderboardRecords(records, '2026-08-30').currentStreak, 4);
  assert.equal(summarizeLeaderboardRecords([...records, marked('2026-08-26', 'absent')], '2026-08-27').currentStreak, 1);
  assert.equal(summarizeLeaderboardRecords([marked('2026-08-26', 'present')], '2026-08-27').currentStreak, 1);
});

test('historical corrections remain private and cannot start a public streak', () => {
  const records = [marked('2026-08-26', 'present', '2026-08-27T10:00:00.000Z')];
  const summary = summarizeLeaderboardRecords(records, '2026-08-27');
  assert.equal(summary.markedTotal, 0);
  assert.equal(summary.currentStreak, 0);
});

test('ranking sorts by percentage, streak, marked count, then name and hides empty rows', () => {
  const rows = rankLeaderboard([
    { studentId: 'c', displayName: 'Zara', stats: { percentage: 100, currentStreak: 1, markedTotal: 2, present: 2, absent: 0, lastMarkedAt: null } },
    { studentId: 'a', displayName: 'Aman', stats: { percentage: 100, currentStreak: 3, markedTotal: 3, present: 3, absent: 0, lastMarkedAt: null } },
    { studentId: 'b', displayName: 'Bhavya', stats: { percentage: 80, currentStreak: 9, markedTotal: 10, present: 8, absent: 2, lastMarkedAt: null } },
    { studentId: 'd', displayName: 'No marks', stats: { percentage: null, currentStreak: 0, markedTotal: 0, present: 0, absent: 0, lastMarkedAt: null } },
    { studentId: 'e', displayName: '   ', stats: { percentage: 100, currentStreak: 5, markedTotal: 5, present: 5, absent: 0, lastMarkedAt: null } },
  ]);
  assert.deepEqual(rows.map((row) => [row.rank, row.name]), [[1, 'Aman'], [2, 'Zara'], [3, 'Bhavya']]);
  assert.equal(rows[0].studentId, 'a');
});
