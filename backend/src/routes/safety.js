import express from 'express';
import { config } from '../config.js';
import { q, one, tx, audit } from '../db.js';
import { requireAuth, requireRole } from '../auth.js';
import { emit, pushLater } from '../notify.js';
import { HttpError, bad, forbidden, h, isUuid, notFound, optNum, optStr, randomToken, reqLatLng, reqTime, reqUuid } from '../util.js';

export const router = express.Router();
router.use(['/api/panic', '/api/tracks', '/api/guard'], requireAuth);

const mapsUrl = (lat, lng) => (lat === null || lat === undefined ? '' : `https://maps.google.com/?q=${lat},${lng}`);
const liveUrl = (token) => `${config.publicUrl}/live/${token}`;

/** Penerima panic: kontak darurat yang punya akun + satpam bertugas (jika tidak ada yang bertugas: semua satpam dan admin). */
async function panicRecipients(userId) {
  const contacts = (await q('SELECT contact_user_id FROM emergency_contacts WHERE user_id = $1 AND contact_user_id IS NOT NULL', [userId])).rows.map(
    (r) => r.contact_user_id,
  );
  let guards = (await q(`SELECT g.user_id FROM guard_duty g JOIN users u ON u.id = g.user_id WHERE g.on_duty AND u.role IN ('guard','admin')`)).rows.map(
    (r) => r.user_id,
  );
  if (!guards.length) guards = (await q(`SELECT id FROM users WHERE role IN ('guard','admin')`)).rows.map((r) => r.id);
  return { contacts, guards, all: [...new Set([...contacts, ...guards])].filter((id) => id !== userId) };
}

async function canSeeSession(user, session) {
  if (session.user_id === user.id || user.role === 'admin') return true;
  if (session.kind === 'panic' && user.role === 'guard') return true;
  return Boolean(await one('SELECT 1 FROM track_recipients WHERE session_id = $1 AND recipient_user_id = $2', [session.id, user.id]));
}

// ================= PANIC =================

router.post(
  '/api/panic',
  h(async (req, res) => {
    const b = req.body || {};
    const id = reqUuid(b.id, 'id');
    const [lat, lng] = reqLatLng(b.lat, b.lng);
    const createdAt = reqTime(b.created_at, 'created_at');
    const trackId = b.track_session_id ? reqUuid(b.track_session_id, 'track_session_id') : null;

    const existing = await one('SELECT * FROM panic_events WHERE id = $1', [id]);
    if (existing) {
      if (existing.user_id !== req.user.id) throw forbidden();
      const s = await one('SELECT share_token FROM track_sessions WHERE panic_id = $1', [id]);
      return res.json({ panic: existing, live_url: s ? liveUrl(s.share_token) : null, duplicate: true });
    }

    const rcp = await panicRecipients(req.user.id);
    const token = randomToken();
    let result;
    try {
      result = await tx(async (c) => {
      const panic = (
        await c.query(
          `INSERT INTO panic_events (id, user_id, lat, lng, accuracy_m, battery, created_at, sms_sent)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8) RETURNING *`,
          [id, req.user.id, lat, lng, optNum(b.accuracy_m, 'accuracy_m', { min: 0 }), optNum(b.battery, 'battery', { min: 0, max: 100 }), createdAt, Boolean(b.sms_sent)],
        )
      ).rows[0];
      if (trackId) {
        const taken = (await c.query('SELECT user_id FROM track_sessions WHERE id = $1', [trackId])).rows[0];
        if (taken) throw bad('track_session_id sudah dipakai');
        await c.query(
          `INSERT INTO track_sessions (id, user_id, kind, panic_id, started_at, share_token) VALUES ($1,$2,'panic',$3,$4,$5)`,
          [trackId, req.user.id, id, createdAt, token],
        );
        for (const rid of rcp.all) {
          await c.query('INSERT INTO track_recipients (session_id, recipient_user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING', [trackId, rid]);
        }
        if (lat !== null) {
          await c.query(
            `INSERT INTO location_points (id, session_id, lat, lng, accuracy_m, battery, recorded_at) VALUES (gen_random_uuid(),$1,$2,$3,$4,$5,$6)`,
            [trackId, lat, lng, panic.accuracy_m, panic.battery, createdAt],
          );
        }
      }
      return panic;
      });
    } catch (e) {
      // Dua kiriman bersamaan (langsung + antrean) dengan id sama: yang kalah dianggap duplikat
      if (e.code === '23505') {
        const s = await one('SELECT share_token FROM track_sessions WHERE panic_id = $1', [id]);
        return res.json({ panic: await one('SELECT * FROM panic_events WHERE id = $1', [id]), live_url: s ? liveUrl(s.share_token) : null, duplicate: true });
      }
      throw e;
    }

    const live = trackId ? liveUrl(token) : '';
    const data = {
      type: 'panic',
      panic_id: id,
      user_id: req.user.id,
      user_name: req.user.name,
      user_phone: req.user.phone || '',
      lat: lat ?? '',
      lng: lng ?? '',
      accuracy_m: result.accuracy_m ?? '',
      battery: result.battery ?? '',
      created_at: createdAt,
      maps_url: mapsUrl(lat, lng),
      live_url: live,
      session_id: trackId || '',
    };
    pushLater(rcp.contacts.filter((x) => !rcp.guards.includes(x)), { ...data, role: 'contact' });
    pushLater(rcp.guards, { ...data, role: 'guard' });
    emit('guards', 'panic', data);
    rcp.all.forEach((uid) => emit(`user:${uid}`, 'panic', data));
    await audit(req.user.id, 'panic_create', id, { recipients: rcp.all.length, delayed_s: Math.round((Date.now() - Date.parse(createdAt)) / 1000) });
    res.status(201).json({ panic: result, live_url: live || null, recipients: rcp.all.length });
  }),
);

