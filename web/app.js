// Giờ Vàng — web app cho ba mẹ
import { initializeApp } from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js';
import {
  getAuth, GoogleAuthProvider, signInWithPopup, signInWithRedirect, getRedirectResult,
  onAuthStateChanged, signOut,
} from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js';
import {
  getFirestore, doc, getDocs, setDoc, updateDoc, deleteDoc, collection, query, where,
  onSnapshot, serverTimestamp, Timestamp, runTransaction, orderBy, limit,
} from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js';
import { firebaseConfig as bakedConfig } from './config.js';
import { DEFAULT_CONFIG, evaluate, approveExtra, fmtDur, localParts, hmToMin, minToHm } from './policy.js';

const $app = document.getElementById('app');
const TZ_DEFAULT = 'Asia/Ho_Chi_Minh';
const OFFLINE_MIN = 10;

const S = {
  view: 'loading', tab: 'home', user: null, fid: null, family: null,
  config: null, day: null, dayKey: null, devices: [], requests: [], apps: [],
  pairing: null, extraMin: {}, quickExtra: 30, now: Date.now(), loaded: {},
};
let fb = null;
let unsubs = [];
let dayUnsub = null;
let seenRequests = null;
let seenApps = null;

// ---------- tiện ích ----------
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const tz = () => S.family?.tz || TZ_DEFAULT;
const childName = () => S.family?.childName || 'con';
const cfg = () => mergeConfig(S.config);

function mergeConfig(c) {
  const x = c || {};
  return {
    ...DEFAULT_CONFIG, ...x,
    weekday: { ...DEFAULT_CONFIG.weekday, ...(x.weekday || {}) },
    weekend: { ...DEFAULT_CONFIG.weekend, ...(x.weekend || {}) },
    session: { ...DEFAULT_CONFIG.session, ...(x.session || {}) },
    requests: { ...DEFAULT_CONFIG.requests, ...(x.requests || {}) },
  };
}

let toastTimer = null;
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
}

function errMsg(e) {
  const c = e?.code || '';
  if (c.includes('permission-denied')) return 'Không có quyền. Kiểm tra lại luật bảo mật Firestore.';
  if (c.includes('popup-closed')) return 'Đã đóng cửa sổ đăng nhập.';
  if (c.includes('unauthorized-domain')) return 'Tên miền này chưa được cho phép trong Firebase Authentication.';
  if (c.includes('unavailable')) return 'Mất kết nối mạng, thử lại sau.';
  return 'Có lỗi: ' + (e?.message || e);
}

