import test from 'node:test';
import assert from 'node:assert/strict';
import { attachHomepageSeenWindow, mergeNoticeFeeds, parseErpNotices, parseHomepageNotices } from './notices.js';

test('parses dated ERP notice cards', () => {
  const html = `
    <div class="website-list"><div class="result">
      <div><p>administrator August 25, 2026</p><a href="/noticeboard/holiday">Holiday notice</a></div>
      <div><p>coe_office August 18, 2026</p><a href="/noticeboard/exam">Exam schedule</a></div>
    </div></div>`;
  const notices = parseErpNotices(html, '2026-08-26');
  assert.equal(notices.length, 2);
  assert.equal(notices[0].publishedDate, '2026-08-25');
  assert.equal(notices[0].source, 'GNDEC ERP Notice Board');
  assert.match(notices[0].url, /noticeboard\/holiday$/);
});

test('filters mixed homepage panel and includes active announcement items', () => {
  const html = `
    <div class="block-wrapper"><h2 class="block-title">ADMISSION (2026-2027)</h2><div class="content">
      <p><a href="">There is a Notice regarding Holiday on 27.08.2026. The College remains open.</a></p>
      <p><a href="https://admission.gndec.ac.in/spot_counselling/">Spot Counselling 2026-27</a></p>
      <p><a href="https://admission.gndec.ac.in/Fee_Structure.php">Fee Structure</a></p>
    </div></div>
    <div class="marquee"><p><a href="https://gndec.ac.in/sites/default/files/notice.pdf">Notice regarding original documents submission</a></p></div>`;
  const notices = parseHomepageNotices(html, '2026-08-26');
  assert.equal(notices.length, 2);
  assert.ok(notices.some((notice) => notice.title.includes('Holiday')));
  assert.ok(notices.some((notice) => notice.title.includes('original documents')));
  assert.ok(notices.every((notice) => notice.source === 'GNDEC homepage'));
  assert.ok(notices.every((notice) => !/Spot Counselling|Fee Structure/i.test(notice.title)));
});

test('keeps homepage first-seen date stable and gives it a two-day window', () => {
  const current = { id: 'home-1', title: 'Holiday announcement', publishedDate: '2026-08-27', url: 'https://gndec.ac.in/', source: 'GNDEC homepage' };
  const previous = [{ ...current, firstSeenAt: '2026-08-26T15:00:00.000Z', bannerStartDate: '2026-08-26', bannerUntilDate: '2026-08-27' }];
  const retained = attachHomepageSeenWindow(current, previous, '2026-08-27T04:00:00.000Z');
  assert.equal(retained.firstSeenAt, '2026-08-26T15:00:00.000Z');
  assert.equal(retained.bannerStartDate, '2026-08-26');
  assert.equal(retained.bannerUntilDate, '2026-08-27');
});

test('merges ERP and homepage notices by normalized title and date', () => {
  const erp = [{ id: 'erp-1', title: 'Holiday Notice', publishedDate: '2026-08-26', source: 'GNDEC ERP Notice Board' }];
  const homepage = [{ id: 'home-1', title: '  holiday   notice ', publishedDate: '2026-08-26', source: 'GNDEC homepage' }];
  const merged = mergeNoticeFeeds(erp, homepage);
  assert.equal(merged.length, 1);
  assert.equal(merged[0].id, 'erp-1');
});
