// Tes integrasi: menjalankan API sungguhan terhadap database PostgreSQL uji.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';

process.env.DATABASE_URL ||= 'postgres://everest:everest@localhost:5432/everest_test';
process.env.DEV_LOGIN = 'true';
process.env.FARHAN_DAILY_QUOTA = '3';
process.env.UPLOAD_DIR = path.join(os.tmpdir(), `everest-test-${process.pid}`);
process.env.PUBLIC_URL = 'http://everest.test';

const { default: request } = await import('supertest');
const { default: sharp } = await import('sharp');
const { io: ioClient } = await import('socket.io-client');
const { pool } = await import('../src/db.js');
const { migrate } = await import('../src/migrate.js');
const { createApp } = await import('../src/app.js');
const { attachSockets } = await import('../src/server.js');
const { outbox } = await import('../src/notify.js');
const { setTestProvider } = await import('../src/llm.js');

const uuid = () => crypto.randomUUID();
let app, server, baseUrl;
const users = {};

async function login(email, role) {
  const r = await request(app).post('/api/auth/dev-login').send({ email, name: email.split('@')[0], role }).expect(200);
  return { token: r.body.token, ...r.body.user };
}
const auth = (u) => ({ Authorization: `Bearer ${u.token}` });

before(async () => {
  await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;');
  await migrate(() => {});
  app = createApp();
  server = http.createServer(app);
  attachSockets(server);
  await new Promise((r) => server.listen(0, r));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
  users.ana = await login('ana@uika.test');
  users.budi = await login('budi@uika.test');
  users.citra = await login('citra@uika.test');
  users.satpam = await login('satpam@uika.test', 'guard');
  users.admin = await login('admin@uika.test', 'admin');
});

after(async () => {
  await new Promise((r) => server.close(r));
  await pool.end();
});

test('login dev dan profil', async () => {
  const r = await request(app).get('/api/me').set(auth(users.ana)).expect(200);
  assert.equal(r.body.email, 'ana@uika.test');
  assert.equal(r.body.role, 'user');
  await request(app).get('/api/me').expect(401);
  await request(app).get('/api/me').set({ Authorization: 'Bearer palsu' }).expect(401);
});

test('kontak darurat: berdasarkan email pengguna dan nomor HP', async () => {
  const r = await request(app).post('/api/contacts').set(auth(users.ana)).send({ email: 'budi@uika.test', phone: '+62 812 1111 2222' }).expect(201);
  assert.equal(r.body.contact_user_id, users.budi.id);
  await request(app).post('/api/contacts').set(auth(users.ana)).send({ email: 'tidakada@uika.test' }).expect(404);
  await request(app).post('/api/contacts').set(auth(users.ana)).send({ phone: '08123456789', name: 'Ibu' }).expect(201);
  await request(app).post('/api/contacts').set(auth(users.ana)).send({ email: 'ana@uika.test' }).expect(400);
  const list = await request(app).get('/api/contacts').set(auth(users.ana)).expect(200);
  assert.equal(list.body.length, 2);
});