router.get(
  '/api/panic',
  h(async (req, res) => {
    const { rows } = await q('SELECT * FROM panic_events WHERE user_id = $1 ORDER BY created_at DESC LIMIT 100', [req.user.id]);
    res.json(rows);
  }),
);

/** Panic yang masih berjalan dan terlihat oleh pengguna ini (satpam/admin: semua; kontak: yang melibatkan dia). */
router.get(
  '/api/panic/active',
  h(async (req, res) => {
    const staff = ['guard', 'admin'].includes(req.user.role);
    const { rows } = await q(
      `SELECT p.*, u.name AS user_name, u.phone AS user_phone, s.id AS session_id, s.share_token,
              lp.lat AS last_lat, lp.lng AS last_lng, lp.recorded_at AS last_at, lp.battery AS last_battery
         FROM panic_events p
         JOIN users u ON u.id = p.user_id
         LEFT JOIN track_sessions s ON s.panic_id = p.id
         LEFT JOIN LATERAL (SELECT lat, lng, recorded_at, battery FROM location_points WHERE session_id = s.id ORDER BY recorded_at DESC LIMIT 1) lp ON true
        WHERE p.status IN ('aktif','diterima')
          AND ($2 OR EXISTS (SELECT 1 FROM track_recipients r WHERE r.session_id = s.id AND r.recipient_user_id = $1))
        ORDER BY p.created_at DESC`,
      [req.user.id, staff],
    );
    res.json(rows.map(({ share_token, ...r }) => ({ ...r, live_url: share_token ? liveUrl(share_token) : null })));
  }),
);

const OWNER_STATUSES = new Set(['aman', 'dibatalkan']);
const STAFF_STATUSES = new Set(['diterima', 'ditangani', 'aman']);

router.patch(
  '/api/panic/:id',
  h(async (req, res) => {
    const id = reqUuid(req.params.id, 'id');
    const status = String(req.body?.status || '');
    const note = optStr(req.body?.note, 'note', 2000);
    const p = await one('SELECT * FROM panic_events WHERE id = $1', [id]);
    if (!p) throw notFound();
    const isOwner = p.user_id === req.user.id;
    const isStaff = ['guard', 'admin'].includes(req.user.role);
    if (!(isOwner && OWNER_STATUSES.has(status)) && !(isStaff && STAFF_STATUSES.has(status))) throw forbidden('Status itu tidak bisa Anda setel');
    if (p.status === status) return res.json(p);
    if (['aman', 'dibatalkan'].includes(p.status)) throw new HttpError(409, 'Kejadian sudah ditutup');
    const sets = ['status = $2'];
    if (status === 'diterima') sets.push('acked_by = $3', 'acked_at = now()');
    if (['ditangani', 'aman', 'dibatalkan'].includes(status)) sets.push('resolved_by = $3', 'resolved_at = now()');
    sets.push('note = COALESCE($4, note)');
    const updated = await one(`UPDATE panic_events SET ${sets.join(', ')} WHERE id = $1 RETURNING *`, [id, status, req.user.id, note]);
    if (status !== 'diterima') await q('UPDATE track_sessions SET ended_at = COALESCE(ended_at, now()) WHERE panic_id = $1', [id]);

    const s = await one('SELECT id FROM track_sessions WHERE panic_id = $1', [id]);
    const recipients = s ? (await q('SELECT recipient_user_id FROM track_recipients WHERE session_id = $1', [s.id])).rows.map((r) => r.recipient_user_id) : [];
    const data = { type: 'panic_status', panic_id: id, status, by_name: req.user.name, note: note || '' };
    pushLater([p.user_id, ...recipients].filter((u) => u !== req.user.id), data);
    emit('guards', 'panic_status', data);
    if (s) emit(`live:${s.id}`, 'status', { status });
    await audit(req.user.id, 'panic_status', id, { status });
    res.json(updated);
  }),
);

