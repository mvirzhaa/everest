import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { Server } from 'socket.io';
import { config, assertProductionConfig } from './config.js';
import { createApp } from './app.js';
import { migrate } from './migrate.js';
import { pool, q } from './db.js';
import { SESSION_COOKIE, userFromToken } from './auth.js';
import { attachIo, initFcm } from './notify.js';
import { liveSessionByToken } from './routes/safety.js';
import { escalatePanics } from './escalation.js';

function cookieValue(header, name) {
  const m = (header || '').split(/;\s*/).find((c) => c.startsWith(`${name}=`));
  return m ? decodeURIComponent(m.slice(name.length + 1)) : null;
}

export function attachSockets(server) {
  if (config.basePath) {
    server.prependListener('request', (req) => {
      if (req.url && req.url.startsWith(`${config.basePath}/`)) {
        req.url = req.url.slice(config.basePath.length);
      }
    });
    server.prependListener('upgrade', (req) => {
      if (req.url && req.url.startsWith(`${config.basePath}/`)) {
        req.url = req.url.slice(config.basePath.length);
      }
    });
  }
  const io = new Server(server, { cors: { origin: false } });
  // Autentikasi di middleware: selesai SEBELUM klien menerima CONNECT, jadi event pertama
  // dari klien (mis. "watch" tepat setelah tersambung) tidak hilang.
  io.use(async (socket, next) => {
    try {
      const a = socket.handshake.auth || {};
      if (a.live_token) {
        // Penonton peta live: cukup token tautan
        socket.data.liveSession = (await liveSessionByToken(a.live_token)).id;
        return next();
      }
      const token = a.token || cookieValue(socket.handshake.headers.cookie, SESSION_COOKIE);
      const user = token ? await userFromToken(token) : null;
      if (!user) return next(new Error('unauthorized'));
      socket.data.user = user;
      next();
    } catch {
      next(new Error('unauthorized'));
    }
  });
  io.on('connection', (socket) => {
    if (socket.data.liveSession) {
      socket.join(`live:${socket.data.liveSession}`);
      return;
    }
    const user = socket.data.user;
    socket.join(`user:${user.id}`);
    if (['guard', 'admin'].includes(user.role)) socket.join('guards');
    if (user.role === 'admin') socket.join('admins');
    socket.on('watch', async (sessionId) => {
      // Satpam/admin ingin mengikuti titik lokasi sesi panic tertentu
      if (!['guard', 'admin'].includes(user.role) || typeof sessionId !== 'string') return;
      try {
        const ok = (await q(`SELECT 1 FROM track_sessions WHERE id::text = $1 AND (kind = 'panic' OR $2)`, [sessionId, user.role === 'admin'])).rowCount;
        if (ok) socket.join(`live:${sessionId}`);
      } catch {
        /* abaikan id yang tidak valid */
      }
    });
  });
  attachIo(io);
  return io;
}

/** Hapus titik lokasi Track biasa yang melewati masa simpan (panic disimpan untuk laporan keamanan). */
async function pruneOldPoints() {
  const r = await q(
    `DELETE FROM location_points p USING track_sessions s
      WHERE p.session_id = s.id AND s.kind = 'track' AND p.recorded_at < now() - ($1 || ' days')::interval`,
    [String(config.retention.trackDays)],
  );
  if (r.rowCount) console.log(`retensi: ${r.rowCount} titik lokasi lama dihapus`);
}

async function main() {
  for (const p of assertProductionConfig()) console.warn(`[peringatan] ${p}`);
  await migrate();
  await initFcm();
  const app = createApp();
  const server = http.createServer(app);
  attachSockets(server);
  server.listen(config.port, () => console.log(`Everest backend berjalan di ${config.publicUrl} (port ${config.port})`));
  setInterval(() => pruneOldPoints().catch((e) => console.error('retensi gagal:', e.message)), 6 * 3600 * 1000).unref();
  // Eskalasi panic yang belum ditanggapi
  let escalating = false;
  setInterval(async () => {
    if (escalating) return;
    escalating = true;
    try {
      for (const e of await escalatePanics()) console.log(`eskalasi panic ${e.panic_id} → tahap ${e.level} (${e.recipients} penerima)`);
    } catch (e) {
      console.error('eskalasi gagal:', e.message);
    } finally {
      escalating = false;
    }
  }, config.escalation.checkSec * 1000).unref();
  const stop = () => server.close(() => pool.end().then(() => process.exit(0)));
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