test('panic: terkirim ke kontak dan satpam bertugas, idempotent, alur status', async () => {
  await request(app).post('/api/guard/duty').set(auth(users.satpam)).send({ on_duty: true }).expect(200);
  await request(app).post('/api/guard/duty').set(auth(users.ana)).send({ on_duty: true }).expect(403);

  const id = uuid();
  const trackId = uuid();
  // Dibuat 20 menit lalu saat offline, baru terkirim sekarang
  const createdAt = new Date(Date.now() - 20 * 60000).toISOString();
  const before = outbox.length;
  const r = await request(app)
    .post('/api/panic')
    .set(auth(users.ana))
    .send({ id, lat: -6.5571, lng: 106.7969, accuracy_m: 12, battery: 41, created_at: createdAt, track_session_id: trackId, sms_sent: true })
    .expect(201);
  assert.equal(r.body.recipients, 2);
  assert.match(r.body.live_url, /^http:\/\/everest\.test\/live\//);
  assert.equal(r.body.panic.created_at, createdAt);
  const pushes = outbox.slice(before);
  assert.equal(pushes.length, 2);
  const toGuard = pushes.find((p) => p.data.role === 'guard');
  const toContact = pushes.find((p) => p.data.role === 'contact');
  assert.deepEqual(toGuard.userIds, [users.satpam.id]);
  assert.deepEqual(toContact.userIds, [users.budi.id]);
  assert.equal(toGuard.data.maps_url, 'https://maps.google.com/?q=-6.5571,106.7969');
  assert.equal(typeof toGuard.data.battery, 'string');

  // Kirim ulang dari antrean offline: tidak membuat data/push ganda
  const again = await request(app).post('/api/panic').set(auth(users.ana)).send({ id, lat: -6.5571, lng: 106.7969, created_at: createdAt, track_session_id: trackId }).expect(200);
  assert.equal(again.body.duplicate, true);
  assert.equal(outbox.length, before + 2);

  // Visibilitas panic aktif
  const g = await request(app).get('/api/panic/active').set(auth(users.satpam)).expect(200);
  assert.equal(g.body.length, 1);
  assert.equal(g.body[0].user_name, 'ana');
  assert.equal(g.body[0].last_lat, -6.5571);
  assert.equal((await request(app).get('/api/panic/active').set(auth(users.budi))).body.length, 1);
  assert.equal((await request(app).get('/api/panic/active').set(auth(users.citra))).body.length, 0);

  // Status
  await request(app).patch(`/api/panic/${id}`).set(auth(users.ana)).send({ status: 'diterima' }).expect(403);
  await request(app).patch(`/api/panic/${id}`).set(auth(users.citra)).send({ status: 'diterima' }).expect(403);
  const ack = await request(app).patch(`/api/panic/${id}`).set(auth(users.satpam)).send({ status: 'diterima', note: 'Menuju lokasi' }).expect(200);
  assert.equal(ack.body.acked_by, users.satpam.id);
  await request(app).patch(`/api/panic/${id}`).set(auth(users.ana)).send({ status: 'aman' }).expect(200);
  await request(app).patch(`/api/panic/${id}`).set(auth(users.satpam)).send({ status: 'ditangani' }).expect(409);
  const sessions = await request(app).get('/api/tracks').set(auth(users.ana)).expect(200);
  assert.ok(sessions.body.find((s) => s.id === trackId).ended_at, 'sesi tracking panic ikut berakhir');
});

test('panic: kiriman ganda serentak (langsung + antrean) tidak error', async () => {
  const id = uuid();
  const body = { id, lat: -6.55, lng: 106.79, created_at: new Date().toISOString(), track_session_id: uuid() };
  const [a, b] = await Promise.all([
    request(app).post('/api/panic').set(auth(users.budi)).send(body),
    request(app).post('/api/panic').set(auth(users.budi)).send(body),
  ]);
  assert.deepEqual([a.status, b.status].sort(), [200, 201]);
  const { rows } = await pool.query('SELECT count(*)::int AS n FROM panic_events WHERE id = $1', [id]);
  assert.equal(rows[0].n, 1);
  await request(app).patch(`/api/panic/${id}`).set(auth(users.budi)).send({ status: 'dibatalkan' }).expect(200);
});

test('panic tanpa satpam bertugas: dikirim ke semua satpam dan admin', async () => {
  await request(app).post('/api/guard/duty').set(auth(users.satpam)).send({ on_duty: false }).expect(200);
  const before = outbox.length;
  const r = await request(app).post('/api/panic').set(auth(users.citra)).send({ id: uuid(), lat: -6.5, lng: 106.8, created_at: new Date().toISOString() }).expect(201);
  assert.equal(r.body.live_url, null);
  const guardPush = outbox.slice(before).find((p) => p.data.role === 'guard');
  assert.deepEqual(new Set(guardPush.userIds), new Set([users.satpam.id, users.admin.id]));
  await request(app).post('/api/guard/duty').set(auth(users.satpam)).send({ on_duty: true }).expect(200);
});

test('eskalasi: panic tanpa respons diteruskan ke semua satpam lalu admin, pengirim diberi tahu', async () => {
  const { escalatePanics } = await import('../src/escalation.js');
  const satpam2 = await login('satpam2@uika.test', 'guard'); // tidak bertugas
  const id = uuid();
  const trackId = uuid();
  await request(app).post('/api/panic').set(auth(users.ana)).send({ id, lat: -6.557, lng: 106.796, created_at: new Date().toISOString(), track_session_id: trackId }).expect(201);
  // Panic lain yang langsung diterima satpam tidak boleh dieskalasi
  const acked = uuid();
  await request(app).post('/api/panic').set(auth(users.budi)).send({ id: acked, lat: -6.55, lng: 106.79, created_at: new Date().toISOString() }).expect(201);
  await request(app).patch(`/api/panic/${acked}`).set(auth(users.satpam)).send({ status: 'diterima' }).expect(200);

  const at = (s) => new Date(Date.now() + s * 1000);
  const mine = (list) => list.filter((e) => e.panic_id === id);
  assert.equal(mine(await escalatePanics({ now: at(60) })).length, 0, 'belum 2 menit');

  let before = outbox.length;
  const l1 = mine(await escalatePanics({ now: at(130) }));
  assert.deepEqual(l1.map((e) => e.level), [1]);
  const sent1 = outbox.slice(before);
  const guardPush = sent1.find((p) => p.data.panic_id === id && p.data.type === 'panic');
  assert.deepEqual(new Set(guardPush.userIds), new Set([users.satpam.id, satpam2.id]));
  assert.equal(guardPush.data.escalation, '1');
  assert.ok(guardPush.data.live_url.startsWith('http://everest.test/live/'));
  const ownerPush = sent1.find((p) => p.data.panic_id === id && p.data.type === 'panic_escalated');
  assert.deepEqual(ownerPush.userIds, [users.ana.id]);
  assert.match(ownerPush.data.message, /diteruskan ke semua satpam/);

  // Tidak dobel pada putaran berikutnya
  assert.equal(mine(await escalatePanics({ now: at(140) })).length, 0);

  before = outbox.length;
  assert.deepEqual(mine(await escalatePanics({ now: at(310) })).map((e) => e.level), [2]);
  const adminPush = outbox.slice(before).find((p) => p.data.panic_id === id && p.data.type === 'panic');
  assert.deepEqual(adminPush.userIds, [users.admin.id]);
  assert.equal((await escalatePanics({ now: at(900) })).filter((e) => e.panic_id === acked).length, 0, 'panic yang sudah diterima tidak dieskalasi');

  // Satpam yang baru ikut dari eskalasi bisa melihat jejak lokasinya
  await request(app).get(`/api/tracks/${trackId}/points`).set(auth(satpam2)).expect(200);
  const active = await request(app).get('/api/panic/active').set(auth(users.satpam)).expect(200);
  assert.equal(active.body.find((p) => p.id === id).escalation_level, 2);
  await request(app).patch(`/api/panic/${id}`).set(auth(users.ana)).send({ status: 'aman' }).expect(200);
  await request(app).patch(`/api/panic/${acked}`).set(auth(users.budi)).send({ status: 'aman' }).expect(200);
});

test('persetujuan privasi: tercatat, cakupan disaring, versi lama tidak menimpa', async () => {
  const me0 = await request(app).get('/api/me').set(auth(users.budi)).expect(200);
  assert.equal(me0.body.consent_version, null);
  assert.ok(me0.body.privacy_version);
  const t1 = new Date(Date.now() - 60000).toISOString();
  await request(app).post('/api/me/consent').set(auth(users.budi)).send({ version: '2026-11-07', agreed_at: t1, scopes: ['rekam', 'lokasi', 'hack'] }).expect(200);
  // Kiriman antrean yang lebih tua datang belakangan
  await request(app).post('/api/me/consent').set(auth(users.budi)).send({ version: 'lama', agreed_at: new Date(Date.now() - 3600000).toISOString() }).expect(200);
  const me1 = await request(app).get('/api/me').set(auth(users.budi)).expect(200);
  assert.equal(me1.body.consent_version, '2026-11-07');
  const { rows } = await pool.query('SELECT scopes FROM consent_log WHERE user_id = $1 ORDER BY agreed_at DESC', [users.budi.id]);
  assert.equal(rows.length, 2);
  assert.deepEqual(rows[0].scopes, ['rekam', 'lokasi']);
  await request(app).post('/api/me/consent').set(auth(users.budi)).send({ agreed_at: t1 }).expect(400);
  const page = await request(app).get('/privasi').expect(200);
  assert.match(page.text, /Kebijakan Privasi/);
});

test('track: penerima hanya dari kontak, titik idempotent, peta live dan hak akses', async () => {
  const sid = uuid();
  const r = await request(app)
    .post('/api/tracks')
    .set(auth(users.ana))
    .send({ id: sid, started_at: new Date().toISOString(), recipient_user_ids: [users.budi.id, users.citra.id] })
    .expect(201);
  assert.equal(r.body.recipients, 1, 'citra bukan kontak ana, jadi diabaikan');
  const token = r.body.live_url.split('/').pop();

  const t0 = Date.now() - 60000;
  const pts = [0, 1, 2].map((i) => ({ id: uuid(), lat: -6.55 + i * 0.001, lng: 106.79, battery: 80, recorded_at: new Date(t0 + i * 10000).toISOString() }));
  const a = await request(app).post(`/api/tracks/${sid}/points`).set(auth(users.ana)).send({ points: pts }).expect(200);
  assert.equal(a.body.inserted, 3);
  const b = await request(app).post(`/api/tracks/${sid}/points`).set(auth(users.ana)).send({ points: pts }).expect(200);
  assert.equal(b.body.inserted, 0, 'kirim ulang tidak menggandakan titik');
  await request(app).post(`/api/tracks/${sid}/points`).set(auth(users.budi)).send({ points: [] }).expect(403);
  await request(app).post(`/api/tracks/${sid}/points`).set(auth(users.ana)).send({ points: [{ id: uuid(), lat: 200, lng: 0, recorded_at: new Date().toISOString() }] }).expect(400);

  const live = await request(app).get(`/api/live/${token}`).expect(200);
  assert.equal(live.body.points.length, 3);
  assert.equal(live.body.session.user_name, 'ana');
  await request(app).get('/api/live/bukan-token').expect(404);

  await request(app).get(`/api/tracks/${sid}/points`).set(auth(users.budi)).expect(200);
  await request(app).get(`/api/tracks/${sid}/points`).set(auth(users.citra)).expect(403);
  await request(app).get(`/api/tracks/${sid}/points`).set(auth(users.satpam)).expect(403); // track biasa bukan untuk satpam

  await request(app).patch(`/api/tracks/${sid}/stop`).set(auth(users.ana)).send({}).expect(200);
  // Titik yang tertahan offline sebelum stop tetap diterima
  await request(app).post(`/api/tracks/${sid}/points`).set(auth(users.ana)).send({ points: [{ id: uuid(), lat: -6.56, lng: 106.79, recorded_at: new Date(t0 + 40000).toISOString() }] }).expect(200);
});

test('peta live via socket menerima titik baru secara real-time', async () => {
  const sid = uuid();
  const r = await request(app).post('/api/tracks').set(auth(users.ana)).send({ id: sid, started_at: new Date().toISOString(), recipient_user_ids: [users.budi.id] }).expect(201);
  const token = r.body.live_url.split('/').pop();
  const socket = ioClient(baseUrl, { auth: { live_token: token }, transports: ['websocket'] });
  await new Promise((res, rej) => {
    socket.on('connect', res);
    socket.on('connect_error', rej);
  });
  await new Promise((res) => setTimeout(res, 150)); // beri waktu join room
  const got = new Promise((res) => socket.on('points', res));
  await request(app).post(`/api/tracks/${sid}/points`).set(auth(users.ana)).send({ points: [{ id: uuid(), lat: -6.51, lng: 106.81, recorded_at: new Date().toISOString() }] }).expect(200);
  const msg = await Promise.race([got, new Promise((_, rej) => setTimeout(() => rej(new Error('tidak ada event socket')), 3000))]);
  assert.equal(msg.points[0].lat, -6.51);
  socket.close();
});

test('protokol WebSocket mentah seperti klien Android: auth, watch langsung, event panic & titik', async () => {
  const { default: WebSocket } = await import('ws');
  const url = `${baseUrl.replace('http', 'ws')}/socket.io/?EIO=4&transport=websocket`;
  const frames = [];
  const waitFor = (pred, ms = 3000) =>
    new Promise((res, rej) => {
      const t0 = Date.now();
      const tick = () => {
        const f = frames.find(pred);
        if (f) return res(f);
        if (Date.now() - t0 > ms) return rej(new Error(`tidak ada bingkai yang cocok; diterima: ${frames.join(' | ')}`));
        setTimeout(tick, 20);
      };
      tick();
    });
  const open = (token) => {
    const ws = new WebSocket(url);
    ws.on('message', (m) => {
      const s = m.toString();
      frames.push(s);
      if (s === '2') ws.send('3');
      if (s.startsWith('0{')) ws.send(`40${JSON.stringify({ token })}`);
    });
    return ws;
  };

  // Token salah → CONNECT_ERROR (44)
  const bad = open('palsu');
  const err = await waitFor((f) => f.startsWith('44'));
  assert.match(err, /unauthorized/);
  bad.close();
  frames.length = 0;

  const ws = open(users.satpam.token);
  const open0 = await waitFor((f) => f.startsWith('0{'));
  assert.ok(JSON.parse(open0.slice(1)).pingInterval > 0);
  await waitFor((f) => f.startsWith('40'));

  // Panic baru → event "panic" ke satpam
  const id = uuid();
  const trackId = uuid();
  await request(app).post('/api/panic').set(auth(users.ana)).send({ id, lat: -6.5, lng: 106.8, created_at: new Date().toISOString(), track_session_id: trackId }).expect(201);
  const ev = await waitFor((f) => f.startsWith('42["panic",'));
  assert.equal(JSON.parse(ev.slice(2))[1].panic_id, id);

  // "watch" dikirim seketika setelah tersambung (tanpa jeda) tetap diterima server
  frames.length = 0;
  const ws2 = open(users.satpam.token);
  await waitFor((f) => f.startsWith('40'));
  ws2.send(`42${JSON.stringify(['watch', trackId])}`);
  await new Promise((r) => setTimeout(r, 100));
  await request(app).post(`/api/tracks/${trackId}/points`).set(auth(users.ana)).send({ points: [{ id: uuid(), lat: -6.51, lng: 106.81, recorded_at: new Date().toISOString() }] }).expect(200);
  const pts = await waitFor((f) => f.startsWith('42["points",'));
  assert.equal(JSON.parse(pts.slice(2))[1].points[0].lat, -6.51);
  await waitFor((f) => f.startsWith('42["panic_location",'));

  // Status berubah → peta live menerima "status"
  await request(app).patch(`/api/panic/${id}`).set(auth(users.satpam)).send({ status: 'diterima' }).expect(200);
  await waitFor((f) => f.startsWith('42["status",') && f.includes('diterima'));
  await waitFor((f) => f.startsWith('42["panic_status",'));
  await request(app).patch(`/api/panic/${id}`).set(auth(users.ana)).send({ status: 'aman' }).expect(200);
  ws.close();
  ws2.close();
});

test('laporan: foto dinormalisasi tanpa EXIF, hak akses pelapor/admin, status dan notifikasi', async () => {
  const photo = await sharp({ create: { width: 3000, height: 2000, channels: 3, background: '#4a7' } })
    .withExif({ IFD0: { Make: 'HPRahasia', Model: 'X1' } })
    .jpeg()
    .toBuffer();
  assert.ok((await sharp(photo).metadata()).exif, 'foto uji memang punya EXIF');
  const id = uuid();
  const meta = { id, description: 'Lampu koridor gedung B mati', category: 'fasilitas', lat: -6.557, lng: 106.797, created_at: new Date().toISOString() };
  const r = await request(app).post('/api/reports').set(auth(users.ana)).field('meta', JSON.stringify(meta)).attach('photo', photo, { filename: 'f.jpg', contentType: 'image/jpeg' }).expect(201);
  assert.equal(r.body.report.status, 'baru');
  assert.equal(r.body.report.photo_path, undefined);

  // Foto tersimpan: diperkecil, tanpa EXIF
  const url = new URL(r.body.report.photo_url);
  const img = await request(app).get(url.pathname + url.search).expect(200);
  const m = await sharp(img.body).metadata();
  assert.equal(m.width, 1600);
  assert.equal(m.exif, undefined);
  await request(app).get(url.pathname).expect(403);
  await request(app).get(`${url.pathname}?t=palsu`).expect(403);

  // Kirim ulang dari antrean: idempotent
  const dup = await request(app).post('/api/reports').set(auth(users.ana)).field('meta', JSON.stringify(meta)).attach('photo', photo, 'f.jpg').expect(200);
  assert.equal(dup.body.duplicate, true);
  await request(app).post('/api/reports').set(auth(users.ana)).field('meta', JSON.stringify({ ...meta, id: uuid(), description: '' })).attach('photo', photo, 'f.jpg').expect(400);
  await request(app).post('/api/reports').set(auth(users.ana)).field('meta', JSON.stringify({ ...meta, id: uuid() })).expect(400);

  // Akses
  await request(app).get(`/api/reports/${id}`).set(auth(users.budi)).expect(403);
  await request(app).get(`/api/reports/${id}`).set(auth(users.satpam)).expect(403);
  const own = await request(app).get('/api/reports').set(auth(users.ana)).expect(200);
  assert.equal(own.body.length, 1);
  assert.equal((await request(app).get('/api/reports').set(auth(users.budi))).body.length, 0);

  // Admin menindaklanjuti
  await request(app).get('/api/admin/reports').set(auth(users.ana)).expect(403);
  const list = await request(app).get('/api/admin/reports?status=baru').set(auth(users.admin)).expect(200);
  assert.equal(list.body[0].user_name, 'ana');
  const before = outbox.length;
  await request(app).patch(`/api/admin/reports/${id}`).set(auth(users.admin)).send({ status: 'diproses', note: 'Teknisi dijadwalkan besok' }).expect(200);
  const push = outbox.slice(before)[0];
  assert.deepEqual(push.userIds, [users.ana.id]);
  assert.equal(push.data.type, 'report_status');
  const detail = await request(app).get(`/api/reports/${id}`).set(auth(users.ana)).expect(200);
  assert.deepEqual(detail.body.history.map((h) => h.status), ['baru', 'diproses']);
  assert.equal(detail.body.history[1].note, 'Teknisi dijadwalkan besok');
});

test('catatan: sinkron dua arah, last-write-wins, pencarian, hapus', async () => {
  const id = uuid();
  const t1 = new Date(Date.now() - 3600000).toISOString();
  const note = {
    id,
    title: 'Rapat prodi',
    type: 'rapat',
    started_at: t1,
    duration_s: 1800,
    keywords: ['akreditasi'],
    updated_at: t1,
    segments: [
      { id: uuid(), start_ms: 0, end_ms: 30000, text: 'Pembahasan borang akreditasi prodi informatika' },
      { id: uuid(), start_ms: 30000, end_ms: 60000, text: 'Anggaran laboratorium disetujui' },
    ],
  };
  const r = await request(app).post('/api/notes/sync').set(auth(users.ana)).send({ notes: [note], since: null }).expect(200);
  assert.deepEqual(r.body.accepted, [id]);

  // HP kedua menarik perubahan
  const pull = await request(app).post('/api/notes/sync').set(auth(users.ana)).send({ notes: [] }).expect(200);
  assert.equal(pull.body.notes.length, 1);
  assert.equal(pull.body.notes[0].segments.length, 2);
  assert.equal((await request(app).post('/api/notes/sync').set(auth(users.budi)).send({ notes: [] })).body.notes.length, 0);

  // Versi lama ditolak diam-diam, versi baru diterima
  await request(app).post('/api/notes/sync').set(auth(users.ana)).send({ notes: [{ ...note, title: 'Versi lama', updated_at: new Date(Date.parse(t1) - 1000).toISOString() }] }).expect(200);
  const t2 = new Date().toISOString();
  await request(app).post('/api/notes/sync').set(auth(users.ana)).send({ notes: [{ ...note, title: 'Rapat prodi (revisi)', updated_at: t2 }] }).expect(200);
  const after2 = await request(app).post('/api/notes/sync').set(auth(users.ana)).send({ notes: [], since: t1 }).expect(200);
  assert.equal(after2.body.notes[0].title, 'Rapat prodi (revisi)');

  // Pengguna lain tidak bisa menimpa ID milik orang lain
  await request(app).post('/api/notes/sync').set(auth(users.budi)).send({ notes: [{ ...note, updated_at: new Date().toISOString() }] }).expect(403);

  const s = await request(app).get('/api/notes/search?q=anggaran').set(auth(users.ana)).expect(200);
  assert.equal(s.body.length, 1);
  assert.equal(s.body[0].start_ms, 30000);
  assert.equal((await request(app).get('/api/notes/search?q=anggaran').set(auth(users.budi))).body.length, 0);

  await request(app).post('/api/notes/sync').set(auth(users.ana)).send({ notes: [{ id, started_at: t1, updated_at: new Date(Date.now() + 1000).toISOString(), deleted: true }] }).expect(200);
  assert.equal((await request(app).get('/api/notes/search?q=anggaran').set(auth(users.ana))).body.length, 0);
});

test('Farhan cloud: alat cari_catatan dan usulan jadwal, kuota harian', async () => {
  await request(app)
    .post('/api/notes/sync')
    .set(auth(users.citra))
    .send({
      notes: [
        {
          id: uuid(),
          title: 'Kuliah jaringan',
          started_at: '2026-11-03T08:00:00+07:00',
          updated_at: new Date().toISOString(),
          segments: [{ id: uuid(), start_ms: 120000, end_ms: 150000, text: 'Tugas besar dikumpulkan tanggal 20 November' }],
        },
      ],
    })
    .expect(200);

  setTestProvider(async ({ system, question, runTool }) => {
    assert.match(system, /Farhan/);
    const found = await runTool('cari_catatan', { kata_kunci: 'tugas besar' });
    await runTool('usulkan_jadwal', { judul: 'Kumpul tugas besar', mulai: '2026-11-20T09:00:00+07:00' });
    return `Tugas besar dikumpulkan ${found.hasil[0].teks.split('tanggal ')[1]}.`;
  });
  const r = await request(app).post('/api/farhan/ask').set(auth(users.citra)).send({ question: 'Kapan tugas besar dikumpulkan? Jadwalkan juga.' }).expect(200);
  assert.equal(r.body.answer, 'Tugas besar dikumpulkan 20 November.');
  assert.equal(r.body.action.type, 'buat_jadwal');
  assert.equal(r.body.action.start, '2026-11-20T09:00:00+07:00');
  assert.equal(r.body.sources[0].start_ms, 120000);

  await request(app).post('/api/farhan/ask').set(auth(users.citra)).send({ question: 'a' }).expect(200);
  await request(app).post('/api/farhan/ask').set(auth(users.citra)).send({ question: 'b' }).expect(200);
  const over = await request(app).post('/api/farhan/ask').set(auth(users.citra)).send({ question: 'c' }).expect(429);
  assert.match(over.body.error, /Kuota/);
  setTestProvider(null);
  await request(app).post('/api/farhan/ask').set(auth(users.ana)).send({ question: 'x' }).expect(503);
});

test('Calendar: tanpa tautan Google memberi pesan jelas', async () => {
  // Server uji tanpa ENC_KEY/klien Google → layanan belum dikonfigurasi atau akun belum terhubung
  const r = await request(app).post('/api/calendar/events').set(auth(users.ana)).send({ title: 'Rapat', start: '2026-11-07T09:00:00+07:00' });
  assert.ok([409, 503].includes(r.status));
  assert.ok(r.body.error);
  await request(app).post('/api/auth/google/link').set(auth(users.ana)).send({ auth_code: 'x' }).expect(503);
});

test('admin: ubah peran, statistik, audit; non-admin ditolak', async () => {
  await request(app).patch(`/api/admin/users/${users.citra.id}`).set(auth(users.satpam)).send({ role: 'admin' }).expect(403);
  await request(app).patch(`/api/admin/users/${users.citra.id}`).set(auth(users.admin)).send({ role: 'guard' }).expect(200);
  // Peran baru langsung berlaku tanpa login ulang
  await request(app).post('/api/guard/duty').set(auth(users.citra)).send({ on_duty: true }).expect(200);
  await request(app).patch(`/api/admin/users/${users.admin.id}`).set(auth(users.admin)).send({ role: 'user' }).expect(400);
  const st = await request(app).get('/api/admin/stats').set(auth(users.admin)).expect(200);
  assert.equal(st.body.guards, 3); // satpam, satpam2, citra
  assert.ok(st.body.reports_in_progress >= 1);
  const a = await request(app).get('/api/admin/audit').set(auth(users.admin)).expect(200);
  assert.ok(a.body.some((l) => l.action === 'panic_create'));
});

test('validasi input dan error', async () => {
  await request(app).post('/api/panic').set(auth(users.ana)).send({ id: 'bukan-uuid', created_at: new Date().toISOString() }).expect(400);
  await request(app).post('/api/panic').set(auth(users.ana)).send({ id: uuid(), lat: 1, created_at: new Date().toISOString() }).expect(400);
  await request(app).post('/api/panic').set(auth(users.ana)).send({ id: uuid(), created_at: new Date(Date.now() + 3 * 86400000).toISOString() }).expect(400);
  await request(app).post('/api/notes/sync').set(auth(users.ana)).set('content-type', 'application/json').send('{rusak').expect(400);
  await request(app).get('/api/tidak-ada').expect(404);
  const cfg = await request(app).get('/api/auth/config').expect(200);
  assert.deepEqual(cfg.body, { sso: false, devLogin: true, googleCalendar: false });
});