function ago(ts) {
  if (!ts?.toMillis) return 'chưa kết nối';
  const m = Math.floor((S.now - ts.toMillis()) / 60000);
  if (m < 1) return 'vừa xong';
  if (m < 60) return `${m} phút trước`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} giờ trước`;
  return `${Math.floor(h / 24)} ngày trước`;
}

function hhmm(ts) {
  if (!ts?.toMillis) return '';
  return new Intl.DateTimeFormat('vi-VN', { timeZone: tz(), hour: '2-digit', minute: '2-digit' }).format(new Date(ts.toMillis()));
}

async function sha256Hex(text) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
function randomHex(bytes) {
  const a = new Uint8Array(bytes);
  crypto.getRandomValues(a);
  return [...a].map((b) => b.toString(16).padStart(2, '0')).join('');
}
function randomCode() {
  const abc = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  const a = new Uint8Array(8);
  crypto.getRandomValues(a);
  return [...a].map((b) => abc[b % abc.length]).join('');
}

function parseFirebaseConfig(text) {
  const keys = ['apiKey', 'authDomain', 'projectId', 'storageBucket', 'messagingSenderId', 'appId'];
  const out = {};
  for (const k of keys) {
    const m = new RegExp(k + '["\']?\\s*:\\s*["\']([^"\']+)["\']').exec(text || '');
    if (m) out[k] = m[1].trim();
  }
  if (!out.apiKey || !out.projectId || !out.appId) return null;
  if (!out.authDomain) out.authDomain = out.projectId + '.firebaseapp.com';
  return out;
}

function storedConfig() {
  try { return JSON.parse(localStorage.getItem('gv.firebaseConfig') || 'null'); } catch { return null; }
}

const fref = (...p) => doc(fb.db, 'families', S.fid, ...p);
const fcol = (...p) => collection(fb.db, 'families', S.fid, ...p);
const todayKey = () => localParts(Date.now(), tz()).dateKey;

// ---------- khởi động ----------
function start() {
  const c = bakedConfig || storedConfig();
  if (!c) { S.view = 'setup'; render(); return; }
  try {
    const app = initializeApp(c);
    fb = { app, auth: getAuth(app), db: getFirestore(app), cfg: c };
  } catch (e) {
    localStorage.removeItem('gv.firebaseConfig');
    S.view = 'setup'; render(); toast(errMsg(e)); return;
  }
  getRedirectResult(fb.auth).catch((e) => toast(errMsg(e)));
  onAuthStateChanged(fb.auth, async (u) => {
    S.user = u;
    stopListeners();
    if (!u) { S.view = 'signin'; render(); return; }
    S.view = 'loading'; render();
    try {
      const snap = await getDocs(query(collection(fb.db, 'families'), where('parentUids', 'array-contains', u.uid), limit(1)));
      if (snap.empty) { S.view = 'create'; render(); return; }
      S.fid = snap.docs[0].id;
      S.family = snap.docs[0].data();
      S.view = 'main';
      listen();
      render();
    } catch (e) {
      S.view = 'error'; S.error = errMsg(e); render();
    }
  });
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
  setInterval(tick, 15000);
}

function stopListeners() {
  unsubs.forEach((u) => u());
  unsubs = [];
  if (dayUnsub) dayUnsub();
  dayUnsub = null;
  seenRequests = null;
  seenApps = null;
}

function listen() {
  const onErr = (e) => toast(errMsg(e));
  unsubs.push(onSnapshot(doc(fb.db, 'families', S.fid), (s) => { S.family = s.data(); render(); }, onErr));
  unsubs.push(onSnapshot(fref('config', 'main'), (s) => { S.config = s.data() || {}; S.loaded.config = true; render(); }, onErr));
  unsubs.push(onSnapshot(fcol('devices'), (s) => { S.devices = s.docs.map((d) => ({ id: d.id, ...d.data() })); render(); }, onErr));
  unsubs.push(onSnapshot(query(fcol('requests'), orderBy('createdAt', 'desc'), limit(40)), (s) => {
    S.requests = s.docs.map((d) => ({ id: d.id, ...d.data() }));
    const pend = S.requests.filter((r) => r.status === 'pending');
    if (seenRequests) {
      for (const r of pend) if (!seenRequests.has(r.id)) notify(`${childName()} xin thêm ${r.minutes} phút`, r.message || 'Mở Giờ Vàng để duyệt');
    }
    seenRequests = new Set(pend.map((r) => r.id));
    render();
  }, onErr));
  unsubs.push(onSnapshot(fcol('apps'), (s) => {
    S.apps = s.docs.map((d) => ({ id: d.id, ...d.data() }));
    const pend = S.apps.filter((a) => a.category === 'pending');
    if (seenApps) {
      const fresh = pend.filter((a) => !seenApps.has(a.id));
      if (fresh.length === 1) notify(`${childName()} vừa cài app mới`, `${fresh[0].label || fresh[0].id}: chọn Học hay Chơi`);
      else if (fresh.length > 1) notify(`${fresh.length} app mới chờ ba mẹ chọn`, 'Mở mục Ứng dụng để chọn Học hay Chơi');
    }
    seenApps = new Set(pend.map((a) => a.id));
    render();
  }, onErr));
  listenDay();
}

function listenDay() {
  if (dayUnsub) dayUnsub();
  S.dayKey = todayKey();
  S.day = null;
  dayUnsub = onSnapshot(fref('days', S.dayKey), (s) => { S.day = s.data() || {}; render(); }, (e) => toast(errMsg(e)));
}

function tick() {
  S.now = Date.now();
  if (S.view === 'main' && S.fid && todayKey() !== S.dayKey) listenDay();
  render();
}

async function notify(title, body) {
  toast(title);
  try {
    if (navigator.vibrate) navigator.vibrate(120);
    if ('Notification' in window && Notification.permission === 'granted') {
      const reg = await navigator.serviceWorker?.getRegistration();
      if (reg) reg.showNotification(title, { body, icon: 'icon.svg', tag: title });
      else new Notification(title, { body });
    }
  } catch { /* bỏ qua */ }
}

// ---------- vẽ giao diện ----------
let pendingRender = false;
function render() {
  const a = document.activeElement;
  if (a && $app.contains(a) && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA')) {
    if (!pendingRender) {
      pendingRender = true;
      // đợi một chút để cú bấm nút (nếu có) xử lý xong trước khi vẽ lại
      a.addEventListener('blur', () => { setTimeout(() => { pendingRender = false; render(); }, 350); }, { once: true });
    }
    return;
  }
  S.now = Date.now();
  let html = '';
  if (S.view === 'loading') html = `<div class="center muted">Đang tải…</div>`;
  else if (S.view === 'setup') html = viewSetup();
  else if (S.view === 'signin') html = viewSignin();
  else if (S.view === 'create') html = viewCreate();
  else if (S.view === 'error') html = `<div class="center"><p>${esc(S.error)}</p><button class="btn" data-act="reload">Thử lại</button></div>`;
  else if (S.view === 'main') html = viewMain();
  $app.innerHTML = html;
}

function viewSetup() {
  return `
  <div class="header"><div class="title"><span>Cài đặt lần đầu</span><span>Giờ Vàng</span></div></div>
  <div class="card">
    <h2>Dán cấu hình Firebase</h2>
    <p class="small muted" style="margin:0;line-height:1.5">Trong Firebase console: Project settings → Your apps → Web app → chọn “Config”, sao chép đoạn <b>firebaseConfig</b> rồi dán vào đây.</p>
    <label class="lbl" for="cfgText">Cấu hình</label>
    <textarea id="cfgText" class="field" placeholder='const firebaseConfig = { apiKey: "...", projectId: "...", appId: "..." };'></textarea>
    <button class="btn primary block" data-act="saveConfig">Lưu và tiếp tục</button>
  </div>`;
}

function viewSignin() {
  return `
  <div class="center" style="padding-top:72px">
    <img src="icon.svg" width="88" height="88" alt="">
    <h1 class="page" style="margin-top:16px">Giờ Vàng</h1>
    <p class="muted">Quản lý giờ chơi máy tính bảng, laptop, điện thoại của con</p>
    <button class="btn primary block" style="margin-top:24px" data-act="signin">Đăng nhập bằng Google</button>
    <button class="btn link" style="margin-top:12px" data-act="resetConfig">Đổi cấu hình Firebase</button>
  </div>`;
}

function viewCreate() {
  return `
  <div class="header"><div class="title"><span>Bắt đầu</span><span>Tạo gia đình</span></div></div>
  <div class="card">
    <div><label class="lbl" for="cName">Tên con</label><input id="cName" class="field" value="Sóc" maxlength="30"></div>
    <div><label class="lbl" for="cPin">Mã PIN ba mẹ (4–6 số)</label><input id="cPin" class="field" type="password" inputmode="numeric" maxlength="6" autocomplete="new-password"></div>
    <div><label class="lbl" for="cPin2">Nhập lại mã PIN</label><input id="cPin2" class="field" type="password" inputmode="numeric" maxlength="6" autocomplete="new-password"></div>
    <p class="small muted" style="margin:0;line-height:1.5">Mã PIN dùng để mở khóa máy của con, mở Cài đặt hoặc gỡ app. Đừng cho con biết.</p>
    <button class="btn primary block" data-act="createFamily">Tạo</button>
  </div>`;
}

function viewMain() {
  const pendReq = S.requests.filter((r) => r.status === 'pending').length;
  const pendApp = S.apps.filter((a) => a.category === 'pending').length;
  let body = '';
  if (!S.loaded.config || S.day === null) body = `<div class="center muted">Đang tải…</div>`;
  else if (S.tab === 'home') body = viewHome();
  else if (S.tab === 'rules') body = viewRules();
  else if (S.tab === 'requests') body = viewRequests();
  else if (S.tab === 'apps') body = viewApps();
  else if (S.tab === 'devices') body = viewDevices();
  const tab = (id, label, icon, badge) => `
    <button data-act="tab" data-tab="${id}" ${S.tab === id ? 'aria-current="page"' : ''}>
      <svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">${icon}</svg>
      ${label}${badge ? `<span class="badge">${badge}</span>` : ''}
    </button>`;
  return `${body}
  <nav class="nav" aria-label="Điều hướng"><div class="in">
    ${tab('home', 'Tổng quan', '<path d="M3 11l9-8 9 8v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>')}
    ${tab('rules', 'Quy tắc', '<path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/>')}
    ${tab('requests', 'Yêu cầu', '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>', pendReq)}
    ${tab('apps', 'Ứng dụng', '<rect x="4" y="4" width="6" height="6" rx="1.5"/><rect x="14" y="4" width="6" height="6" rx="1.5"/><rect x="4" y="14" width="6" height="6" rx="1.5"/><rect x="14" y="14" width="6" height="6" rx="1.5"/>', pendApp)}
    ${tab('devices', 'Thiết bị', '<rect x="7" y="2.5" width="10" height="19" rx="2"/><path d="M11 18.5h2"/>')}
  </div></nav>`;
}

function header(sub, title) {
  return `<div class="header"><div class="avatar" aria-hidden="true">${esc(childName().slice(0, 1).toUpperCase())}</div>
    <div class="title"><span>${esc(sub)}</span><span>${esc(title)}</span></div></div>`;
}

function deviceName(uid) {
  return S.devices.find((d) => d.id === uid)?.name || 'Thiết bị khác';
}

function isOffline(d) {
  return !d.lastSeen?.toMillis || S.now - d.lastSeen.toMillis() > OFFLINE_MIN * 60000;
}

function viewHome() {
  const c = cfg();
  const s = evaluate({ nowMs: S.now, tz: tz(), config: c, day: S.day });
  const frac = s.quotaSec ? Math.min(1, s.playedSec / s.quotaSec) : 1;
  const circ = 2 * Math.PI * 52;
  const used = Math.round(s.playedSec / 60);
  const reasonText = { hours: `Ngoài giờ được dùng (${s.openAt}–${s.cutoff})`, quota: 'Đã hết quỹ giờ hôm nay', break: `Đang nghỉ giải lao, còn ${fmtDur(s.breakLeftSec)}` }[s.reason];
  const per = Object.entries(S.day?.perDevice || {}).sort((a, b) => b[1] - a[1]);
  const maxPer = Math.max(1, ...per.map((p) => p[1]));
  const pendReq = S.requests.filter((r) => r.status === 'pending');
  const pendApp = S.apps.filter((a) => a.category === 'pending');
  const offline = S.devices.filter(isOffline);
  const dateLabel = new Intl.DateTimeFormat('vi-VN', { timeZone: tz(), weekday: 'long', day: 'numeric', month: 'numeric' }).format(new Date(S.now));

  return `
  ${header('Hôm nay, ' + dateLabel, 'Giờ chơi của ' + childName())}
  <div class="card">
    <div class="big">
      <div class="ring">
        <svg aria-hidden="true" width="124" height="124" viewBox="0 0 124 124"><circle cx="62" cy="62" r="52" fill="none" stroke="#DCEFE8" stroke-width="12"/><circle cx="62" cy="62" r="52" fill="none" stroke="${s.reason === 'quota' ? '#D9822B' : '#0F6E5C'}" stroke-width="12" stroke-linecap="round" stroke-dasharray="${(frac * circ).toFixed(1)} ${circ.toFixed(1)}"/></svg>
        <div class="in"><b>${Math.floor(used / 60)}g ${used % 60}p</b><span>đã chơi</span></div>
      </div>
      <div class="facts">
        <span class="k">Quỹ giờ hôm nay${s.weekend ? ' (cuối tuần)' : ''}</span>
        <span class="v">${fmtDur(s.quotaSec)}${S.day?.bonusMin ? ` <span class="small muted">(+${S.day.bonusMin}p)</span>` : ''}</span>
        <span class="g">Còn lại ${fmtDur(s.playLeftSec)}</span>
        <span class="k">Tự khóa lúc ${s.cutoff}</span>
      </div>
    </div>
    ${s.reason !== 'none' ? `<div class="pill warn" style="align-self:flex-start">${esc(reasonText)}</div>` : ''}
    ${c.session.enabled && s.reason === 'none' ? `<div class="small muted">Lượt chơi hiện tại còn ${fmtDur(s.sessionLeftSec)} (tối đa ${c.session.maxMin} phút, nghỉ ${c.session.breakMin} phút)</div>` : ''}
  </div>

  ${pendReq.length ? `<button class="card warn" style="text-align:left;cursor:pointer;font:inherit" data-act="tab" data-tab="requests">
    <b>${esc(childName())} xin thêm ${pendReq[0].minutes} phút</b><span class="small">${pendReq.length > 1 ? `và ${pendReq.length - 1} yêu cầu khác · ` : ''}Bấm để duyệt</span></button>` : ''}
  ${pendApp.length ? `<button class="card warn" style="text-align:left;cursor:pointer;font:inherit" data-act="tab" data-tab="apps">
    <b>${pendApp.length} app mới chờ ba mẹ chọn Học hay Chơi</b><span class="small">Tạm tính là Chơi · Bấm để chọn</span></button>` : ''}
  ${offline.length && S.devices.length ? `<div class="card warn"><b>Mất liên lạc với ${offline.map((d) => esc(d.name)).join(', ')}</b><span class="small">Máy tắt, mất mạng, hoặc app bị tắt. Lần cuối: ${offline.map((d) => ago(d.lastSeen)).join(', ')}</span></div>` : ''}

  <div class="card">
    <h2>Theo thiết bị</h2>
    ${per.length ? per.map(([uid, sec]) => `
      <div style="display:flex;flex-direction:column;gap:6px">
        <div class="row" style="min-height:0"><b style="font-size:14px">${esc(deviceName(uid))}</b><span class="small muted">${fmtDur(sec)}</span></div>
        <div class="bar"><div style="width:${Math.round((sec / maxPer) * 100)}%"></div></div>
      </div>`).join('') : `<span class="small muted">Hôm nay chưa chơi.</span>`}
  </div>

  <div class="card">
    <h2>Cho thêm giờ ngay</h2>
    <div class="btns">${[15, 30, 60].map((m) => `<button class="chip ${S.quickExtra === m ? 'on' : ''}" data-act="quickPick" data-min="${m}">${m === 60 ? '1 giờ' : m + ' phút'}</button>`).join('')}</div>
    <button class="btn primary block" data-act="quickExtra">Thêm ${S.quickExtra} phút cho ${esc(childName())}</button>
  </div>`;
}

function stepper(act, path, value, labelDec, labelInc) {
  return `<div class="stepper">
    <button aria-label="${esc(labelDec)}" data-act="${act}" data-path="${path}" data-d="-1">−</button>
    <span class="val">${esc(value)}</span>
    <button aria-label="${esc(labelInc)}" data-act="${act}" data-path="${path}" data-d="1">+</button>
  </div>`;
}

function sw(path, on, label) {
  return `<button class="switch" role="switch" aria-checked="${on ? 'true' : 'false'}" aria-label="${esc(label)}" data-act="toggle" data-path="${path}"><span class="track"><span class="knob"></span></span></button>`;
}

function viewRules() {
  const c = cfg();
  const group = (key, title) => `
    <h3>${title}</h3>
    <div class="row"><b style="font-size:14px">Quỹ giờ chơi</b>${stepper('step', key + '.quotaMin', fmtDur(c[key].quotaMin * 60), 'Giảm quỹ giờ', 'Tăng quỹ giờ')}</div>
    <div class="row"><b style="font-size:14px">Mở máy lúc</b>${stepper('step', key + '.openAt', c[key].openAt, 'Mở máy sớm hơn', 'Mở máy muộn hơn')}</div>
    <div class="row"><b style="font-size:14px">Tự khóa lúc</b>${stepper('step', key + '.cutoff', c[key].cutoff, 'Khóa sớm hơn', 'Khóa muộn hơn')}</div>`;
  return `
  ${header('Giờ chơi của ' + childName(), 'Quy tắc')}
  <div class="card">
    <div><h2>Quỹ giờ và giờ tắt máy</h2><span class="small muted">Quỹ giờ dùng chung cho mọi thiết bị. Mỗi lần bấm thay đổi 15 phút.</span></div>
    ${group('weekday', 'Ngày thường (T2 đến T6)')}
    <div class="sep"></div>
    ${group('weekend', 'Cuối tuần (T7, CN)')}
  </div>

  <div class="card">
    <div class="row"><div class="label"><b style="font-size:15px;font-weight:700">Giới hạn mỗi lần chơi</b><span>${c.session.enabled ? 'Hết lượt thì máy nghỉ giải lao' : 'Đang tắt: chơi liên tục không giới hạn'}</span></div>${sw('session.enabled', c.session.enabled, 'Giới hạn mỗi lần chơi')}</div>
    <div class="${c.session.enabled ? '' : 'dim'}">
      <div class="row"><b style="font-size:14px">Tối đa mỗi lần</b>${stepper('step', 'session.maxMin', c.session.maxMin + ' phút', 'Giảm', 'Tăng')}</div>
      <div class="row"><b style="font-size:14px">Nghỉ giữa các lần</b>${stepper('step', 'session.breakMin', c.session.breakMin + ' phút', 'Giảm', 'Tăng')}</div>
    </div>
    <span class="small muted">Chỉ áp dụng cho giờ chơi. Giờ học không bị ngắt.</span>
  </div>

  <div class="card">
    <div class="row"><div class="label"><b style="font-size:15px;font-weight:700">Cho con xin thêm giờ</b><span>${c.requests.enabled ? 'Con thấy nút xin thêm giờ' : 'Đang tắt: con không xin được'}</span></div>${sw('requests.enabled', c.requests.enabled, 'Cho con xin thêm giờ')}</div>
    <div class="row ${c.requests.enabled ? '' : 'dim'}"><b style="font-size:14px">Số lần tối đa mỗi ngày</b>${stepper('step', 'requests.maxPerDay', c.requests.maxPerDay + ' lần', 'Giảm', 'Tăng')}</div>
  </div>

  <div class="card">
    <div class="row"><div class="label"><b style="font-size:15px;font-weight:700">Khóa Cài đặt bằng PIN</b><span>Chặn con tắt app, đổi giờ, gỡ app</span></div>${sw('protectSettings', c.protectSettings, 'Khóa Cài đặt bằng PIN')}</div>
    <div class="sep"></div>
    <div><label class="lbl" for="newPin">Đổi mã PIN ba mẹ</label>
      <div class="btns"><input id="newPin" class="field" type="password" inputmode="numeric" maxlength="6" placeholder="4–6 số" autocomplete="new-password"><button class="btn" style="flex:0 0 auto" data-act="changePin">Đổi</button></div>
    </div>
  </div>

  <div class="card">
    <h2>Phân loại chơi và học</h2>
    <span class="small muted">Chỉ giờ chơi tính vào quỹ giờ. App chưa xếp nhóm tự tính là Chơi.</span>
    <button class="btn block" style="color:var(--brand);font-weight:700" data-act="tab" data-tab="apps">Chọn app thuộc nhóm Học</button>
  </div>`;
}

function viewRequests() {
  const c = cfg();
  const s = evaluate({ nowMs: S.now, tz: tz(), config: c, day: S.day });
  const pend = S.requests.filter((r) => r.status === 'pending');
  const done = S.requests.filter((r) => r.status !== 'pending' && r.day === S.dayKey);
  const used = S.day?.requestCount || 0;
  const reqCard = (r) => {
    const val = S.extraMin[r.id] ?? String(r.minutes);
    const m = parseInt(val, 10) || 0;
    return `
    <div class="card alert">
      <div class="item" style="min-height:0">
        <div class="avatar" aria-hidden="true">${esc(childName().slice(0, 1).toUpperCase())}</div>
        <div class="label"><b style="font-size:16px;font-weight:700;white-space:normal">${esc(childName())} xin thêm ${r.minutes} phút</b><span>${hhmm(r.createdAt)}, trên ${esc(r.deviceName || 'thiết bị')}</span></div>
      </div>
      ${r.message ? `<div class="msg">${esc(r.message)}</div>` : ''}
      <div class="row" style="min-height:0"><span class="small muted">Quỹ giờ còn lại</span><b class="small">${fmtDur(s.playLeftSec)}</b></div>
      <div class="row" style="min-height:0"><span class="small muted">Số lần đã xin hôm nay</span><span class="pill ${used >= c.requests.maxPerDay ? 'warn' : 'no'}">${used} / ${c.requests.maxPerDay} lần</span></div>
      <div style="display:flex;flex-direction:column;gap:8px">
        <span class="small" style="font-weight:600">Cho thêm</span>
        <div class="btns">${[15, 30, 60].map((x) => `<button class="chip ${m === x ? 'on' : ''}" data-act="pickExtra" data-id="${r.id}" data-min="${x}">${x === 60 ? '1 giờ' : x + ' phút'}</button>`).join('')}</div>
        <div class="row" style="min-height:0">
          <label for="ex_${r.id}" class="small" style="font-weight:600;flex-shrink:0">Hoặc nhập số phút</label>
          <input id="ex_${r.id}" class="field" style="text-align:center;font-weight:700;max-width:110px" inputmode="numeric" maxlength="3" value="${esc(val)}" data-input="extra" data-id="${r.id}">
        </div>
      </div>
      <div class="btns">
        <button class="btn" data-act="deny" data-id="${r.id}">Từ chối</button>
        <button class="btn primary" style="flex:2" data-act="approve" data-id="${r.id}" ${m > 0 ? '' : 'disabled'}>${m > 0 ? `Đồng ý thêm ${m} phút` : 'Nhập số phút'}</button>
      </div>
    </div>`;
  };
  return `
  ${header('Giờ chơi của ' + childName(), 'Yêu cầu')}
  ${!c.requests.enabled ? `<div class="card"><b>Đang tắt cho con xin thêm giờ</b><span class="small muted">Bật lại ở mục Quy tắc.</span></div>` : ''}
  ${pend.length ? pend.map(reqCard).join('') : `<div class="card"><span class="muted">Không có yêu cầu nào đang chờ.</span></div>`}
  <div class="card">
    <h2>Đã xử lý hôm nay</h2>
    ${done.length ? done.map((r) => `
      <div class="item" style="min-height:44px">
        <div class="label"><b>Xin thêm ${r.minutes} phút</b><span>${hhmm(r.createdAt)}, trên ${esc(r.deviceName || 'thiết bị')}</span></div>
        ${r.status === 'approved' ? `<span class="pill ok">Đồng ý ${r.grantedMin} phút</span>` : `<span class="pill no">Từ chối</span>`}
      </div>`).join('') : `<span class="small muted">Chưa có.</span>`}
  </div>`;
}

function viewApps() {
  const sorted = [...S.apps].sort((a, b) => String(a.label || a.id).localeCompare(String(b.label || b.id), 'vi'));
  const groups = [
    ['pending', 'Chờ ba mẹ duyệt', 'Tạm tính là Chơi'],
    ['study', 'Nhóm Học', 'Không tính giờ, luôn mở'],
    ['play', 'Nhóm Chơi', 'Tính vào quỹ giờ'],
  ];
  const item = (a) => `
    <div class="item">
      <div class="appicon" aria-hidden="true">${esc(String(a.label || a.id).slice(0, 1).toUpperCase())}</div>
      <div class="label"><b>${esc(a.label || a.id)}</b><span>${a.category === 'pending' && a.suggested ? `Gợi ý: ${a.suggested === 'study' ? 'Học' : 'Chơi'} · ` : ''}${esc(a.deviceName || '')}</span></div>
      <div class="seg">
        <button class="chip ${a.category !== 'study' && a.category !== 'pending' ? 'on-play' : ''}" aria-pressed="${a.category === 'play'}" data-act="setCat" data-id="${esc(a.id)}" data-cat="play">Chơi</button>
        <button class="chip ${a.category === 'study' ? 'on-study' : ''}" aria-pressed="${a.category === 'study'}" data-act="setCat" data-id="${esc(a.id)}" data-cat="study">Học</button>
      </div>
    </div>`;
  const studyCount = S.apps.filter((a) => a.category === 'study').length;
  return `
  ${header('Giờ chơi của ' + childName(), 'Ứng dụng')}
  <div class="card"><span class="small muted" style="line-height:1.5">App chưa xếp nhóm tự tính là Chơi. Đang có <b style="color:var(--brand)">${studyCount} app</b> thuộc nhóm Học. Chỉ nên xếp vào Học những app con không chơi game được trong đó. YouTube và trình duyệt nên để là Chơi.</span></div>
  ${S.apps.length === 0 ? `<div class="card"><span class="muted">Chưa có app nào. Danh sách app hiện ra sau khi ghép cặp máy của con.</span></div>` : ''}
  ${groups.map(([cat, title, hint]) => {
    const list = sorted.filter((a) => (cat === 'play' ? a.category !== 'study' && a.category !== 'pending' : a.category === cat));
    if (!list.length) return '';
    return `<div class="card ${cat === 'pending' ? 'alert' : ''}"><div class="row" style="min-height:0"><h2>${title}</h2><span class="small muted">${hint}</span></div><div class="list">${list.map(item).join('')}</div></div>`;
  }).join('')}`;
}

function viewDevices() {
  const p = S.pairing;
  const pairValid = p && p.expiresAt > S.now;
  return `
  ${header('Giờ chơi của ' + childName(), 'Thiết bị')}
  <div class="card">
    <h2>Máy của ${esc(childName())}</h2>
    ${S.devices.length ? S.devices.map((d) => {
      const perms = d.perms || {};
      const missing = [['usage', 'Truy cập dữ liệu sử dụng'], ['overlay', 'Hiển thị trên app khác'], ['admin', 'Quản trị thiết bị']].filter(([k]) => perms[k] === false).map(([, v]) => v);
      return `<div class="item">
        <div class="appicon" aria-hidden="true">${d.platform === 'windows' ? 'W' : 'A'}</div>
        <div class="label"><b>${esc(d.name || 'Thiết bị')}</b><span>${isOffline(d) ? 'Mất liên lạc · ' : ''}Lần cuối: ${ago(d.lastSeen)}${d.appVersion ? ' · bản ' + esc(d.appVersion) : ''}</span>
        ${missing.length ? `<span style="color:var(--danger)">Thiếu quyền: ${missing.join(', ')}</span>` : ''}</div>
        <button class="btn link danger" data-act="removeDevice" data-id="${d.id}" aria-label="Gỡ ${esc(d.name || 'thiết bị')}">Gỡ</button>
      </div>`;
    }).join('') : `<span class="small muted">Chưa có máy nào.</span>`}
  </div>

  <div class="card">
    <h2>Thêm máy của con</h2>
    ${pairValid ? `
      <span class="small muted" style="line-height:1.5">Mở app Giờ Vàng trên máy của con, chọn “Dán mã cài đặt” và dán mã dưới đây. Mã dùng được trong 15 phút.</span>
      <div class="pair" aria-label="Mã ghép cặp">${esc(p.code)}</div>
      <div class="code">${esc(p.setup)}</div>
      <div class="btns"><button class="btn primary" data-act="copySetup">Sao chép mã</button>${navigator.share ? `<button class="btn" data-act="shareSetup">Gửi qua Zalo…</button>` : ''}</div>
    ` : `
      <span class="small muted" style="line-height:1.5">Cài file APK Giờ Vàng lên điện thoại hoặc máy tính bảng của con, rồi bấm nút dưới đây để lấy mã cài đặt.</span>
      <button class="btn primary block" data-act="newPair">Tạo mã cài đặt</button>
    `}
  </div>

  <div class="card">
    <h2>Thông báo</h2>
    <span class="small muted" style="line-height:1.5">Khi web app đang mở, Giờ Vàng báo ngay lúc con xin thêm giờ hoặc cài app mới.</span>
    ${'Notification' in window ? (Notification.permission === 'granted' ? `<span class="pill ok" style="align-self:flex-start">Đã bật thông báo</span>` : `<button class="btn block" data-act="enableNotif">Bật thông báo</button>`) : ''}
  </div>

  <div class="card">
    <h2>Tài khoản</h2>
    <span class="small muted">${esc(S.user?.email || '')}</span>
    <button class="btn block" data-act="signout">Đăng xuất</button>
  </div>`;
}

// ---------- hành động ----------
let saveTimer = null;
function saveConfigSoon() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    try {
      const c = cfg();
      await setDoc(fref('config', 'main'), {
        weekday: c.weekday, weekend: c.weekend, session: c.session, requests: c.requests,
        protectSettings: c.protectSettings, updatedAt: serverTimestamp(),
      }, { merge: true });
      toast('Đã lưu');
    } catch (e) { toast(errMsg(e)); }
  }, 500);
}

function setPath(obj, path, value) {
  const ks = path.split('.');
  let o = obj;
  for (let i = 0; i < ks.length - 1; i++) { o[ks[i]] = { ...(o[ks[i]] || {}) }; o = o[ks[i]]; }
  o[ks[ks.length - 1]] = value;
}
function getPath(obj, path) {
  return path.split('.').reduce((o, k) => (o == null ? o : o[k]), obj);
}

function stepValue(path, dir) {
  const c = cfg();
  const cur = getPath(c, path);
  const [grp, key] = path.split('.');
  if (key === 'quotaMin') return Math.max(15, Math.min(720, cur + dir * 15));
  if (key === 'openAt') return minToHm(Math.max(0, Math.min(hmToMin(c[grp].cutoff) - 15, hmToMin(cur) + dir * 15)));
  if (key === 'cutoff') return minToHm(Math.max(hmToMin(c[grp].openAt) + 15, Math.min(23 * 60 + 45, hmToMin(cur) + dir * 15)));
  if (key === 'maxMin') return Math.max(5, Math.min(120, cur + dir * 5));
  if (key === 'breakMin') return Math.max(0, Math.min(60, cur + dir * 5));
  if (key === 'maxPerDay') return Math.max(0, Math.min(10, cur + dir));
  return cur;
}

async function addExtra(minutes, requestId) {
  const dref = fref('days', todayKey());
  await runTransaction(fb.db, async (tx) => {
    const ds = await tx.get(dref);
    const patch = approveExtra(ds.exists() ? ds.data() : {}, minutes, Date.now(), tz(), cfg());
    tx.set(dref, patch, { merge: true });
    if (requestId) {
      tx.update(fref('requests', requestId), { status: 'approved', grantedMin: minutes, decidedAt: serverTimestamp(), decidedBy: S.user.uid });
    }
  });
}

const actions = {
  reload: () => location.reload(),
  saveConfig: () => {
    const c = parseFirebaseConfig(document.getElementById('cfgText').value);
    if (!c) { toast('Chưa đúng cấu hình. Cần có apiKey, projectId và appId.'); return; }
    localStorage.setItem('gv.firebaseConfig', JSON.stringify(c));
    location.reload();
  },
  resetConfig: () => {
    if (bakedConfig) { toast('Cấu hình đang nằm trong file config.js'); return; }
    localStorage.removeItem('gv.firebaseConfig');
    location.reload();
  },
  signin: async () => {
    const p = new GoogleAuthProvider();
    try { await signInWithPopup(fb.auth, p); } catch (e) {
      if (String(e?.code).includes('popup-blocked') || String(e?.code).includes('operation-not-supported')) await signInWithRedirect(fb.auth, p);
      else toast(errMsg(e));
    }
  },
  signout: () => signOut(fb.auth),
  createFamily: async () => {
    const name = document.getElementById('cName').value.trim() || 'con';
    const pin = document.getElementById('cPin').value.trim();
    const pin2 = document.getElementById('cPin2').value.trim();
    if (!/^\d{4,6}$/.test(pin)) { toast('Mã PIN cần 4 đến 6 chữ số'); return; }
    if (pin !== pin2) { toast('Hai lần nhập mã PIN chưa khớp'); return; }
    try {
      const fdoc = doc(collection(fb.db, 'families'));
      await setDoc(fdoc, { parentUids: [S.user.uid], childName: name, tz: TZ_DEFAULT, createdAt: serverTimestamp() });
      S.fid = fdoc.id;
      const salt = randomHex(16);
      await setDoc(fref('config', 'main'), { ...DEFAULT_CONFIG, pinSalt: salt, pinHash: await sha256Hex(`${salt}:${pin}`), updatedAt: serverTimestamp() });
      S.family = { childName: name, tz: TZ_DEFAULT, parentUids: [S.user.uid] };
      S.view = 'main'; S.tab = 'devices';
      listen(); render();
    } catch (e) { toast(errMsg(e)); }
  },
  tab: (el) => { S.tab = el.dataset.tab; render(); window.scrollTo(0, 0); },
  step: (el) => {
    const v = stepValue(el.dataset.path, Number(el.dataset.d));
    const c = cfg();
    setPath(c, el.dataset.path, v);
    S.config = c;
    render(); saveConfigSoon();
  },
  toggle: (el) => {
    const c = cfg();
    setPath(c, el.dataset.path, !getPath(c, el.dataset.path));
    S.config = c;
    render(); saveConfigSoon();
  },
  changePin: async () => {
    const pin = document.getElementById('newPin').value.trim();
    if (!/^\d{4,6}$/.test(pin)) { toast('Mã PIN cần 4 đến 6 chữ số'); return; }
    try {
      const salt = randomHex(16);
      await setDoc(fref('config', 'main'), { pinSalt: salt, pinHash: await sha256Hex(`${salt}:${pin}`) }, { merge: true });
      document.getElementById('newPin').value = '';
      toast('Đã đổi mã PIN');
    } catch (e) { toast(errMsg(e)); }
  },
  pickExtra: (el) => { S.extraMin[el.dataset.id] = el.dataset.min; render(); },
  approve: async (el) => {
    const id = el.dataset.id;
    const r = S.requests.find((x) => x.id === id);
    const m = parseInt(S.extraMin[id] ?? r?.minutes, 10) || 0;
    if (m <= 0 || m > 600) { toast('Số phút từ 1 đến 600'); return; }
    el.disabled = true;
    try { await addExtra(m, id); toast(`Đã cho thêm ${m} phút`); } catch (e) { toast(errMsg(e)); el.disabled = false; }
  },
  deny: async (el) => {
    try {
      await updateDoc(fref('requests', el.dataset.id), { status: 'denied', decidedAt: serverTimestamp(), decidedBy: S.user.uid });
      toast('Đã từ chối');
    } catch (e) { toast(errMsg(e)); }
  },
  quickPick: (el) => { S.quickExtra = Number(el.dataset.min); render(); },
  quickExtra: async (el) => {
    el.disabled = true;
    try { await addExtra(S.quickExtra); toast(`Đã cho thêm ${S.quickExtra} phút`); } catch (e) { toast(errMsg(e)); }
    el.disabled = false;
  },
  setCat: async (el) => {
    try { await updateDoc(fref('apps', el.dataset.id), { category: el.dataset.cat, decidedAt: serverTimestamp() }); } catch (e) { toast(errMsg(e)); }
  },
  newPair: async () => {
    const code = randomCode();
    const expiresAt = Date.now() + 15 * 60000;
    try {
      await setDoc(doc(fb.db, 'pairCodes', code), { familyId: S.fid, expiresAt: Timestamp.fromMillis(expiresAt), createdBy: S.user.uid });
      const c = fb.cfg;
      const payload = { v: 1, k: c.apiKey, p: c.projectId, a: c.appId, d: c.authDomain, f: S.fid, c: code, n: childName() };
      // mã hóa UTF-8 để tên có dấu (Sóc, Hải…) không bị lỗi
      const bytes = new TextEncoder().encode(JSON.stringify(payload));
      S.pairing = { code, expiresAt, setup: 'GV1.' + btoa(String.fromCharCode(...bytes)) };
      render();
    } catch (e) { toast(errMsg(e)); }
  },
  copySetup: async () => {
    try { await navigator.clipboard.writeText(S.pairing.setup); toast('Đã sao chép mã cài đặt'); } catch { toast('Không sao chép được, hãy chọn và sao chép thủ công'); }
  },
  shareSetup: async () => {
    try { await navigator.share({ title: 'Mã cài đặt Giờ Vàng', text: S.pairing.setup }); } catch { /* người dùng hủy */ }
  },
  removeDevice: async (el) => {
    const d = S.devices.find((x) => x.id === el.dataset.id);
    if (!window.confirm(`Gỡ ${d?.name || 'thiết bị'} khỏi gia đình? Máy đó sẽ không còn bị quản lý.`)) return;
    try { await deleteDoc(fref('devices', el.dataset.id)); toast('Đã gỡ thiết bị'); } catch (e) { toast(errMsg(e)); }
  },
  enableNotif: async () => {
    try { await Notification.requestPermission(); } catch { /* bỏ qua */ }
    render();
  },
};

$app.addEventListener('click', (e) => {
  const el = e.target.closest('[data-act]');
  if (!el || !$app.contains(el)) return;
  const fn = actions[el.dataset.act];
  if (fn) fn(el);
});
$app.addEventListener('input', (e) => {
  const el = e.target;
  if (el.dataset.input === 'extra') {
    el.value = el.value.replace(/\D/g, '').slice(0, 3);
    S.extraMin[el.dataset.id] = el.value;
    const btn = $app.querySelector(`[data-act="approve"][data-id="${el.dataset.id}"]`);
    const m = parseInt(el.value, 10) || 0;
    if (btn) { btn.disabled = m <= 0; btn.textContent = m > 0 ? `Đồng ý thêm ${m} phút` : 'Nhập số phút'; }
    $app.querySelectorAll(`[data-act="pickExtra"][data-id="${el.dataset.id}"]`).forEach((c) => c.classList.toggle('on', Number(c.dataset.min) === m));
  }
});

start();
