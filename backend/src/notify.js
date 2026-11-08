import fs from 'node:fs';
import { config } from './config.js';
import { q } from './db.js';

/**
 * Pengiriman notifikasi:
 *  - Socket.IO untuk dashboard web dan aplikasi yang sedang terbuka
 *  - FCM (data message prioritas tinggi) untuk HP Android
 * Tanpa FCM_SERVICE_ACCOUNT, pesan hanya dicatat (mode pengembangan/uji).
 */
let io = null;
let messaging = null;
let fcmReady = false;
export const outbox = []; // dipakai tes untuk memeriksa pesan yang "terkirim"

export function attachIo(server) {
  io = server;
}

export function emit(room, event, payload) {
  io?.to(room).emit(event, payload);
}

export async function initFcm(log = console.log) {
  if (!config.fcmServiceAccount) {
    log('FCM tidak dikonfigurasi: notifikasi push hanya dicatat di log');
    return;
  }
  if (!fs.existsSync(config.fcmServiceAccount) || !fs.statSync(config.fcmServiceAccount).isFile()) {
    log(`FCM_SERVICE_ACCOUNT tidak ditemukan (${config.fcmServiceAccount}): notifikasi push dimatikan`);
    return;
  }
  const admin = (await import('firebase-admin')).default;
  const cred = JSON.parse(fs.readFileSync(config.fcmServiceAccount, 'utf8'));
  admin.initializeApp({ credential: admin.credential.cert(cred) });
  messaging = admin.messaging();
  fcmReady = true;
  log('FCM siap');
}

/**
 * Kirim data message ke semua HP milik userIds.
 * Semua nilai data harus string (aturan FCM).
 */
export async function pushToUsers(userIds, data) {
  const ids = [...new Set(userIds.filter(Boolean))];
  if (!ids.length) return { sent: 0 };
  const payload = Object.fromEntries(Object.entries(data).map(([k, v]) => [k, v === null || v === undefined ? '' : String(v)]));
  const { rows } = await q('SELECT fcm_token FROM devices WHERE user_id = ANY($1::uuid[])', [ids]);
  const tokens = rows.map((r) => r.fcm_token);
  outbox.push({ userIds: ids, tokens, data: payload });
  if (outbox.length > 500) outbox.shift();
  if (!fcmReady || !tokens.length) return { sent: 0, tokens: tokens.length };

  const res = await messaging.sendEachForMulticast({
    tokens,
    data: payload,
    android: { priority: 'high', ttl: 3600 * 1000 },
  });
  // Bersihkan token yang sudah tidak berlaku
  const dead = [];
  res.responses.forEach((r, i) => {
    const code = r.error?.code || '';
    if (code.includes('registration-token-not-registered') || code.includes('invalid-registration-token')) dead.push(tokens[i]);
  });
  if (dead.length) await q('DELETE FROM devices WHERE fcm_token = ANY($1)', [dead]);
  return { sent: res.successCount, failed: res.failureCount };
}

/** Kirim tanpa menunggu: kegagalan push tidak boleh menggagalkan request utama. */
export function pushLater(userIds, data) {
  pushToUsers(userIds, data).catch((e) => console.error('push gagal:', e.message));
}
