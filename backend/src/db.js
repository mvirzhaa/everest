import pg from 'pg';
import { config } from './config.js';

// timestamptz dikembalikan sebagai string ISO, bukan Date, agar JSON konsisten
pg.types.setTypeParser(1184, (v) => (v === null ? null : new Date(v).toISOString()));

export const pool = new pg.Pool({ connectionString: config.databaseUrl, max: 10 });

export const q = (text, params) => pool.query(text, params);

export async function one(text, params) {
  const { rows } = await pool.query(text, params);
  return rows[0] || null;
}

export async function tx(fn) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (e) {
    await client.query('ROLLBACK');
    throw e;
  } finally {
    client.release();
  }
}

export async function audit(actorId, action, target, meta) {
  await pool.query('INSERT INTO audit_log(actor_id, action, target, meta) VALUES ($1,$2,$3,$4)', [
    actorId || null,
    action,
    target || null,
    meta ? JSON.stringify(meta) : null,
  ]);
}
