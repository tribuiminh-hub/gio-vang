// Chạy thử web ba mẹ trong Chromium với Firebase giả lập
import { createRequire } from 'node:module';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json' };
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p === '/') p = '/index.html';
  const f = p.startsWith('/tests/') ? path.join(root, p) : path.join(root, 'web', p);
  if (!fs.existsSync(f)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' });
  res.end(fs.readFileSync(f));
}).listen(0);
const base = `http://localhost:${server.address().port}`;

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error' && !/fonts\.g/.test(m.text())) errors.push('console: ' + m.text()); });
await page.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.fulfill({ status: 200, body: '' }));
const fake = fs.readFileSync(path.join(root, 'tests/fake-firebase.js'), 'utf8');
await page.route(/gstatic\.com\/firebasejs/, (r) => r.fulfill({ status: 200, contentType: 'text/javascript', body: fake }));

const shots = path.join(root, 'tests', 'shots');
fs.mkdirSync(shots, { recursive: true });
const shot = (n) => page.screenshot({ path: path.join(shots, n + '.png'), fullPage: true });
const step = async (name, fn) => { await fn(); await page.waitForTimeout(250); console.log('ok -', name); };

await page.goto(base);
await step('màn hình nhập cấu hình', async () => {
  await page.waitForSelector('#cfgText');
  await page.fill('#cfgText', 'const firebaseConfig = {\n apiKey: "AIzaFAKE",\n authDomain: "gv-test.firebaseapp.com",\n projectId: "gv-test",\n appId: "1:123:web:abc"\n};');
  await page.click('[data-act=saveConfig]');
  await page.waitForSelector('[data-act=signin]');
});
await step('đăng nhập', async () => { await page.click('[data-act=signin]'); await page.waitForSelector('#cName'); });
await step('tạo gia đình', async () => {
  await page.fill('#cPin', '1234');
  await page.fill('#cPin2', '1234');
  await page.click('[data-act=createFamily]');
  await page.waitForSelector('[data-act=newPair]');
});
await step('tạo mã cài đặt', async () => {
  await page.click('[data-act=newPair]');
  await page.waitForSelector('.pair');
  const setup = await page.textContent('.code');
  const json = JSON.parse(Buffer.from(setup.slice(4), 'base64').toString('utf8'));
  if (json.n !== 'Sóc' || json.p !== 'gv-test' || json.c.length !== 8) throw new Error('mã cài đặt sai: ' + setup);
  await shot('devices');
});

// Giả lập máy của con: ghép cặp, chơi, xin thêm giờ, cài app mới
await page.evaluate(() => {
  const f = window.__fake;
  const fid = [...f.store.keys()].find((k) => /^families\/[^/]+$/.test(k)).split('/')[1];
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  window.__fid = fid; window.__today = today;
  f.put(`families/${fid}/devices/dev1`, { name: 'Máy tính bảng', platform: 'android', lastSeen: f.Timestamp.now(), perms: { usage: true, overlay: true, admin: false } });
  f.put(`families/${fid}/devices/dev2`, { name: 'Điện thoại', platform: 'android', lastSeen: f.Timestamp.fromMillis(Date.now() - 3600e3), perms: { usage: true, overlay: true, admin: true } });
  f.put(`families/${fid}/days/${today}`, { playedSec: 165 * 60, requestCount: 1, sessionSec: 300, lastPlayAt: Date.now() - 30e3, perDevice: { dev1: 6000, dev2: 3900 } });
  f.put(`families/${fid}/requests/r1`, { minutes: 30, message: 'Con đang chơi dở màn', deviceUid: 'dev1', deviceName: 'Máy tính bảng', status: 'pending', createdAt: f.Timestamp.now(), day: today });
  f.put(`families/${fid}/apps/com.mojang.minecraftpe`, { label: 'Minecraft', category: 'pending', suggested: 'play', deviceName: 'Máy tính bảng' });
  f.put(`families/${fid}/apps/com.google.android.apps.classroom`, { label: 'Google Classroom', category: 'pending', suggested: 'study', deviceName: 'Máy tính bảng' });
  f.put(`families/${fid}/apps/com.google.android.youtube`, { label: 'YouTube', category: 'play', deviceName: 'Điện thoại' });
});

