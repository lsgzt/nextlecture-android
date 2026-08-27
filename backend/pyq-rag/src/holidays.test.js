import test from 'node:test';
import assert from 'node:assert/strict';
import { parseHolidayPdfText } from './holidays.js';

test('parses public holidays and wrapped names from the official PDF text', () => {
  const holidays = parseHolidayPdfText(`
    Calendar Year 2026
    Sr.No. Name Date Day
    1 Republic Day 26 January Monday
    6 Martyrdom Day of Shaheed-e-Azam Bhagat Singh, Sukhdev 23 March Monday
    and Rajguru Ji
  `, 2026);
  assert.equal(holidays.length, 2);
  assert.equal(holidays[0].date, '2026-01-26');
  assert.equal(holidays[1].name, 'Martyrdom Day of Shaheed-e-Azam Bhagat Singh, Sukhdev and Rajguru Ji');
  assert.equal(holidays[1].category, 'Public holiday');
});

test('parses restricted and half-day categories', () => {
  const holidays = parseHolidayPdfText(`
    Calendar Year 2026
    The following two restricted holidays will be observed :-
    Sr.No. Name of Holiday Date
    1 Parkash Gurparab Sri Guru Ram Dass Sahib Ji 27 October (Tuesday)
    In respect of half day holidays, the following four second half-day holidays will be notified
    Sr.No. Name of Holiday Date (day before holiday)
    1 Mahavir Jayanti 30 March (Monday)
  `, 2026);
  assert.equal(holidays.length, 2);
  assert.equal(holidays[0].category, 'Restricted holiday');
  assert.equal(holidays[0].weekday, 'Tuesday');
  assert.equal(holidays[1].category, 'Half-day holiday');
  assert.equal(holidays[1].date, '2026-03-30');
});
