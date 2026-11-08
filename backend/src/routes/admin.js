import express from 'express';
import { q, one, tx, audit } from '../db.js';
import { requireAuth, requireRole } from '../auth.js';
import { pushLater } from '../notify.js';
import { REPORT_STATUSES, withPhotoUrl } from './reports.js';
import { HttpError, bad, h, notFound, optStr, reqUuid } from '../util.js';

export const router = express.Router();
router.use('/api/admin', requireAuth, requireRole('admin'));

router.get(
  '/api/admin/stats',
  h(async (req, res) => {
    const s = await one(`SELECT
      (SELECT count(*)::int FROM users) AS users,
      (SELECT count(*)::int FROM users WHERE role = 'guard') AS guards,
      (SELECT count(*)::int FROM guard_duty WHERE on_duty) AS guards_on_duty,
      (SELECT count(*)::int FROM panic_events WHERE status IN ('aktif','diterima')) AS panic_active,
      (SELECT count(*)::int FROM reports WHERE status = 'baru') AS reports_new,
      (SELECT count(*)::int FROM reports WHERE status = 'diproses') AS reports_in_progress`);
    res.json(s);
  }),
);

router.get(
  '/api/admin/users',
  h(async (req, res) => {
    const term = optStr(req.query.q, 'q', 100);
    const { rows } = await q(
      `SELECT u.id, u.email, u.name, u.phone, u.role, u.created_at, COALESCE(g.on_duty,false) AS on_duty
         FROM users u LEFT JOIN guard_duty g ON g.user_id = u.id
        WHERE $1::text IS NULL OR u.email ILIKE '%'||$1||'%' OR u.name ILIKE '%'||$1||'%'
        ORDER BY u.role DESC, u.name LIMIT 300`,
      [term],
    );
    res.json(rows);
  }),
);

router.patch(
  '/api/admin/users/:id',
  h(async (req, res) => {
    const id = reqUuid(req.params.id, 'id');
    const role = String(req.body?.role || '');
    if (!['user', 'guard', 'admin'].includes(role)) throw bad('role tidak valid');
    if (id === req.user.id && role !== 'admin') throw bad('Tidak bisa menurunkan peran diri sendiri');
    const u = await one('UPDATE users SET role = $2 WHERE id = $1 RETURNING id, email, name, role', [id, role]);
    if (!u) throw notFound();
    if (role === 'user') await q('DELETE FROM guard_duty WHERE user_id = $1', [id]);
    await audit(req.user.id, 'role_change', id, { role });
    res.json(u);
  }),
);

router.get(
  '/api/admin/reports',
  h(async (req, res) => {
    const status = REPORT_STATUSES.has(req.query.status) ? req.query.status : null;
    const { rows } = await q(
      `SELECT r.*, u.name AS user_name, u.email AS user_email
         FROM reports r JOIN users u ON u.id = r.user_id
        WHERE $1::text IS NULL OR r.status = $1
        ORDER BY r.created_at DESC LIMIT 300`,
      [status],
    );
    res.json(rows.map(withPhotoUrl));
  }),
);

router.patch(
  '/api/admin/reports/:id',
  h(async (req, res) => {
    const id = reqUuid(req.params.id, 'id');
    const status = String(req.body?.status || '');
    if (!REPORT_STATUSES.has(status)) throw bad('status tidak valid');
    const note = optStr(req.body?.note, 'note', 2000);
    const current = await one('SELECT * FROM reports WHERE id = $1', [id]);
    if (!current) throw notFound();
    if (current.status === status && !note) throw new HttpError(409, 'Status sudah sama');
    const r = await tx(async (c) => {
      const u = (await c.query('UPDATE reports SET status = $2, updated_at = now() WHERE id = $1 RETURNING *', [id, status])).rows[0];
      await c.query('INSERT INTO report_status_log (report_id, status, note, actor_id) VALUES ($1,$2,$3,$4)', [id, status, note, req.user.id]);
      return u;
    });
    pushLater([r.user_id], { type: 'report_status', report_id: id, status, note: note || '' });
    await audit(req.user.id, 'report_status', id, { status });
    res.json(withPhotoUrl(r));
  }),
);

router.get(
  '/api/admin/panics',
  h(async (req, res) => {
    const { rows } = await q(
      `SELECT p.*, u.name AS user_name, u.phone AS user_phone, a.name AS acked_by_name, r.name AS resolved_by_name, s.id AS session_id
         FROM panic_events p JOIN users u ON u.id = p.user_id
         LEFT JOIN users a ON a.id = p.acked_by LEFT JOIN users r ON r.id = p.resolved_by
         LEFT JOIN track_sessions s ON s.panic_id = p.id
        ORDER BY p.created_at DESC LIMIT 300`,
    );
    res.json(rows);
  }),
);

router.get(
  '/api/admin/audit',
  h(async (req, res) => {
    const { rows } = await q(
      `SELECT l.id, l.action, l.target, l.meta, l.created_at, u.name AS actor_name
         FROM audit_log l LEFT JOIN users u ON u.id = l.actor_id ORDER BY l.id DESC LIMIT 300`,
    );
    res.json(rows);
  }),
);
