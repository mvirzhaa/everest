import fs from 'node:fs';
import path from 'node:path';
import express from 'express';
import multer from 'multer';
import sharp from 'sharp';
import { config } from '../config.js';
import { q, one, tx, audit } from '../db.js';
import { requireAuth, signMediaToken, verifyMediaToken } from '../auth.js';
import { emit } from '../notify.js';
import { bad, forbidden, h, notFound, reqLatLng, reqStr, reqTime, reqUuid } from '../util.js';

export const router = express.Router();

const CATEGORIES = new Set(['fasilitas', 'kebersihan', 'keamanan', 'lingkungan', 'lainnya']);
const reportDir = path.join(config.uploadDir, 'reports');
fs.mkdirSync(reportDir, { recursive: true });

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 12 * 1024 * 1024, files: 1, fields: 5 },
  fileFilter: (req, file, cb) => cb(null, /^image\/(jpeg|png|webp|heic|heif)$/.test(file.mimetype)),
});

export function withPhotoUrl(r) {
  const p = `/media/reports/${r.id}.jpg`;
  const { photo_path, ...rest } = r;
  return { ...rest, photo_url: `${config.publicUrl}${p}?t=${signMediaToken(p)}` };
}

/**
 * Kirim laporan: multipart dengan field `photo` (file) dan `meta` (JSON:
 * {id, description, category, lat, lng, created_at}). Kirim ulang id yang sama aman (idempotent).
 */
router.post(
  '/api/reports',
  requireAuth,
  upload.single('photo'),
  h(async (req, res) => {
    let meta;
    try {
      meta = JSON.parse(req.body?.meta || '{}');
    } catch {
      throw bad('meta harus JSON');
    }
    const id = reqUuid(meta.id, 'id');
    const existing = await one('SELECT * FROM reports WHERE id = $1', [id]);
    if (existing) {
      if (existing.user_id !== req.user.id) throw forbidden();
      return res.json({ report: withPhotoUrl(existing), duplicate: true });
    }
    if (!req.file) throw bad('Foto wajib dilampirkan (jpeg/png/webp)');
    const description = reqStr(meta.description, 'Deskripsi', { max: 4000 });
    const category = CATEGORIES.has(meta.category) ? meta.category : 'lainnya';
    const [lat, lng] = reqLatLng(meta.lat, meta.lng);
    const createdAt = reqTime(meta.created_at, 'created_at');

    // Normalisasi: putar sesuai orientasi, perkecil, simpan JPEG tanpa metadata EXIF
    let jpeg;
    try {
      jpeg = await sharp(req.file.buffer).rotate().resize({ width: 1600, height: 1600, fit: 'inside', withoutEnlargement: true }).jpeg({ quality: 82 }).toBuffer();
    } catch {
      throw bad('File foto rusak atau tidak didukung');
    }
    const file = `${id}.jpg`;
    fs.writeFileSync(path.join(reportDir, file), jpeg);

    const report = await tx(async (c) => {
      const r = (
        await c.query(
          `INSERT INTO reports (id, user_id, photo_path, description, category, lat, lng, created_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8) RETURNING *`,
          [id, req.user.id, file, description, category, lat, lng, createdAt],
        )
      ).rows[0];
      await c.query(`INSERT INTO report_status_log (report_id, status, actor_id) VALUES ($1,'baru',$2)`, [id, req.user.id]);
      return r;
    });
    emit('admins', 'report', { id, user_name: req.user.name, category, description: description.slice(0, 140), created_at: createdAt });
    res.status(201).json({ report: withPhotoUrl(report) });
  }),
);

router.get(
  '/api/reports',
  requireAuth,
  h(async (req, res) => {
    const { rows } = await q('SELECT * FROM reports WHERE user_id = $1 ORDER BY created_at DESC LIMIT 200', [req.user.id]);
    res.json(rows.map(withPhotoUrl));
  }),
);

router.get(
  '/api/reports/:id',
  requireAuth,
  h(async (req, res) => {
    const id = reqUuid(req.params.id, 'id');
    const r = await one('SELECT r.*, u.name AS user_name FROM reports r JOIN users u ON u.id = r.user_id WHERE r.id = $1', [id]);
    if (!r) throw notFound();
    if (r.user_id !== req.user.id && req.user.role !== 'admin') throw forbidden();
    const log = (
      await q(
        `SELECT l.status, l.note, l.created_at, u.name AS actor_name
           FROM report_status_log l LEFT JOIN users u ON u.id = l.actor_id WHERE l.report_id = $1 ORDER BY l.created_at`,
        [id],
      )
    ).rows;
    if (req.user.role === 'admin' && r.user_id !== req.user.id) await audit(req.user.id, 'report_view', id);
    res.json({ ...withPhotoUrl(r), history: log });
  }),
);

/** Foto laporan: hanya lewat URL bertanda tangan berumur 10 menit. */
router.get('/media/reports/:file', (req, res) => {
  const file = path.basename(req.params.file);
  if (!/^[0-9a-f-]{36}\.jpg$/i.test(file)) return res.status(404).end();
  if (!verifyMediaToken(String(req.query.t || ''), `/media/reports/${file}`)) return res.status(403).end();
  res.set('Cache-Control', 'private, max-age=600');
  res.sendFile(path.join(reportDir, file), (err) => err && !res.headersSent && res.status(404).end());
});

export const REPORT_STATUSES = new Set(['baru', 'diproses', 'selesai', 'ditolak']);