router.post(
  '/api/guard/duty',
  requireRole('guard', 'admin'),
  h(async (req, res) => {
    const onDuty = Boolean(req.body?.on_duty);
    await q(
      `INSERT INTO guard_duty (user_id, on_duty, since) VALUES ($1,$2,now())
       ON CONFLICT (user_id) DO UPDATE SET on_duty = EXCLUDED.on_duty, since = now()`,
      [req.user.id, onDuty],
    );
    await audit(req.user.id, onDuty ? 'duty_on' : 'duty_off', null);
    res.json({ on_duty: onDuty });
  }),
);

// ================= TRACK =================

router.post(
  '/api/tracks',
  h(async (req, res) => {
    const id = reqUuid(req.body?.id, 'id');
    const startedAt = reqTime(req.body?.started_at, 'started_at');
    const wanted = Array.isArray(req.body?.recipient_user_ids) ? req.body.recipient_user_ids.filter(isUuid) : [];
    const existing = await one('SELECT * FROM track_sessions WHERE id = $1', [id]);
    if (existing) {
      if (existing.user_id !== req.user.id) throw forbidden();
      return res.json({ session: existing, live_url: liveUrl(existing.share_token), duplicate: true });
    }
    // Penerima hanya boleh dari kontak darurat sendiri
    const allowed = (
      await q('SELECT contact_user_id FROM emergency_contacts WHERE user_id = $1 AND contact_user_id = ANY($2::uuid[])', [req.user.id, wanted])
    ).rows.map((r) => r.contact_user_id);
    const token = randomToken();
    const session = await tx(async (c) => {
      const s = (
        await c.query(`INSERT INTO track_sessions (id, user_id, kind, started_at, share_token) VALUES ($1,$2,'track',$3,$4) RETURNING *`, [
          id,
          req.user.id,
          startedAt,
          token,
        ])
      ).rows[0];
      for (const rid of allowed) await c.query('INSERT INTO track_recipients (session_id, recipient_user_id) VALUES ($1,$2)', [id, rid]);
      return s;
    });
    pushLater(allowed, { type: 'track_start', session_id: id, user_name: req.user.name, live_url: liveUrl(token), started_at: startedAt });
    res.status(201).json({ session, live_url: liveUrl(token), recipients: allowed.length });
  }),
);

router.get(
  '/api/tracks',
  h(async (req, res) => {
    const { rows } = await q(
      `SELECT s.id, s.kind, s.panic_id, s.started_at, s.ended_at,
              (SELECT count(*)::int FROM location_points p WHERE p.session_id = s.id) AS points
         FROM track_sessions s WHERE s.user_id = $1 ORDER BY s.started_at DESC LIMIT 100`,
      [req.user.id],
    );
    res.json(rows);
  }),
);

