import fs from 'node:fs';
import path from 'node:path';
import express from 'express';
import helmet from 'helmet';
import cookieParser from 'cookie-parser';
import multer from 'multer';
import { config } from './config.js';
import { router as authRoutes } from './routes/auth.js';
import { router as meRoutes } from './routes/me.js';
import { router as noteRoutes } from './routes/notes.js';
import { router as safetyRoutes } from './routes/safety.js';
import { router as reportRoutes } from './routes/reports.js';
import { router as farhanRoutes } from './routes/farhan.js';
import { router as adminRoutes } from './routes/admin.js';
import { HttpError } from './util.js';

export function createApp() {
  const app = express();
  app.set('trust proxy', 1);
  app.disable('x-powered-by');
  app.use(
    helmet({
      contentSecurityPolicy: {
        directives: {
          defaultSrc: ["'self'"],
          scriptSrc: ["'self'"],
          styleSrc: ["'self'", "'unsafe-inline'"],
          imgSrc: [
            "'self'",
            'data:',
            'blob:',
            'https://server.arcgisonline.com',
            'https://services.arcgisonline.com',
            'https://*.arcgisonline.com',
            'https://tile.openstreetmap.org',
            'https://*.tile.openstreetmap.org',
          ],
          connectSrc: ["'self'", 'ws:', 'wss:'],
        },
      },
      crossOriginEmbedderPolicy: false,
    }),
  );
  app.use(cookieParser());
  app.use(express.json({ limit: '20mb' }));

  // Jika PUBLIC_URL memiliki subpath (mis. /everest), normalisasi req.url bila reverse proxy meneruskannya apa adanya
  if (config.basePath) {
    app.use((req, res, next) => {
      if (req.url === config.basePath) return res.redirect(`${config.basePath}/dashboard/`);
      if (req.url.startsWith(`${config.basePath}/`)) {
        req.url = req.url.slice(config.basePath.length);
      }
      next();
    });
  }

  app.get('/healthz', (req, res) => res.json({ ok: true, time: new Date().toISOString() }));

  app.use(authRoutes);
  app.use(meRoutes);
  app.use(noteRoutes);
  app.use(safetyRoutes);
  app.use(reportRoutes);
  app.use(farhanRoutes);
  app.use(adminRoutes);

  // Model whisper untuk diunduh HP (letakkan file ggml-*.bin di MODELS_DIR)
  fs.mkdirSync(config.modelsDir, { recursive: true });
  app.get('/api/models', (req, res) => {
    const files = fs.readdirSync(config.modelsDir).filter((f) => /^ggml-[\w.-]+\.bin$/.test(f));
    res.json(files.map((f) => ({ name: f, size: fs.statSync(path.join(config.modelsDir, f)).size, url: `${config.publicUrl}/models/${f}` })));
  });
  app.use('/models', express.static(config.modelsDir, { immutable: true, maxAge: '30d' }));

  // Halaman web: dashboard admin/satpam dan peta live
  app.use('/dashboard', express.static(path.join(config.publicDir, 'dashboard')));
  app.use('/assets', express.static(path.join(config.publicDir, 'assets')));
  app.get('/live/:token', (req, res) => res.sendFile(path.join(config.publicDir, 'live.html')));
  app.get('/privasi', (req, res) => res.sendFile(path.join(config.publicDir, 'privasi.html')));
  app.get('/', (req, res) => res.redirect(`${config.basePath}/dashboard/`));

  app.use((req, res) => res.status(404).json({ error: 'Tidak ditemukan' }));
  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    if (err instanceof HttpError) return res.status(err.status).json({ error: err.message });
    if (err instanceof multer.MulterError) {
      return res.status(err.code === 'LIMIT_FILE_SIZE' ? 413 : 400).json({ error: `Unggahan ditolak: ${err.message}` });
    }
    if (err.type === 'entity.too.large') return res.status(413).json({ error: 'Data terlalu besar' });
    if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'JSON tidak valid' });
    console.error(err);
    res.status(500).json({ error: 'Terjadi kesalahan di server' });
  });
  return app;
}
