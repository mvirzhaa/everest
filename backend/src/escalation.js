import { config } from './config.js';
import { q, one, audit } from './db.js';
import { emit, pushToUsers } from './notify.js';

/**
 * Eskalasi panic yang belum ditanggapi (status masih 'aktif').
 *   Tahap 1 (ESCALATE_GUARDS_SEC, bawaan 2 menit): ke SEMUA satpam, termasuk yang tidak bertugas.
 *   Tahap 2 (ESCALATE_ADMINS_SEC, bawaan 5 menit): ke semua admin.
 * Setiap tahap juga memberi tahu pengirim bahwa panic-nya belum direspons dan sedang diteruskan.
 *
 * UPDATE ... WHERE escalation_level = n bersifat atomik, jadi aman walau server berjalan lebih dari satu instans.
 */
export async function escalatePanics({ now = new Date() } = {}) {
  const done = [];
  const stages = [
    { level: 1, from: 0, sec: config.escalation.guardsSec, roles: ['guard'] },
    { level: 2, from: 1, sec: config.escalation.adminsSec, roles: ['admin'] },
  ];
  for (const st of stages) {
    const { rows } = await q(
      `UPDATE panic_events SET escalation_level = $1, escalated_at = $3
        WHERE status = 'aktif' AND escalation_level = $2
          AND received_at <= $3::timestamptz - make_interval(secs => $4)
        RETURNING *`,
      [st.level, st.from, now.toISOString(), st.sec],
    );
    for (const p of rows) {
      const n = await escalateOne(p, st, now).catch((e) => {
        console.error('eskalasi gagal:', p.id, e.message);
        return 0;
      });
      done.push({ panic_id: p.id, level: st.level, recipients: n });
    }
  }
  return done;
}

async function escalateOne(p, st, now) {
  const owner = await one('SELECT name, phone FROM users WHERE id = $1', [p.user_id]);
  const s = await one('SELECT id, share_token FROM track_sessions WHERE panic_id = $1', [p.id]);
  const targets = (await q('SELECT id FROM users WHERE role = ANY($1::text[]) AND id <> $2', [st.roles, p.user_id])).rows.map((r) => r.id);
  if (s) {
    for (const uid of targets) {
      await q('INSERT INTO track_recipients (session_id, recipient_user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING', [s.id, uid]);
    }
  }
  const last = s
    ? await one('SELECT lat, lng FROM location_points WHERE session_id = $1 ORDER BY recorded_at DESC LIMIT 1', [s.id])
    : null;
  const lat = last?.lat ?? p.lat;
  const lng = last?.lng ?? p.lng;
  const minutes = Math.max(1, Math.round((now - new Date(p.received_at)) / 60000));
  const data = {
    type: 'panic',
    role: 'guard',
    escalation: st.level,
    escalation_text: `Belum ada yang menanggapi selama ${minutes} menit`,
    panic_id: p.id,
    user_id: p.user_id,
    user_name: owner?.name || '',
    user_phone: owner?.phone || '',
    lat: lat ?? '',
    lng: lng ?? '',
    battery: p.battery ?? '',
    created_at: new Date(p.created_at).toISOString(),
    maps_url: lat === null || lat === undefined ? '' : `https://maps.google.com/?q=${lat},${lng}`,
    live_url: s ? `${config.publicUrl}/live/${s.share_token}` : '',
    session_id: s?.id || '',
  };
  await pushToUsers(targets, data);
  emit('guards', 'panic_escalated', { panic_id: p.id, level: st.level, minutes });

  const help = config.securityPhone ? ` Telepon pos satpam ${config.securityPhone} atau 112.` : ' Bila memungkinkan, telepon 112.';
  const message =
    st.level === 1
      ? `Belum ada petugas yang merespons. Panic Anda diteruskan ke semua satpam.${help}`
      : `Panic Anda masih belum direspons dan sudah diteruskan ke admin keamanan kampus.${help}`;
  await pushToUsers([p.user_id], { type: 'panic_escalated', panic_id: p.id, level: st.level, message });
  emit(`user:${p.user_id}`, 'panic_escalated', { panic_id: p.id, level: st.level, message });
  await audit(null, 'panic_escalate', p.id, { level: st.level, recipients: targets.length, minutes });
  return targets.length;
}
