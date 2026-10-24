// Giờ Vàng — quy tắc tính giờ. Bản này dùng cho web ba mẹ và để kiểm thử.
// App Android (Policy.kt) làm đúng theo cùng thuật toán này.

export const DEFAULT_CONFIG = {
  weekday: { quotaMin: 120, openAt: '07:00', cutoff: '20:30' },
  weekend: { quotaMin: 180, openAt: '07:00', cutoff: '21:00' },
  session: { enabled: true, maxMin: 20, breakMin: 10 },
  requests: { enabled: true, maxPerDay: 2 },
  protectSettings: true,
};

export const EMPTY_DAY = {
  playedSec: 0,
  studySec: 0,
  bonusMin: 0,
  cutoffExtendMin: 0,
  requestCount: 0,
  sessionSec: 0,
  lastPlayAt: 0,
  breakUntil: 0,
  perDevice: {},
};

// "HH:mm" -> phút trong ngày
export function hmToMin(hm) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(hm || '').trim());
  if (!m) return 0;
  return Math.min(23, Number(m[1])) * 60 + Math.min(59, Number(m[2]));
}

export function minToHm(min) {
  const v = ((Math.round(min) % 1440) + 1440) % 1440;
  return String(Math.floor(v / 60)).padStart(2, '0') + ':' + String(v % 60).padStart(2, '0');
}

// Lấy ngày giờ địa phương theo múi giờ của gia đình
export function localParts(nowMs, tz) {
  const f = new Intl.DateTimeFormat('en-CA', {
    timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', weekday: 'short', hourCycle: 'h23',
  });
  const p = {};
  for (const x of f.formatToParts(new Date(nowMs))) p[x.type] = x.value;
  const minuteOfDay = Number(p.hour) * 60 + Number(p.minute);
  return {
    dateKey: `${p.year}-${p.month}-${p.day}`,
    minuteOfDay,
    secondOfDay: minuteOfDay * 60 + Number(p.second),
    weekend: p.weekday === 'Sat' || p.weekday === 'Sun',
  };
}

export function dayRulesFor(config, weekend) {
  const c = { ...DEFAULT_CONFIG, ...(config || {}) };
  return { ...(weekend ? DEFAULT_CONFIG.weekend : DEFAULT_CONFIG.weekday), ...(weekend ? c.weekend : c.weekday) };
}

// Kết quả: có đang bị khóa không, lý do, và các số giây còn lại.
// reason: 'none' | 'hours' | 'quota' | 'break'
export function evaluate({ nowMs, tz, config, day, pendingSec = 0, unlockUntil = 0 }) {
  const cfg = { ...DEFAULT_CONFIG, ...(config || {}) };
  const d = { ...EMPTY_DAY, ...(day || {}) };
  const lp = localParts(nowMs, tz);
  const r = dayRulesFor(cfg, lp.weekend);
  const session = { ...DEFAULT_CONFIG.session, ...(cfg.session || {}) };

  const openMin = hmToMin(r.openAt);
  const cutoffMin = Math.min(1440, hmToMin(r.cutoff) + (d.cutoffExtendMin || 0));
  const inHours = lp.minuteOfDay >= openMin && lp.minuteOfDay < cutoffMin;

  const quotaSec = (Number(r.quotaMin) + Number(d.bonusMin || 0)) * 60;
  const playedSec = Number(d.playedSec || 0) + pendingSec;
  const playLeftSec = Math.max(0, quotaSec - playedSec);
  const untilCutoffSec = inHours ? cutoffMin * 60 - lp.secondOfDay : 0;

  // Lượt chơi: nghỉ đủ lâu thì lượt mới bắt đầu lại từ 0
  let sessionSec = Number(d.sessionSec || 0);
  if (d.lastPlayAt && nowMs - d.lastPlayAt >= session.breakMin * 60000) sessionSec = 0;
  sessionSec += pendingSec;
  const breakLeftSec = d.breakUntil > nowMs ? Math.ceil((d.breakUntil - nowMs) / 1000) : 0;
  const sessionLeftSec = session.enabled ? Math.max(0, session.maxMin * 60 - sessionSec) : Infinity;

  let reason = 'none';
  if (!inHours) reason = 'hours';
  else if (playLeftSec <= 0) reason = 'quota';
  else if (session.enabled && (breakLeftSec > 0 || sessionLeftSec <= 0)) reason = 'break';

  const unlocked = unlockUntil > nowMs;
  return {
    dateKey: lp.dateKey,
    weekend: lp.weekend,
    blocked: reason !== 'none' && !unlocked,
    reason,
    unlocked,
    quotaSec,
    playedSec,
    playLeftSec,
    // thời gian thật sự chơi được tiếp: nhỏ nhất của quỹ giờ, giờ tắt máy và lượt chơi
    canPlaySec: reason === 'none' ? Math.min(playLeftSec, untilCutoffSec, sessionLeftSec) : 0,
    sessionLeftSec,
    breakLeftSec: breakLeftSec > 0 ? breakLeftSec : (session.enabled && sessionLeftSec <= 0 ? session.breakMin * 60 : 0),
    openAt: r.openAt,
    cutoff: minToHm(cutoffMin),
    requestsLeft: cfg.requests?.enabled ? Math.max(0, (cfg.requests.maxPerDay ?? 0) - (d.requestCount || 0)) : 0,
  };
}

// Cộng dồn thời gian chơi vào bản ghi ngày (dùng trong transaction trên thiết bị)
export function applyPlay(day, deltaSec, nowMs, config, deviceId) {
  const cfg = { ...DEFAULT_CONFIG, ...(config || {}) };
  const s = { ...DEFAULT_CONFIG.session, ...(cfg.session || {}) };
  const d = { ...EMPTY_DAY, ...(day || {}) };
  let sess = d.lastPlayAt && nowMs - d.lastPlayAt >= s.breakMin * 60000 ? 0 : d.sessionSec;
  sess += deltaSec;
  let breakUntil = d.breakUntil || 0;
  if (s.enabled && sess >= s.maxMin * 60) {
    breakUntil = nowMs + s.breakMin * 60000;
    sess = 0;
  }
  const perDevice = { ...(d.perDevice || {}) };
  perDevice[deviceId] = (perDevice[deviceId] || 0) + deltaSec;
  return {
    ...d,
    playedSec: d.playedSec + deltaSec,
    sessionSec: sess,
    lastPlayAt: nowMs,
    breakUntil,
    perDevice,
  };
}

// Ba mẹ đồng ý thêm giờ: cộng phút và nới giờ tắt máy nếu cần
export function approveExtra(day, minutes, nowMs, tz, config) {
  const d = { ...EMPTY_DAY, ...(day || {}) };
  const lp = localParts(nowMs, tz);
  const r = dayRulesFor(config, lp.weekend);
  const needEnd = lp.minuteOfDay + minutes;
  const cutoff = hmToMin(r.cutoff) + (d.cutoffExtendMin || 0);
  const extend = needEnd > cutoff ? needEnd - hmToMin(r.cutoff) : d.cutoffExtendMin || 0;
  return {
    bonusMin: (d.bonusMin || 0) + minutes,
    cutoffExtendMin: Math.max(d.cutoffExtendMin || 0, Math.min(extend, 1440 - hmToMin(r.cutoff))),
  };
}

export function fmtDur(sec) {
  if (!isFinite(sec)) return 'không giới hạn';
  const m = Math.max(0, Math.round(sec / 60));
  const h = Math.floor(m / 60);
  const r = m % 60;
  if (h && r) return `${h} giờ ${r} phút`;
  if (h) return `${h} giờ`;
  return `${r} phút`;
}
