// Tes login SSO: penerbit OIDC palsu lokal (discovery + JWKS), token ditandatangani RS256.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

process.env.DATABASE_URL ||= 'postgres://everest:everest@localhost:5432/everest_test';
process.env.ADMIN_EMAILS = 'rektor@uika.test';

const { default: request } = await import('supertest');
const { generateKeyPair, exportJWK, SignJWT } = await import('jose');
const { config } = await import('../src/config.js');
const { pool } = await import('../src/db.js');
const { migrate } = await import('../src/migrate.js');
const { createApp } = await import('../src/app.js');

let idp, issuer, privateKey, app;
const AUD = 'everest-android';

async function idToken(claims, opts = {}) {
  return new SignJWT(claims)
    .setProtectedHeader({ alg: 'RS256', kid: 'k1' })
    .setIssuer(opts.issuer || issuer)
    .setAudience(opts.aud || AUD)
    .setSubject(claims.sub || 'sub-1')
    .setIssuedAt()
    .setExpirationTime(opts.exp || '5m')
    .sign(opts.key || privateKey);
}

before(async () => {
  await migrate(() => {});
  const kp = await generateKeyPair('RS256');
  privateKey = kp.privateKey;
  const jwk = { ...(await exportJWK(kp.publicKey)), kid: 'k1', alg: 'RS256', use: 'sig' };
  idp = http.createServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    if (req.url === '/.well-known/openid-configuration') {
      return res.end(JSON.stringify({ issuer, jwks_uri: `${issuer}/jwks`, authorization_endpoint: `${issuer}/auth`, token_endpoint: `${issuer}/token` }));
    }
    if (req.url === '/jwks') return res.end(JSON.stringify({ keys: [jwk] }));
    res.statusCode = 404;
    res.end('{}');
  });
  await new Promise((r) => idp.listen(0, '127.0.0.1', r));
  issuer = `http://127.0.0.1:${idp.address().port}`;
  config.oidc.issuer = issuer;
  config.oidc.audiences = [AUD];
  app = createApp();
});

after(async () => {
  await new Promise((r) => idp.close(r));
  await pool.end();
});

test('id_token sah dari SSO menghasilkan sesi Everest', async () => {
  const tok = await idToken({ sub: 'nim-2201', email: 'Dewi@UIKA.test', name: 'Dewi Lestari' });
  const r = await request(app).post('/api/auth/oidc').send({ id_token: tok }).expect(200);
  assert.equal(r.body.user.email, 'dewi@uika.test');
  assert.equal(r.body.user.role, 'user');
  const me = await request(app).get('/api/me').set('Authorization', `Bearer ${r.body.token}`).expect(200);
  assert.equal(me.body.name, 'Dewi Lestari');
});

test('email di ADMIN_EMAILS otomatis jadi admin', async () => {
  config.adminEmails = ['rektor@uika.test'];
  const r = await request(app).post('/api/auth/oidc').send({ id_token: await idToken({ sub: 'x9', email: 'rektor@uika.test', name: 'Rektor' }) }).expect(200);
  assert.equal(r.body.user.role, 'admin');
});

test('token palsu, audiens salah, atau kedaluwarsa ditolak', async () => {
  const other = await generateKeyPair('RS256');
  await request(app).post('/api/auth/oidc').send({ id_token: await idToken({ email: 'a@b.c' }, { key: other.privateKey }) }).expect(401);
  await request(app).post('/api/auth/oidc').send({ id_token: await idToken({ email: 'a@b.c' }, { aud: 'aplikasi-lain' }) }).expect(401);
  await request(app).post('/api/auth/oidc').send({ id_token: await idToken({ email: 'a@b.c' }, { exp: Math.floor(Date.now() / 1000) - 600 }) }).expect(401);
  await request(app).post('/api/auth/oidc').send({ id_token: await idToken({ sub: 'tanpa-email' }) }).expect(401);
  await request(app).post('/api/auth/dev-login').send({ email: 'x@y.z' }).expect(404); // DEV_LOGIN mati
});