await step('tổng quan', async () => {
  await page.click('[data-act=tab][data-tab=home]');
  await page.waitForSelector('.ring');
  const t = await page.textContent('#app');
  for (const s of ['Giờ chơi của Sóc', 'Còn lại 15 phút', 'xin thêm 30 phút', '2 app mới', 'Mất liên lạc với Điện thoại', 'Máy tính bảng']) {
    if (!t.includes(s)) throw new Error('Tổng quan thiếu: ' + s);
  }
  await shot('home');
});
await step('quy tắc: tăng quỹ giờ, đổi giờ khóa, tắt lượt chơi', async () => {
  await page.click('[data-act=tab][data-tab=rules]');
  await page.click('[data-act=step][data-path="weekend.quotaMin"][data-d="1"]');
  await page.click('[data-act=step][data-path="weekday.cutoff"][data-d="-1"]');
  await page.click('[data-act=toggle][data-path="session.enabled"]');
  await page.waitForTimeout(700);
  const cfg = await page.evaluate(() => window.__fake.store.get(`families/${window.__fid}/config/main`));
  if (cfg.weekend.quotaMin !== 195) throw new Error('quỹ giờ cuối tuần chưa lưu: ' + cfg.weekend.quotaMin);
  if (cfg.weekday.cutoff !== '20:15') throw new Error('giờ khóa chưa lưu: ' + cfg.weekday.cutoff);
  if (cfg.session.enabled !== false) throw new Error('chưa tắt lượt chơi');
  if (!cfg.pinHash || cfg.pinHash.length !== 64) throw new Error('thiếu PIN');
  await page.click('[data-act=toggle][data-path="session.enabled"]');
  await shot('rules');
});
await step('duyệt: nhập 45 phút rồi đồng ý', async () => {
  await page.click('[data-act=tab][data-tab=requests]');
  await page.fill('input[data-input=extra]', '45');
  const label = await page.textContent('[data-act=approve]');
  if (!label.includes('45')) throw new Error('nút chưa đổi theo số phút: ' + label);
  await shot('requests');
  await page.click('[data-act=approve]');
  await page.waitForTimeout(500);
  const st = await page.evaluate(() => ({ r: window.__fake.store.get(`families/${window.__fid}/requests/r1`), d: window.__fake.store.get(`families/${window.__fid}/days/${window.__today}`) }));
  if (st.r.status !== 'approved' || st.r.grantedMin !== 45) throw new Error('yêu cầu chưa được duyệt: ' + JSON.stringify(st.r));
  if (st.d.bonusMin !== 45) throw new Error('chưa cộng giờ: ' + st.d.bonusMin);
  const t = await page.textContent('#app');
  if (!t.includes('Đồng ý 45 phút')) throw new Error('lịch sử chưa cập nhật');
});
await step('ứng dụng: xếp Classroom vào Học', async () => {
  await page.click('[data-act=tab][data-tab=apps]');
  await page.click('[data-act=setCat][data-id="com.google.android.apps.classroom"][data-cat=study]');
  await page.waitForTimeout(300);
  const a = await page.evaluate(() => window.__fake.store.get(`families/${window.__fid}/apps/com.google.android.apps.classroom`));
  if (a.category !== 'study') throw new Error('chưa đổi nhóm');
  await shot('apps');
});
await step('cho thêm giờ nhanh', async () => {
  await page.click('[data-act=tab][data-tab=home]');
  await page.click('[data-act=quickPick][data-min="15"]');
  await page.click('[data-act=quickExtra]');
  await page.waitForTimeout(400);
  const d = await page.evaluate(() => window.__fake.store.get(`families/${window.__fid}/days/${window.__today}`));
  if (d.bonusMin !== 60) throw new Error('cộng nhanh sai: ' + d.bonusMin);
  await shot('home-after');
});

await browser.close();
server.close();
if (errors.length) { console.error('\nLỗi trong trang:\n' + errors.join('\n')); process.exit(1); }
console.log('\nWeb ba mẹ chạy thử đạt');
