import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Readable } from 'node:stream';
import { finished } from 'node:stream/promises';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const dir = process.env.MODELS_DIR || path.resolve(__dirname, '../data/models');
fs.mkdirSync(dir, { recursive: true });

const base = 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main';
const models = ['ggml-base-q5_1.bin', 'ggml-small-q5_1.bin'];

for (const m of models) {
  const target = path.join(dir, m);
  if (fs.existsSync(target) && fs.statSync(target).size > 0) {
    console.log(`sudah ada: ${m} (${(fs.statSync(target).size / 1024 / 1024).toFixed(1)} MB)`);
    continue;
  }
  console.log(`mengunduh ${m} ...`);
  const res = await fetch(`${base}/${m}`);
  if (!res.ok) throw new Error(`Gagal mengunduh ${m}: HTTP ${res.status}`);
  const part = `${target}.part`;
  const fileStream = fs.createWriteStream(part);
  await finished(Readable.fromWeb(res.body).pipe(fileStream));
  fs.renameSync(part, target);
  console.log(`selesai: ${m} (${(fs.statSync(target).size / 1024 / 1024).toFixed(1)} MB)`);
}
console.log('Semua model siap di:', dir);

