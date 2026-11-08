import jwt from 'jsonwebtoken';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { config } from './config.js';
import { one } from './db.js';
import { HttpError, forbidden } from './util.js';

export const SESSION_COOKIE = 'everest_session';

export function signToken(user) {
  return jwt.sign({ sub: user.id, role: user.role }, config.jwtSecret, { expiresIn: config.jwtTtl, issuer: 'everest' });
}

/** Token pendek untuk URL media (foto laporan) agar <img> bisa memuatnya tanpa header Authorization. */
export function signMediaToken(path) {
  return jwt.sign({ p: path }, config.jwtSecret, { expiresIn: '10m', issuer: 'everest-media' });
}

export function verifyMediaToken(token, path) {
  try {
    const p = jwt.verify(token, config.jwtSecret, { issuer: 'everest-media' });
    return p.p === path;
  } catch {
    return false;
  }
}

export async function userFromToken(token) {
  let payload;
  try {
    payload = jwt.verify(token, config.jwtSecret, { issuer: 'everest' });
  } catch {
    return null;
  }
  // Ambil peran terbaru dari DB (peran bisa diubah admin setelah token terbit)
  return one('SELECT id, email, name, phone, role FROM users WHERE id = $1', [payload.sub]);
}

export async function requireAuth(req, res, next) {
  try {
    const header = req.get('authorization') || '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : req.cookies?.[SESSION_COOKIE];
    if (!token) throw new HttpError(401, 'Belum login');
    const user = await userFromToken(token);
    if (!user) throw new HttpError(401, 'Sesi tidak valid atau kedaluwarsa');
    req.user = user;
    next();
  } catch (e) {
    next(e);
  }
}

export const requireRole = (...roles) => (req, res, next) =>
  roles.includes(req.user?.role) ? next() : next(forbidden());

// ---- OIDC (SSO E-Portal) ----
let discovery = null;
let jwks = null;

export async function oidcDiscovery() {
  if (!config.oidc.issuer) throw new HttpError(503, 'SSO belum dikonfigurasi di server (OIDC_ISSUER)');
  if (!discovery) {
    const url = `${config.oidc.issuer.replace(/\/$/, '')}/.well-known/openid-configuration`;
    const r = await fetch(url);
    if (!r.ok) throw new HttpError(502, `Gagal membaca konfigurasi SSO (${r.status})`);
    discovery = await r.json();
    jwks = createRemoteJWKSet(new URL(discovery.jwks_uri));
  }
  return discovery;
}

/** Verifikasi id_token dari SSO, kembalikan klaim {sub, email, name}. */
export async function verifyIdToken(idToken) {
  const d = await oidcDiscovery();
  try {
    const { payload } = await jwtVerify(idToken, jwks, {
      issuer: d.issuer,
      audience: config.oidc.audiences.length ? config.oidc.audiences : undefined,
    });
    if (!payload.email) throw new Error('klaim email tidak ada');
    return { sub: String(payload.sub), email: String(payload.email).toLowerCase(), name: String(payload.name || payload.email) };
  } catch (e) {
    throw new HttpError(401, `Token SSO tidak valid: ${e.message}`);
  }
}

/** Buat atau perbarui pengguna dari identitas SSO. Email di ADMIN_EMAILS otomatis jadi admin. */
export async function upsertUser({ sub, email, name, role }) {
  const wantAdmin = config.adminEmails.includes(email);
  const user = await one(
    `INSERT INTO users (sso_sub, email, name, role)
     VALUES ($1, $2, $3, $4)
     ON CONFLICT (email) DO UPDATE SET
       sso_sub = COALESCE(EXCLUDED.sso_sub, users.sso_sub),
       name = EXCLUDED.name,
       role = CASE WHEN $5 THEN 'admin' WHEN $6::text IS NOT NULL THEN $6 ELSE users.role END
     RETURNING id, email, name, phone, role`,
    [sub || null, email, name, wantAdmin ? 'admin' : role || 'user', wantAdmin, role || null],
  );
  return user;
}
