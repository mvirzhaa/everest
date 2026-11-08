import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const envFile = path.join(root, '.env');
if (fs.existsSync(envFile) && typeof process.loadEnvFile === 'function') {
  try {
    process.loadEnvFile(envFile);
  } catch {
    /* abaikan jika sudah dimuat atau tidak bisa dibaca */
  }
}

const env = process.env;
const bool = (v, d = false) => (v === undefined ? d : ['1', 'true', 'yes'].includes(String(v).toLowerCase()));
const list = (v) => (v ? v.split(',').map((s) => s.trim().toLowerCase()).filter(Boolean) : []);

const publicUrl = (env.PUBLIC_URL || `http://localhost:${env.PORT || 8080}`).replace(/\/$/, '');
let basePath = '';
try {
  basePath = new URL(publicUrl).pathname.replace(/\/$/, '');
} catch {
  basePath = '';
}

export const config = {
  port: Number(env.PORT || 8080),
  publicUrl,
  basePath,
  databaseUrl: env.DATABASE_URL || 'postgres://everest:everest@localhost:5432/everest',
  jwtSecret: env.JWT_SECRET || 'ganti-rahasia-ini-di-produksi',
  jwtTtl: env.JWT_TTL || '30d',
  // Kunci AES-256 (64 karakter hex) untuk mengenkripsi refresh token Google
  encKey: env.ENC_KEY || '',
  devLogin: bool(env.DEV_LOGIN, false),
  adminEmails: list(env.ADMIN_EMAILS),
  uploadDir: path.resolve(env.UPLOAD_DIR || path.join(root, 'data/uploads')),
  modelsDir: path.resolve(env.MODELS_DIR || path.join(root, 'data/models')),
  publicDir: path.join(root, 'public'),
  oidc: {
    issuer: env.OIDC_ISSUER || '',
    // client id aplikasi Android dan web (boleh beda; dipisah koma)
    audiences: (env.OIDC_AUDIENCES || '').split(',').map((s) => s.trim()).filter(Boolean),
    webClientId: env.OIDC_WEB_CLIENT_ID || '',
    webClientSecret: env.OIDC_WEB_CLIENT_SECRET || '',
    scope: env.OIDC_SCOPE || 'openid profile email',
  },
  google: {
    clientId: env.GOOGLE_CLIENT_ID || '',
    clientSecret: env.GOOGLE_CLIENT_SECRET || '',
  },
  fcmServiceAccount: env.FCM_SERVICE_ACCOUNT || '',
  llm: {
    provider: (env.LLM_PROVIDER || '').toLowerCase(), // 'anthropic' | 'gemini' | '' (mati)
    apiKey: env.LLM_API_KEY || '',
    model: env.LLM_MODEL || '',
    dailyQuota: Number(env.FARHAN_DAILY_QUOTA || 50),
  },
  retention: {
    trackDays: Number(env.TRACK_RETENTION_DAYS || 30),
  },
  escalation: {
    // Detik sejak panic diterima server tanpa ada yang menekan "Terima"
    guardsSec: Number(env.ESCALATE_GUARDS_SEC || 120), // tahap 1: semua satpam (termasuk yang tidak bertugas)
    adminsSec: Number(env.ESCALATE_ADMINS_SEC || 300), // tahap 2: admin
    checkSec: Number(env.ESCALATE_CHECK_SEC || 15),
  },
  // Nomor pos satpam yang disarankan ke pengirim saat panic dieskalasi (opsional)
  securityPhone: env.SECURITY_PHONE || '',
  privacyVersion: env.PRIVACY_VERSION || '2026-11-07',
};

export function assertProductionConfig() {
  const problems = [];
  if (config.jwtSecret === 'ganti-rahasia-ini-di-produksi') problems.push('JWT_SECRET belum diisi');
  if (config.devLogin) problems.push('DEV_LOGIN aktif (matikan di produksi)');
  if (!config.oidc.issuer) problems.push('OIDC_ISSUER belum diisi (login SSO tidak akan jalan)');
  if (!/^[0-9a-f]{64}$/i.test(config.encKey)) problems.push('ENC_KEY belum diisi (64 karakter hex) — tautan Google Calendar dinonaktifkan');
  return problems;
}
