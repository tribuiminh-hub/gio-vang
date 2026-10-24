import assert from 'node:assert/strict';
import { evaluate, applyPlay, approveExtra, localParts, DEFAULT_CONFIG } from '../web/policy.js';

const tz = 'Asia/Ho_Chi_Minh';
// Thứ Bảy 24/10/2026, giờ VN = UTC+7
const at = (hm, date = '2026-10-24') => Date.parse(`${date}T${hm}:00+07:00`);
let n = 0;
const t = (name, fn) => { fn(); n++; console.log('ok -', name); };

t('ngày và cuối tuần theo múi giờ VN', () => {
  const p = localParts(at('23:30'), tz);
  assert.equal(p.dateKey, '2026-10-24');
  assert.equal(p.weekend, true);
  assert.equal(localParts(at('09:00', '2026-10-26'), tz).weekend, false);
});

t('trong giờ, còn quỹ: không khóa', () => {
  const s = evaluate({ nowMs: at('10:00'), tz, config: DEFAULT_CONFIG, day: { playedSec: 600 } });
  assert.equal(s.blocked, false);
  assert.equal(s.playLeftSec, 180 * 60 - 600);
});

t('trước giờ mở máy: khóa vì giờ', () => {
  const s = evaluate({ nowMs: at('06:30'), tz, config: DEFAULT_CONFIG, day: {} });
  assert.equal(s.reason, 'hours');
});

t('sau giờ tắt máy cuối tuần 21:00: khóa', () => {
  assert.equal(evaluate({ nowMs: at('21:00'), tz, config: DEFAULT_CONFIG, day: {} }).reason, 'hours');
  assert.equal(evaluate({ nowMs: at('20:59'), tz, config: DEFAULT_CONFIG, day: {} }).reason, 'none');
});

t('giờ tắt máy ngày thường 20:30', () => {
  assert.equal(evaluate({ nowMs: at('20:40', '2026-10-26'), tz, config: DEFAULT_CONFIG, day: {} }).reason, 'hours');
});

t('hết quỹ giờ: khóa, thêm giờ thì mở', () => {
  const day = { playedSec: 180 * 60 };
  assert.equal(evaluate({ nowMs: at('15:00'), tz, config: DEFAULT_CONFIG, day }).reason, 'quota');
  const s = evaluate({ nowMs: at('15:00'), tz, config: DEFAULT_CONFIG, day: { ...day, bonusMin: 30 } });
  assert.equal(s.blocked, false);
  assert.equal(s.playLeftSec, 1800);
});

t('thời gian chưa đồng bộ cũng được tính', () => {
  const s = evaluate({ nowMs: at('15:00'), tz, config: DEFAULT_CONFIG, day: { playedSec: 179 * 60 }, pendingSec: 60 });
  assert.equal(s.reason, 'quota');
});

t('lượt chơi 20 phút rồi nghỉ 10 phút', () => {
  let day = {};
  const t0 = at('10:00');
  for (let i = 1; i <= 19; i++) day = applyPlay(day, 60, t0 + i * 60000, DEFAULT_CONFIG, 'a');
  assert.equal(evaluate({ nowMs: t0 + 19 * 60000, tz, config: DEFAULT_CONFIG, day }).blocked, false);
  day = applyPlay(day, 60, t0 + 20 * 60000, DEFAULT_CONFIG, 'a');
  assert.equal(day.sessionSec, 0);
  assert.equal(day.breakUntil, t0 + 30 * 60000);
  const s = evaluate({ nowMs: t0 + 25 * 60000, tz, config: DEFAULT_CONFIG, day });
  assert.equal(s.reason, 'break');
  assert.equal(s.breakLeftSec, 300);
  assert.equal(evaluate({ nowMs: t0 + 30 * 60000, tz, config: DEFAULT_CONFIG, day }).blocked, false);
});

t('nghỉ đủ lâu thì lượt chơi tính lại từ đầu', () => {
  let day = applyPlay({}, 15 * 60, at('10:00'), DEFAULT_CONFIG, 'a');
  assert.equal(day.sessionSec, 900);
  day = applyPlay(day, 60, at('10:12'), DEFAULT_CONFIG, 'a');
  assert.equal(day.sessionSec, 60);
});

t('lượt chơi dùng chung giữa các thiết bị', () => {
  let day = applyPlay({}, 10 * 60, at('10:00'), DEFAULT_CONFIG, 'tablet');
  day = applyPlay(day, 10 * 60, at('10:03'), DEFAULT_CONFIG, 'phone');
  assert.equal(day.breakUntil, at('10:13'));
  assert.equal(day.perDevice.tablet, 600);
  assert.equal(day.perDevice.phone, 600);
  assert.equal(day.playedSec, 1200);
});

t('tắt giới hạn lượt chơi', () => {
  const cfg = { ...DEFAULT_CONFIG, session: { enabled: false, maxMin: 20, breakMin: 10 } };
  const day = applyPlay({}, 40 * 60, at('10:00'), cfg, 'a');
  assert.equal(day.breakUntil, 0);
  assert.equal(evaluate({ nowMs: at('10:01'), tz, config: cfg, day }).blocked, false);
});

t('mở khóa bằng PIN ba mẹ', () => {
  const s = evaluate({ nowMs: at('22:00'), tz, config: DEFAULT_CONFIG, day: {}, unlockUntil: at('22:15') });
  assert.equal(s.reason, 'hours');
  assert.equal(s.blocked, false);
});

t('đồng ý thêm 30 phút lúc 20:50 thì nới giờ tắt máy', () => {
  const day = { playedSec: 0 };
  const patch = approveExtra(day, 30, at('20:50'), tz, DEFAULT_CONFIG);
  assert.equal(patch.bonusMin, 30);
  assert.equal(patch.cutoffExtendMin, 20);
  const s = evaluate({ nowMs: at('21:10'), tz, config: DEFAULT_CONFIG, day: { ...day, ...patch } });
  assert.equal(s.blocked, false);
  assert.equal(s.cutoff, '21:20');
});

t('số lần xin thêm còn lại', () => {
  assert.equal(evaluate({ nowMs: at('10:00'), tz, config: DEFAULT_CONFIG, day: { requestCount: 1 } }).requestsLeft, 1);
  const off = { ...DEFAULT_CONFIG, requests: { enabled: false, maxPerDay: 2 } };
  assert.equal(evaluate({ nowMs: at('10:00'), tz, config: off, day: {} }).requestsLeft, 0);
});

console.log(`\n${n} kiểm thử đạt`);
