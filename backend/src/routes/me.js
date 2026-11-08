import express from 'express';
import { q, one, audit } from '../db.js';
import { config } from '../config.js';
import { requireAuth } from '../auth.js';
import { bad, h, notFound, optStr, reqStr, reqTime, reqUuid } from '../util.js';

export const router = express.Router();
router.use('/api/me', requireAuth);
router.use('/api/contacts', requireAuth);
router.use('/api/devices', requireAuth);

router.get(
  '/api/me',
  h(async (req, res) => {
    const u = await one(
      `SELECT u.id, u.email, u.name, u.phone, u.role, (u.google_refresh_token_enc IS NOT NULL) AS google_linked,
              COALESCE(g.on_duty, false) AS on_duty, u.consent_version, u.consent_at
         FROM users u LEFT JOIN guard_duty g ON g.user_id = u.id WHERE u.id = $1`,
      [req.user.id],
    );
    res.json({ ...u, privacy_version: config.privacyVersion });
  }),
);

router.patch(
  '/api/me',
  h(async (req, res) => {
    const phone = optStr(req.body?.phone, 'phone', 30);
    if (phone && !/^\+?[0-9 -]{6,20}$/.test(phone)) throw bad('Nomor HP tidak valid');
    const u = await one('UPDATE users SET phone = $2 WHERE id = $1 RETURNING id, email, name, phone, role', [req.user.id, phone]);
    res.json(u);
  }),
);

const CONSENT_SCOPES = new Set(['rekam', 'lokasi', 'panic', 'laporan', 'farhan_cloud', 'kontak']);

/**
 * Catat persetujuan kebijakan privasi (UU PDP). Persetujuan diberikan di HP (bisa saat offline),
 * lalu dikirim lewat antrean; agreed_at adalah waktu asli di HP.
 */
router.post(
  '/api/me/consent',
  h(async (req, res) => {
    const version = reqStr(req.body?.version, 'version', { max: 40 });
    const agreedAt = reqTime(req.body?.agreed_at, 'agreed_at');
    const scopes = Array.isArray(req.body?.scopes) ? [...new Set(req.body.scopes.map(String).filter((s) => CONSENT_SCOPES.has(s)))] : [];
    await q('INSERT INTO consent_log (user_id, version, scopes, agreed_at) VALUES ($1,$2,$3,$4)', [req.user.id, version, scopes, agreedAt]);
    await q(
      `UPDATE users SET consent_version = $2, consent_at = $3
        WHERE id = $1 AND (consent_at IS NULL OR consent_at <= $3)`,
      [req.user.id, version, agreedAt],
    );
    await audit(req.user.id, 'consent', req.user.id, { version, scopes });
    res.json({ ok: true, version, current: config.privacyVersion });
  }),
);

/** Daftarkan token FCM HP ini. */
router.post(
  '/api/devices',
  h(async (req, res) => {
    const token = reqStr(req.body?.fcm_token, 'fcm_token', { max: 4096 });
    const model = optStr(req.body?.model, 'model', 120);
    await q(
      `INSERT INTO devices (user_id, fcm_token, model) VALUES ($1,$2,$3)
       ON CONFLICT (fcm_token) DO UPDATE SET user_id = EXCLUDED.user_id, model = EXCLUDED.model, last_seen_at = now()`,
      [req.user.id, token, model],
    );
    res.json({ ok: true });
  }),
);

router.delete(
  '/api/devices',
  h(async (req, res) => {
    await q('DELETE FROM devices WHERE user_id = $1 AND fcm_token = $2', [req.user.id, String(req.body?.fcm_token || '')]);
    res.json({ ok: true });
  }),
);

// ---- Kontak darurat ----
router.get(
  '/api/contacts',
  h(async (req, res) => {
    const { rows } = await q(
      `SELECT c.id, c.name, c.phone, c.contact_user_id, u.email AS contact_email
         FROM emergency_contacts c LEFT JOIN users u ON u.id = c.contact_user_id
        WHERE c.user_id = $1 ORDER BY c.created_at`,
      [req.user.id],
    );
    res.json(rows);
  }),
);

/**
 * Tambah kontak darurat. Isi `email` pengguna Everest (agar menerima push)
 * dan/atau `phone` (untuk SMS cadangan saat tidak ada internet).
 */
router.post(
  '/api/contacts',
  h(async (req, res) => {
    const email = optStr(req.body?.email, 'email', 200)?.toLowerCase() || null;
    const phone = optStr(req.body?.phone, 'phone', 30);
    if (!email && !phone) throw bad('Isi email pengguna Everest atau nomor HP');
    if (phone && !/^\+?[0-9 -]{6,20}$/.test(phone)) throw bad('Nomor HP tidak valid');
    let contactUser = null;
    if (email) {
      contactUser = await one('SELECT id, name, phone FROM users WHERE email = $1', [email]);
      if (!contactUser) throw notFound('Pengguna dengan email itu belum terdaftar di Everest. Minta dia login sekali dulu, atau isi nomor HP saja.');
      if (contactUser.id === req.user.id) throw bad('Tidak bisa menambahkan diri sendiri');
    }
    const name = optStr(req.body?.name, 'name', 120) || contactUser?.name || phone;
    const row = await one(
      `INSERT INTO emergency_contacts (user_id, contact_user_id, name, phone)
       VALUES ($1,$2,$3,$4)
       ON CONFLICT (user_id, contact_user_id) DO UPDATE SET name = EXCLUDED.name, phone = COALESCE(EXCLUDED.phone, emergency_contacts.phone)
       RETURNING id, name, phone, contact_user_id`,
      [req.user.id, contactUser?.id || null, name, phone || contactUser?.phone || null],
    );
    res.status(201).json({ ...row, contact_email: email });
  }),
);

router.delete(
  '/api/contacts/:id',
  h(async (req, res) => {
    const id = reqUuid(req.params.id, 'id');
    const r = await q('DELETE FROM emergency_contacts WHERE id = $1 AND user_id = $2', [id, req.user.id]);
    if (!r.rowCount) throw notFound();
    res.json({ ok: true });
  }),
);
