import fs from 'node:fs';
import path from 'node:path';
import express from 'express';
import multer from 'multer';
import { config } from '../config.js';
import { q, tx } from '../db.js';
import { requireAuth } from '../auth.js';
import { bad, forbidden, h, notFound, optNum, optStr, reqTime, reqUuid } from '../util.js';

export const router = express.Router();
router.use('/api/notes', requireAuth);

const TYPES = new Set(['rapat', 'kuliah', 'pribadi', 'lapangan']);
const PAGE = 200;

function parseNote(n) {
  if (!n || typeof n !== 'object') throw bad('catatan tidak valid');
  const note = {
    id: reqUuid(n.id, 'id'),
    title: optStr(n.title, 'title', 300) || '',
    type: TYPES.has(n.type) ? n.type : 'pribadi',
    started_at: reqTime(n.started_at, 'started_at'),
    duration_s: Math.round(optNum(n.duration_s, 'duration_s', { min: 0, max: 86400 * 2 }) || 0),
    summary: optStr(n.summary, 'summary', 20000),
    keywords: Array.isArray(n.keywords) ? n.keywords.slice(0, 30).map((k) => String(k).slice(0, 60)) : [],
    updated_at: reqTime(n.updated_at, 'updated_at'),
    deleted: Boolean(n.deleted),
    segments: [],
  };
  if (!note.deleted) {
    if (!Array.isArray(n.segments)) throw bad('segments harus array');
    if (n.segments.length > 20000) throw bad('segmen terlalu banyak');
    note.segments = n.segments.map((s) => ({
      id: reqUuid(s.id, 'segment.id'),
      start_ms: Math.max(0, Math.round(optNum(s.start_ms, 'start_ms', { min: 0 }) || 0)),
      end_ms: Math.max(0, Math.round(optNum(s.end_ms, 'end_ms', { min: 0 }) || 0)),
      text: String(s.text ?? '').slice(0, 5000),
    }));
  }
  return note;
}

/**
 * Sinkronisasi dua arah, last-write-wins berdasarkan updated_at dari HP.
 * Body: { since: ISO|null, notes: [...] } → { accepted: [id], notes: [...perubahan server], next_since, has_more }
 */
router.post(
  '/api/notes/sync',
  h(async (req, res) => {
    const incoming = Array.isArray(req.body?.notes) ? req.body.notes : [];
    if (incoming.length > 100) throw bad('Maksimal 100 catatan per sinkron');
    const parsed = incoming.map(parseNote);
    const accepted = [];

    await tx(async (c) => {
      for (const n of parsed) {
        const existing = (await c.query('SELECT user_id, updated_at FROM notes WHERE id = $1 FOR UPDATE', [n.id])).rows[0];
        if (existing && existing.user_id !== req.user.id) throw forbidden('ID catatan dipakai pengguna lain');
        if (existing && new Date(existing.updated_at) >= new Date(n.updated_at)) {
          accepted.push(n.id); // versi server sama/lebih baru: tetap dianggap terkirim
          continue;
        }
        await c.query(
          `INSERT INTO notes (id, user_id, title, type, started_at, duration_s, summary, keywords, updated_at, deleted_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9, CASE WHEN $10 THEN now() ELSE NULL END)
           ON CONFLICT (id) DO UPDATE SET title=EXCLUDED.title, type=EXCLUDED.type, started_at=EXCLUDED.started_at,
             duration_s=EXCLUDED.duration_s, summary=EXCLUDED.summary, keywords=EXCLUDED.keywords,
             updated_at=EXCLUDED.updated_at, deleted_at=EXCLUDED.deleted_at`,
          [n.id, req.user.id, n.title, n.type, n.started_at, n.duration_s, n.summary, n.keywords, n.updated_at, n.deleted],
        );
        await c.query('DELETE FROM note_segments WHERE note_id = $1', [n.id]);
        if (n.deleted) {
          await c.query('UPDATE notes SET summary = NULL, keywords = $2, title = $3 WHERE id = $1', [n.id, [], '']);
        } else if (n.segments.length) {
          // Sisipkan bertahap agar jumlah parameter tetap di bawah batas PostgreSQL
          for (let i = 0; i < n.segments.length; i += 1000) {
            const chunk = n.segments.slice(i, i + 1000);
            const vals = [];
            const params = [];
            chunk.forEach((s, j) => {
              const b = j * 5;
              vals.push(`($${b + 1},$${b + 2},$${b + 3},$${b + 4},$${b + 5})`);
              params.push(s.id, n.id, s.start_ms, s.end_ms, s.text);
            });
            await c.query(
              `INSERT INTO note_segments (id, note_id, start_ms, end_ms, text) VALUES ${vals.join(',')}
               ON CONFLICT (id) DO UPDATE SET note_id=EXCLUDED.note_id, start_ms=EXCLUDED.start_ms, end_ms=EXCLUDED.end_ms, text=EXCLUDED.text`,
              params,
            );
          }
        }
        accepted.push(n.id);
      }
    });

    // Perubahan dari server (mis. dari HP lain milik pengguna yang sama)
    const since = req.body?.since ? reqTime(req.body.since, 'since') : '1970-01-01T00:00:00Z';
    const { rows } = await q(
      `SELECT id, title, type, started_at, duration_s, summary, keywords, updated_at, (deleted_at IS NOT NULL) AS deleted,
              (audio_path IS NOT NULL) AS has_audio
         FROM notes WHERE user_id = $1 AND updated_at > $2 ORDER BY updated_at LIMIT $3`,
      [req.user.id, since, PAGE + 1],
    );
    const hasMore = rows.length > PAGE;
    const page = rows.slice(0, PAGE);
    if (page.length) {
      const segs = await q(
        'SELECT id, note_id, start_ms, end_ms, text FROM note_segments WHERE note_id = ANY($1::uuid[]) ORDER BY note_id, start_ms',
        [page.map((r) => r.id)],
      );
      const byNote = new Map();
      for (const s of segs.rows) {
        if (!byNote.has(s.note_id)) byNote.set(s.note_id, []);
        byNote.get(s.note_id).push({ id: s.id, start_ms: s.start_ms, end_ms: s.end_ms, text: s.text });
      }
      page.forEach((n) => (n.segments = byNote.get(n.id) || []));
    }
    res.json({
      accepted,
      notes: page,
      next_since: page.length ? page[page.length - 1].updated_at : since,
      has_more: hasMore,
      server_time: new Date().toISOString(),
    });
  }),
);

