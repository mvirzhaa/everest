import crypto from 'node:crypto';
import { config } from './config.js';

export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export const bad = (msg) => new HttpError(400, msg);
export const forbidden = (msg = 'Tidak diizinkan') => new HttpError(403, msg);
export const notFound = (msg = 'Tidak ditemukan') => new HttpError(404, msg);

/** Bungkus handler async agar error diteruskan ke middleware error Express. */
export const h = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUuid = (v) => typeof v === 'string' && UUID_RE.test(v);

export function reqUuid(v, field) {
  if (!isUuid(v)) throw bad(`${field} harus UUID`);
  return v.toLowerCase();
}

export function reqStr(v, field, { max = 10000, min = 1 } = {}) {
  if (typeof v !== 'string') throw bad(`${field} wajib diisi`);
  const s = v.trim();
  if (s.length < min) throw bad(`${field} wajib diisi`);
  if (s.length > max) throw bad(`${field} terlalu panjang (maks ${max})`);
  return s;
}

export function optStr(v, field, max = 10000) {
  if (v === undefined || v === null || v === '') return null;
  return reqStr(v, field, { max, min: 0 });
}

export function reqTime(v, field) {
  const t = typeof v === 'number' ? new Date(v) : new Date(String(v ?? ''));
  if (Number.isNaN(t.getTime())) throw bad(`${field} bukan waktu yang valid`);
  // Tolak waktu lebih dari 1 hari di masa depan (jam HP ngaco)
  if (t.getTime() > Date.now() + 86400000) throw bad(`${field} berada di masa depan`);
  return t.toISOString();
}

export function optNum(v, field, { min = -Infinity, max = Infinity } = {}) {
  if (v === undefined || v === null || v === '') return null;
  const n = Number(v);
  if (!Number.isFinite(n) || n < min || n > max) throw bad(`${field} tidak valid`);
  return n;
}

export function reqLatLng(lat, lng) {
  const a = optNum(lat, 'lat', { min: -90, max: 90 });
  const b = optNum(lng, 'lng', { min: -180, max: 180 });
  if ((a === null) !== (b === null)) throw bad('lat dan lng harus diisi bersama');
  return [a, b];
}

export const randomToken = (bytes = 24) => crypto.randomBytes(bytes).toString('base64url');

// ---- Enkripsi AES-256-GCM untuk refresh token Google ----
function key() {
  if (!/^[0-9a-f]{64}$/i.test(config.encKey)) throw new HttpError(503, 'ENC_KEY server belum dikonfigurasi');
  return Buffer.from(config.encKey, 'hex');
}

export function encrypt(plain) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key(), iv);
  const enc = Buffer.concat([c.update(plain, 'utf8'), c.final()]);
  return [iv, c.getAuthTag(), enc].map((b) => b.toString('base64url')).join('.');
}

export function decrypt(token) {
  const [iv, tag, enc] = token.split('.').map((s) => Buffer.from(s, 'base64url'));
  const d = crypto.createDecipheriv('aes-256-gcm', key(), iv);
  d.setAuthTag(tag);
  return Buffer.concat([d.update(enc), d.final()]).toString('utf8');
}
