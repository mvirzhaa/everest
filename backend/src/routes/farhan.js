import express from 'express';
import { config } from '../config.js';
import { one } from '../db.js';
import { requireAuth } from '../auth.js';
import { createEvent, listEvents } from '../calendar.js';
import { llmEnabled, runFarhan } from '../llm.js';
import { searchNotes } from './notes.js';
import { HttpError, bad, h, isUuid, optStr, reqStr } from '../util.js';

export const router = express.Router();
router.use(['/api/farhan', '/api/calendar'], requireAuth);

function nowIn(tz) {
  try {
    return new Intl.DateTimeFormat('id-ID', { timeZone: tz, dateStyle: 'full', timeStyle: 'short' }).format(new Date());
  } catch {
    return new Date().toISOString();
  }
}

function offsetOf(tz) {
  try {
    const part = new Intl.DateTimeFormat('en-US', { timeZone: tz, timeZoneName: 'longOffset' }).formatToParts(new Date()).find((p) => p.type === 'timeZoneName');
    return part?.value.replace('GMT', '') || '+07:00';
  } catch {
    return '+07:00';
  }
}

router.get('/api/farhan/status', (req, res) => res.json({ cloud: llmEnabled() }));

/**
 * Farhan jalur cloud. Body: { question, tz?, local_context?: [{title, date, text}] }
 * Balasan: { answer, action: {type:'buat_jadwal', ...} | null, sources: [...] }
 */
router.post(
  '/api/farhan/ask',
  h(async (req, res) => {
    const question = reqStr(req.body?.question, 'question', { max: 2000 });
    const tz = optStr(req.body?.tz, 'tz', 60) || 'Asia/Jakarta';
    const local = Array.isArray(req.body?.local_context) ? req.body.local_context.slice(0, 20) : [];

    const usage = await one(
      `INSERT INTO farhan_usage (user_id, day, requests) VALUES ($1, CURRENT_DATE, 1)
       ON CONFLICT (user_id, day) DO UPDATE SET requests = farhan_usage.requests + 1 RETURNING requests`,
      [req.user.id],
    );
    if (usage.requests > config.llm.dailyQuota) throw new HttpError(429, `Kuota Farhan hari ini habis (${config.llm.dailyQuota} pertanyaan). Coba lagi besok.`);

    let action = null;
    const sources = [];
    const runTool = async (name, input) => {
      try {
        if (name === 'cari_catatan') {
          const rows = await searchNotes(req.user.id, {
            query: input.kata_kunci ? String(input.kata_kunci).slice(0, 200) : null,
            from: input.dari ? new Date(input.dari).toISOString() : null,
            to: input.sampai ? new Date(input.sampai).toISOString() : null,
            limit: 12,
          });
          rows.forEach((r) => sources.push({ note_id: r.note_id, title: r.title, start_ms: r.start_ms, started_at: r.started_at }));
          return { hasil: rows.map((r) => ({ judul: r.title, waktu_rekaman: r.started_at, detik: Math.round(r.start_ms / 1000), teks: r.text })) };
        }
        if (name === 'lihat_jadwal') return { jadwal: await listEvents(req.user.id, { from: input.dari, to: input.sampai }) };
        if (name === 'usulkan_jadwal') {
          action = {
            type: 'buat_jadwal',
            title: String(input.judul || '').slice(0, 200),
            start: String(input.mulai || ''),
            end: input.selesai ? String(input.selesai) : null,
            location: input.lokasi ? String(input.lokasi).slice(0, 200) : null,
            description: input.catatan ? String(input.catatan).slice(0, 1000) : null,
          };
          return { status: 'menunggu konfirmasi pengguna di aplikasi' };
        }
        return { error: `alat ${name} tidak dikenal` };
      } catch (e) {
        return { error: e.message };
      }
    };

    const contextBlock = local.length
      ? `\n\nPotongan catatan lokal yang sudah ditemukan HP (mungkin belum tersinkron):\n${local
          .map((c) => `- [${String(c.date || '').slice(0, 30)}] ${String(c.title || '').slice(0, 120)}: ${String(c.text || '').slice(0, 800)}`)
          .join('\n')}`
      : '';
    const system = `Kamu adalah Farhan, asisten suara di aplikasi Everest milik Universitas Ibn Khaldun Bogor.
Pengguna bernama ${req.user.name}. Sekarang ${nowIn(tz)} (zona ${tz}, offset ${offsetOf(tz)}).
Aturan:
- Jawab dalam bahasa Indonesia yang singkat dan alami: 1–2 kalimat pertama harus langsung menjawab karena akan diucapkan.
- Untuk pertanyaan tentang isi rekaman, gunakan alat cari_catatan dan jawab hanya dari hasilnya. Jika tidak ada, katakan terus terang.
- Untuk permintaan menjadwalkan, gunakan usulkan_jadwal dengan waktu ISO 8601 berofset ${offsetOf(tz)}, lalu katakan bahwa jadwal menunggu konfirmasi.
- Jangan mengarang isi rekaman, nama, atau waktu.`;

    const answer = await runFarhan({ system, question: question + contextBlock, runTool });
    const uniq = [...new Map(sources.map((s) => [`${s.note_id}:${s.start_ms}`, s])).values()].slice(0, 8);
    res.json({ answer: answer || 'Maaf, saya belum bisa menjawab itu.', action, sources: uniq, remaining: Math.max(0, config.llm.dailyQuota - usage.requests) });
  }),
);

// Dipakai HP setelah pengguna mengonfirmasi usulan jadwal (juga dari antrean offline)
router.post(
  '/api/calendar/events',
  h(async (req, res) => {
    const title = reqStr(req.body?.title, 'title', { max: 200 });
    const start = reqStr(req.body?.start, 'start', { max: 40 });
    if (req.body?.end && typeof req.body.end !== 'string') throw bad('end tidak valid');
    const ev = await createEvent(req.user.id, {
      id: isUuid(req.body?.id) ? req.body.id : null,
      title,
      start,
      end: req.body?.end || null,
      location: optStr(req.body?.location, 'location', 200),
      description: optStr(req.body?.description, 'description', 1000),
      timeZone: optStr(req.body?.tz, 'tz', 60) || 'Asia/Jakarta',
    });
    res.status(201).json(ev);
  }),
);

router.get(
  '/api/calendar/events',
  h(async (req, res) => {
    const from = String(req.query.from || new Date().toISOString());
    const to = String(req.query.to || new Date(Date.now() + 7 * 86400000).toISOString());
    if (Number.isNaN(Date.parse(from)) || Number.isNaN(Date.parse(to))) throw bad('rentang waktu tidak valid');
    res.json(await listEvents(req.user.id, { from, to }));
  }),
);