/** Pencarian teks penuh atas transkrip yang sudah tersinkron (dipakai Farhan jalur cloud dan web). */
export async function searchNotes(userId, { query, from, to, limit = 8 }) {
  const params = [userId];
  const where = ['n.user_id = $1', 'n.deleted_at IS NULL'];
  if (from) {
    params.push(from);
    where.push(`n.started_at >= $${params.length}`);
  }
  if (to) {
    params.push(to);
    where.push(`n.started_at < $${params.length}`);
  }
  let rank = '0';
  if (query) {
    params.push(query);
    where.push(`s.tsv @@ websearch_to_tsquery('simple', $${params.length})`);
    rank = `ts_rank(s.tsv, websearch_to_tsquery('simple', $${params.length}))`;
  }
  params.push(Math.min(limit, 30));
  const { rows } = await q(
    `SELECT n.id AS note_id, n.title, n.started_at, s.start_ms, s.text, ${rank} AS rank
       FROM notes n JOIN note_segments s ON s.note_id = n.id
      WHERE ${where.join(' AND ')}
      ORDER BY rank DESC, n.started_at DESC, s.start_ms
      LIMIT $${params.length}`,
    params,
  );
  return rows;
}

router.get(
  '/api/notes/search',
  h(async (req, res) => {
    const from = req.query.from ? reqTime(req.query.from, 'from') : null;
    const to = req.query.to ? new Date(String(req.query.to)).toISOString() : null;
    res.json(await searchNotes(req.user.id, { query: optStr(req.query.q, 'q', 200), from, to, limit: 20 }));
  }),
);

// ---- Unggah audio (opsional, atas pilihan pengguna) ----
const audioDir = path.join(config.uploadDir, 'audio');
fs.mkdirSync(audioDir, { recursive: true });
const audioUpload = multer({
  storage: multer.diskStorage({
    destination: audioDir,
    filename: (req, file, cb) => cb(null, `${req.params.id}.tmp`),
  }),
  limits: { fileSize: 300 * 1024 * 1024, files: 1 },
});

router.post(
  '/api/notes/:id/audio',
  h(async (req, res, next) => {
    const id = reqUuid(req.params.id, 'id');
    const note = (await q('SELECT user_id FROM notes WHERE id = $1', [id])).rows[0];
    if (!note) throw notFound('Catatan belum tersinkron');
    if (note.user_id !== req.user.id) throw forbidden();
    next();
  }),
  audioUpload.single('audio'),
  h(async (req, res) => {
    if (!req.file) throw bad('file audio wajib');
    const ext = /\.(m4a|aac|ogg|opus|wav)$/i.exec(req.file.originalname)?.[1]?.toLowerCase() || 'm4a';
    const final = path.join(audioDir, `${req.params.id}.${ext}`);
    fs.renameSync(req.file.path, final);
    await q('UPDATE notes SET audio_path = $2 WHERE id = $1', [req.params.id, path.basename(final)]);
    res.json({ ok: true });
  }),
);