router.post(
  '/api/tracks/:id/points',
  h(async (req, res) => {
    const id = reqUuid(req.params.id, 'id');
    const s = await one('SELECT * FROM track_sessions WHERE id = $1', [id]);
    if (!s) throw notFound('Sesi belum terdaftar');
    if (s.user_id !== req.user.id) throw forbidden();
    const pts = Array.isArray(req.body?.points) ? req.body.points : [];
    if (pts.length > 1000) throw bad('Maksimal 1000 titik per kiriman');
    const clean = pts.map((p) => {
      const [lat, lng] = reqLatLng(p.lat, p.lng);
      if (lat === null) throw bad('titik wajib punya lat/lng');
      return {
        id: reqUuid(p.id, 'point.id'),
        lat,
        lng,
        accuracy_m: optNum(p.accuracy_m, 'accuracy_m', { min: 0 }),
        speed: optNum(p.speed, 'speed', { min: 0 }),
        battery: optNum(p.battery, 'battery', { min: 0, max: 100 }),
        recorded_at: reqTime(p.recorded_at, 'recorded_at'),
      };
    });
    let inserted = 0;
    if (clean.length) {
      const vals = [];
      const params = [];
      clean.forEach((p, i) => {
        const b = i * 8;
        vals.push(`($${b + 1},$${b + 2},$${b + 3},$${b + 4},$${b + 5},$${b + 6},$${b + 7},$${b + 8})`);
        params.push(p.id, id, p.lat, p.lng, p.accuracy_m, p.speed, p.battery, p.recorded_at);
      });
      const r = await q(
        `INSERT INTO location_points (id, session_id, lat, lng, accuracy_m, speed, battery, recorded_at) VALUES ${vals.join(',')} ON CONFLICT (id) DO NOTHING`,
        params,
      );
      inserted = r.rowCount;
      const latest = clean.reduce((a, b) => (a.recorded_at > b.recorded_at ? a : b));
      const payload = { session_id: id, points: clean.map(({ lat, lng, recorded_at, battery }) => ({ lat, lng, recorded_at, battery })) };
      emit(`live:${id}`, 'points', payload);
      if (s.kind === 'panic') emit('guards', 'panic_location', { panic_id: s.panic_id, ...latest });
    }
    res.json({ received: clean.length, inserted });
  }),
);

router.patch(
  '/api/tracks/:id/stop',
  h(async (req, res) => {
    const id = reqUuid(req.params.id, 'id');
    const s = await one('SELECT * FROM track_sessions WHERE id = $1', [id]);
    if (!s) throw notFound();
    if (s.user_id !== req.user.id) throw forbidden();
    const endedAt = req.body?.ended_at ? reqTime(req.body.ended_at, 'ended_at') : new Date().toISOString();
    const updated = await one('UPDATE track_sessions SET ended_at = COALESCE(ended_at, $2) WHERE id = $1 RETURNING *', [id, endedAt]);
    const rcp = (await q('SELECT recipient_user_id FROM track_recipients WHERE session_id = $1', [id])).rows.map((r) => r.recipient_user_id);
    pushLater(rcp, { type: 'track_stop', session_id: id, user_name: req.user.name });
    emit(`live:${id}`, 'ended', { session_id: id, ended_at: updated.ended_at });
    res.json(updated);
  }),
);

router.get(
  '/api/tracks/:id/points',
  h(async (req, res) => {
    const id = reqUuid(req.params.id, 'id');
    const s = await one('SELECT * FROM track_sessions WHERE id = $1', [id]);
    if (!s) throw notFound();
    if (!(await canSeeSession(req.user, s))) throw forbidden();
    const { rows } = await q(
      'SELECT lat, lng, accuracy_m, speed, battery, recorded_at FROM location_points WHERE session_id = $1 ORDER BY recorded_at LIMIT 20000',
      [id],
    );
    res.json(rows);
  }),
);

// ================= PETA LIVE (tautan token) =================

export async function liveSessionByToken(token) {
  const s = await one(
    `SELECT s.id, s.kind, s.started_at, s.ended_at, u.name AS user_name, u.phone AS user_phone, p.status AS panic_status, p.escalation_level
       FROM track_sessions s JOIN users u ON u.id = s.user_id LEFT JOIN panic_events p ON p.id = s.panic_id
      WHERE s.share_token = $1`,
    [String(token)],
  );
  if (!s) throw notFound('Tautan tidak valid');
  // Tautan berhenti berlaku 1 jam setelah sesi berakhir
  if (s.ended_at && Date.now() - Date.parse(s.ended_at) > 3600000) throw new HttpError(410, 'Sesi berbagi lokasi sudah berakhir');
  return s;
}

router.get(
  '/api/live/:token',
  h(async (req, res) => {
    const s = await liveSessionByToken(req.params.token);
    const { rows } = await q(
      `SELECT lat, lng, battery, recorded_at FROM (
         SELECT lat, lng, battery, recorded_at FROM location_points WHERE session_id = $1 ORDER BY recorded_at DESC LIMIT 3000
       ) t ORDER BY recorded_at`,
      [s.id],
    );
    res.json({
      session: {
        kind: s.kind,
        user_name: s.user_name,
        // Nomor HP hanya untuk panic: penerima tautan perlu bisa menelepon korban
        user_phone: s.kind === 'panic' ? s.user_phone || null : null,
        started_at: s.started_at,
        ended_at: s.ended_at,
        panic_status: s.panic_status,
      },
      points: rows,
    });
  }),
);
