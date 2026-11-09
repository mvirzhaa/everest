import crypto from 'node:crypto';
import express from 'express';
import jwt from 'jsonwebtoken';
import { config } from '../config.js';
import { q, audit } from '../db.js';
import { SESSION_COOKIE, oidcDiscovery, requireAuth, signToken, upsertUser, verifyIdToken } from '../auth.js';
import { HttpError, bad, encrypt, h, reqStr } from '../util.js';

export const router = express.Router();

const cookieOpts = () => ({
  httpOnly: true,
  sameSite: 'lax',
  secure: config.publicUrl.startsWith('https://'),
  path: '/',
});

router.get('/api/auth/config', (req, res) => {
  res.json({ sso: Boolean(config.oidc.issuer), devLogin: config.devLogin, googleCalendar: Boolean(config.google.clientId) });
});

/** Aplikasi Android: kirim id_token hasil login SSO (AppAuth), dapat token sesi Everest. */
router.post(
  '/api/auth/oidc',
  h(async (req, res) => {
    const idToken = reqStr(req.body?.id_token, 'id_token', { max: 8000 });
    const claims = await verifyIdToken(idToken);
    const user = await upsertUser(claims);
    await audit(user.id, 'login', 'oidc');
    res.json({ token: signToken(user), user });
  }),
);

/** Login pengembangan (hanya jika DEV_LOGIN=true). Jangan aktifkan di produksi. */
router.post(
  '/api/auth/dev-login',
  h(async (req, res) => {
    if (!config.devLogin) throw new HttpError(404, 'Tidak tersedia');
    const email = reqStr(req.body?.email, 'email', { max: 200 }).toLowerCase();
    if (!/^[^@\s]+@[^@\s]+$/.test(email)) throw bad('email tidak valid');
    const name = req.body?.name ? reqStr(req.body.name, 'name', { max: 120 }) : email.split('@')[0];
    const role = req.body?.role;
    if (role && !['user', 'guard', 'admin'].includes(role)) throw bad('role tidak valid');
    const user = await upsertUser({ sub: null, email, name, role });
    const token = signToken(user);
    if (req.query.cookie) res.cookie(SESSION_COOKIE, token, { ...cookieOpts(), maxAge: 30 * 86400000 });
    res.json({ token, user });
  }),
);

// ---- Login web (dashboard) lewat SSO: authorization code + PKCE ----
const FLOW_COOKIE = 'everest_oidc_flow';

router.get(
  '/auth/web/login',
  h(async (req, res) => {
    if (!config.oidc.issuer || !config.oidc.webClientId) {
      return res.redirect(`${config.basePath}/dashboard/`);
    }
    const d = await oidcDiscovery();
    const verifier = crypto.randomBytes(32).toString('base64url');
    const state = crypto.randomBytes(16).toString('base64url');
    const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
    const flow = jwt.sign({ verifier, state }, config.jwtSecret, { expiresIn: '10m' });
    res.cookie(FLOW_COOKIE, flow, { ...cookieOpts(), maxAge: 600000 });
    const url = new URL(d.authorization_endpoint);
    url.search = new URLSearchParams({
      response_type: 'code',
      client_id: config.oidc.webClientId,
      redirect_uri: `${config.publicUrl}/auth/web/callback`,
      scope: config.oidc.scope,
      state,
      code_challenge: challenge,
      code_challenge_method: 'S256',
    }).toString();
    res.redirect(url.toString());
  }),
);

router.get(
  '/auth/web/callback',
  h(async (req, res) => {
    const d = await oidcDiscovery();
    let flow;
    try {
      flow = jwt.verify(req.cookies?.[FLOW_COOKIE] || '', config.jwtSecret);
    } catch {
      throw bad('Sesi login kedaluwarsa, ulangi login');
    }
    if (req.query.state !== flow.state) throw bad('State tidak cocok');
    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      code: String(req.query.code || ''),
      redirect_uri: `${config.publicUrl}/auth/web/callback`,
      client_id: config.oidc.webClientId,
      code_verifier: flow.verifier,
    });
    if (config.oidc.webClientSecret) body.set('client_secret', config.oidc.webClientSecret);
    const r = await fetch(d.token_endpoint, { method: 'POST', body });
    const tok = await r.json().catch(() => ({}));
    if (!r.ok || !tok.id_token) throw new HttpError(502, 'Gagal menukar kode SSO');
    const user = await upsertUser(await verifyIdToken(tok.id_token));
    await audit(user.id, 'login', 'web');
    res.clearCookie(FLOW_COOKIE, cookieOpts());
    res.cookie(SESSION_COOKIE, signToken(user), { ...cookieOpts(), maxAge: 30 * 86400000 });
    res.redirect(`${config.basePath}/dashboard/`);
  }),
);

router.post('/auth/web/logout', (req, res) => {
  res.clearCookie(SESSION_COOKIE, cookieOpts());
  res.json({ ok: true });
});

// ---- Tautan Google Calendar ----
/** Android mengirim server auth code dari Google AuthorizationClient (scope calendar.events, offline access). */
router.post(
  '/api/auth/google/link',
  requireAuth,
  h(async (req, res) => {
    if (!config.google.clientId || !config.google.clientSecret) throw new HttpError(503, 'Google Calendar belum dikonfigurasi di server');
    const code = reqStr(req.body?.auth_code, 'auth_code', { max: 2000 });
    const r = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        code,
        client_id: config.google.clientId,
        client_secret: config.google.clientSecret,
        redirect_uri: '',
      }),
    });
    const tok = await r.json().catch(() => ({}));
    if (!r.ok || !tok.refresh_token) throw new HttpError(502, `Google menolak kode otorisasi${tok.error ? ` (${tok.error})` : ''}`);
    await q('UPDATE users SET google_refresh_token_enc = $2 WHERE id = $1', [req.user.id, encrypt(tok.refresh_token)]);
    await audit(req.user.id, 'google_link', null);
    res.json({ linked: true });
  }),
);

router.delete(
  '/api/auth/google/link',
  requireAuth,
  h(async (req, res) => {
    await q('UPDATE users SET google_refresh_token_enc = NULL WHERE id = $1', [req.user.id]);
    res.json({ linked: false });
  }),
);
