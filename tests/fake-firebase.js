// Firebase giả lập trong bộ nhớ, chỉ dùng để chạy thử giao diện web
const store = new Map(); // path -> data
const subs = new Set();
let user = null;
const authSubs = new Set();

export class Timestamp {
  constructor(ms) { this.ms = ms; }
  static fromMillis(ms) { return new Timestamp(ms); }
  static now() { return new Timestamp(Date.now()); }
  toMillis() { return this.ms; }
  toDate() { return new Date(this.ms); }
}
const SERVER_TS = { __server: true };
const INC = (n) => ({ __inc: n });

function resolve(v, prev) {
  if (v && v.__server) return Timestamp.now();
  if (v && v.__inc !== undefined) return (typeof prev === 'number' ? prev : 0) + v.__inc;
  if (v && typeof v === 'object' && !(v instanceof Timestamp) && !Array.isArray(v)) {
    const out = { ...(prev && typeof prev === 'object' ? prev : {}) };
    for (const [k, x] of Object.entries(v)) out[k] = resolve(x, out[k]);
    return out;
  }
  return v;
}
function notify() { for (const s of subs) s(); }

// app
export function initializeApp(cfg) { return { options: cfg }; }

// auth
export function getAuth() { return { get currentUser() { return user; } }; }
export class GoogleAuthProvider {}
export async function signInWithPopup() { user = { uid: 'parent1', email: 'tri@example.com' }; authSubs.forEach((f) => f(user)); return { user }; }
export async function signInWithRedirect() { return signInWithPopup(); }
export async function getRedirectResult() { return null; }
export function onAuthStateChanged(_a, f) { authSubs.add(f); setTimeout(() => f(user), 0); return () => authSubs.delete(f); }
export async function signOut() { user = null; authSubs.forEach((f) => f(null)); }

// firestore
let autoId = 0;
export function getFirestore() { return {}; }
export function doc(a, ...parts) {
  if (a && a.kind === 'col' && parts.length === 0) {
    const id = 'fam' + ++autoId;
    return { kind: 'doc', path: a.path + '/' + id, id };
  }
  return { kind: 'doc', path: parts.join('/'), id: parts[parts.length - 1] };
}
export function collection(_db, ...parts) { return { kind: 'col', path: parts.join('/') }; }
export function where(field, op, value) { return { t: 'where', field, op, value }; }
export function orderBy(field, dir) { return { t: 'order', field, dir }; }
export function limit(n) { return { t: 'limit', n }; }
export function query(col, ...cs) { return { kind: 'query', path: col.path, cs }; }
export function serverTimestamp() { return SERVER_TS; }
export function increment(n) { return INC(n); }

function snapDoc(path) {
  const data = store.get(path);
  const id = path.split('/').pop();
  return { id, exists: () => data !== undefined, data: () => (data === undefined ? undefined : structuredCloneSafe(data)) };
}
function structuredCloneSafe(o) { return JSON.parse(JSON.stringify(o), (k, v) => (v && v.ms !== undefined && Object.keys(v).length === 1 ? new Timestamp(v.ms) : v)); }
function runQuery(q) {
  const base = q.path + '/';
  let docs = [...store.keys()].filter((p) => p.startsWith(base) && !p.slice(base.length).includes('/')).map(snapDoc);
  for (const c of q.cs || []) {
    if (c.t === 'where' && c.op === 'array-contains') docs = docs.filter((d) => (d.data()[c.field] || []).includes(c.value));
    if (c.t === 'where' && c.op === '==') docs = docs.filter((d) => d.data()[c.field] === c.value);
    if (c.t === 'order') docs.sort((a, b) => { const x = a.data()[c.field]?.ms ?? 0; const y = b.data()[c.field]?.ms ?? 0; return c.dir === 'desc' ? y - x : x - y; });
    if (c.t === 'limit') docs = docs.slice(0, c.n);
  }
  return { docs, empty: docs.length === 0 };
}
export async function getDocs(q) { return runQuery(q.kind === 'col' ? { path: q.path, cs: [] } : q); }
export async function getDoc(r) { return snapDoc(r.path); }
export async function setDoc(r, data, opts) {
  const prev = opts?.merge ? store.get(r.path) : undefined;
  store.set(r.path, resolve(data, prev));
  notify();
}
export async function updateDoc(r, data) {
  if (!store.has(r.path)) throw Object.assign(new Error('not found'), { code: 'not-found' });
  const prev = store.get(r.path);
  const out = { ...prev };
  for (const [k, v] of Object.entries(data)) out[k] = resolve(v, prev[k]);
  store.set(r.path, out);
  notify();
}
export async function deleteDoc(r) { store.delete(r.path); notify(); }
export function onSnapshot(target, next) {
  const fire = () => {
    try {
      if (target.kind === 'doc') next(snapDoc(target.path));
      else next(runQuery(target.kind === 'col' ? { path: target.path, cs: [] } : target));
    } catch (e) { console.error('snapshot error', e); }
  };
  subs.add(fire);
  setTimeout(fire, 0);
  return () => subs.delete(fire);
}
export async function runTransaction(_db, fn) {
  const tx = {
    get: async (r) => snapDoc(r.path),
    set: (r, d, o) => { setDoc(r, d, o); return tx; },
    update: (r, d) => { updateDoc(r, d); return tx; },
  };
  return fn(tx);
}

// trợ giúp cho kiểm thử
window.__fake = {
  store, notify, Timestamp,
  put(path, data) { store.set(path, resolve(data)); notify(); },
};
