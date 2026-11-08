import { config } from './config.js';
import { one } from './db.js';
import { HttpError, decrypt } from './util.js';

const API = 'https://www.googleapis.com/calendar/v3/calendars/primary/events';

async function accessToken(userId) {
  const u = await one('SELECT google_refresh_token_enc FROM users WHERE id = $1', [userId]);
  if (!u?.google_refresh_token_enc) throw new HttpError(409, 'Akun Google belum dihubungkan. Hubungkan di menu Profil.');
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    body: new URLSearchParams({
      grant_type: 'refresh_token',
      refresh_token: decrypt(u.google_refresh_token_enc),
      client_id: config.google.clientId,
      client_secret: config.google.clientSecret,
    }),
  });
  const t = await r.json().catch(() => ({}));
  if (!r.ok) {
    if (t.error === 'invalid_grant') throw new HttpError(409, 'Izin Google Calendar sudah dicabut. Hubungkan ulang di menu Profil.');
    throw new HttpError(502, 'Gagal menghubungi Google');
  }
  return t.access_token;
}

/** Buat event. `start`/`end` berupa ISO 8601 dengan offset, mis. 2026-11-07T09:00:00+07:00 */
export async function createEvent(userId, { id, title, start, end, location, description, timeZone = 'Asia/Jakarta' }) {
  const s = new Date(start);
  if (Number.isNaN(s.getTime())) throw new HttpError(400, 'Waktu mulai tidak valid');
  const e = end ? new Date(end) : new Date(s.getTime() + 3600000);
  if (Number.isNaN(e.getTime()) || e <= s) throw new HttpError(400, 'Waktu selesai harus setelah waktu mulai');
  const token = await accessToken(userId);
  const r = await fetch(API, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      // ID dari HP (UUID tanpa tanda hubung = base32hex yang sah) → kirim ulang dari antrean tidak menggandakan event
      id: id ? id.replace(/-/g, '').toLowerCase() : undefined,
      summary: title,
      location: location || undefined,
      description: description || 'Dibuat oleh Farhan (Everest)',
      start: { dateTime: s.toISOString(), timeZone },
      end: { dateTime: e.toISOString(), timeZone },
    }),
  });
  let body = await r.json().catch(() => ({}));
  if (r.status === 409 && id) {
    const g = await fetch(`${API}/${id.replace(/-/g, '').toLowerCase()}`, { headers: { authorization: `Bearer ${token}` } });
    body = await g.json().catch(() => ({}));
    if (!g.ok) throw new HttpError(502, 'Gagal membaca event yang sudah ada');
  } else if (!r.ok) throw new HttpError(502, `Google Calendar menolak: ${body.error?.message || r.status}`);
  return { id: body.id, html_link: body.htmlLink, title: body.summary, start: body.start?.dateTime, end: body.end?.dateTime };
}

export async function listEvents(userId, { from, to }) {
  const token = await accessToken(userId);
  const url = new URL(API);
  url.search = new URLSearchParams({
    timeMin: new Date(from).toISOString(),
    timeMax: new Date(to).toISOString(),
    singleEvents: 'true',
    orderBy: 'startTime',
    maxResults: '50',
  }).toString();
  const r = await fetch(url, { headers: { authorization: `Bearer ${token}` } });
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw new HttpError(502, `Google Calendar menolak: ${body.error?.message || r.status}`);
  return (body.items || []).map((ev) => ({
    id: ev.id,
    title: ev.summary || '(tanpa judul)',
    start: ev.start?.dateTime || ev.start?.date,
    end: ev.end?.dateTime || ev.end?.date,
    location: ev.location || null,
  }));
}
