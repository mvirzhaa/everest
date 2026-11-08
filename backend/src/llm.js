import { config } from './config.js';
import { HttpError } from './util.js';

/**
 * Lapisan LLM untuk Farhan jalur cloud. Penyedia: 'anthropic' (Claude) atau 'gemini'.
 * Alur: model boleh memanggil alat (tools) yang dijalankan server, berulang maks. 4 putaran.
 * Alat yang MENGUBAH sesuatu (usulkan_jadwal) tidak dijalankan di server: dikembalikan
 * sebagai `action` agar HP meminta konfirmasi lisan pengguna dulu.
 */

export const TOOLS = [
  {
    name: 'cari_catatan',
    description:
      'Cari potongan transkrip rekaman milik pengguna berdasarkan kata kunci dan/atau rentang tanggal. Gunakan sebelum menjawab pertanyaan tentang isi rekaman.',
    schema: {
      type: 'object',
      properties: {
        kata_kunci: { type: 'string', description: 'Kata kunci pencarian (boleh kosong bila hanya berdasarkan tanggal)' },
        dari: { type: 'string', description: 'Tanggal/waktu awal ISO 8601 (opsional)' },
        sampai: { type: 'string', description: 'Tanggal/waktu akhir ISO 8601, eksklusif (opsional)' },
      },
    },
  },
  {
    name: 'lihat_jadwal',
    description: 'Lihat jadwal Google Calendar pengguna pada rentang waktu tertentu.',
    schema: {
      type: 'object',
      properties: {
        dari: { type: 'string', description: 'Awal rentang, ISO 8601 dengan offset zona waktu' },
        sampai: { type: 'string', description: 'Akhir rentang, ISO 8601 dengan offset zona waktu' },
      },
      required: ['dari', 'sampai'],
    },
  },
  {
    name: 'usulkan_jadwal',
    description:
      'Usulkan pembuatan event Google Calendar. Event BELUM dibuat: aplikasi akan meminta konfirmasi pengguna. Gunakan saat pengguna meminta menjadwalkan sesuatu.',
    schema: {
      type: 'object',
      properties: {
        judul: { type: 'string' },
        mulai: { type: 'string', description: 'ISO 8601 dengan offset, mis. 2026-11-07T09:00:00+07:00' },
        selesai: { type: 'string', description: 'ISO 8601 dengan offset (opsional, default 1 jam)' },
        lokasi: { type: 'string' },
        catatan: { type: 'string' },
      },
      required: ['judul', 'mulai'],
    },
  },
];

let testProvider = null;
/** Untuk pengujian: ganti penyedia dengan fungsi palsu. */
export function setTestProvider(fn) {
  testProvider = fn;
}

export function llmEnabled() {
  return Boolean(testProvider) || (Boolean(config.llm.provider) && Boolean(config.llm.apiKey));
}

/**
 * @param {object} p
 * @param {string} p.system
 * @param {string} p.question
 * @param {(name:string, input:object)=>Promise<object>} p.runTool
 */
export async function runFarhan({ system, question, runTool }) {
  if (testProvider) return testProvider({ system, question, runTool });
  if (!llmEnabled()) throw new HttpError(503, 'Farhan cloud belum dikonfigurasi di server');
  if (config.llm.provider === 'anthropic') return runAnthropic({ system, question, runTool });
  if (config.llm.provider === 'gemini') return runGemini({ system, question, runTool });
  throw new HttpError(503, `LLM_PROVIDER tidak dikenal: ${config.llm.provider}`);
}

async function postJson(url, headers, body) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 45000);
  try {
    const r = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body), signal: ctrl.signal });
    const json = await r.json().catch(() => ({}));
    if (!r.ok) throw new HttpError(502, `LLM error ${r.status}: ${json.error?.message || 'tanpa pesan'}`);
    return json;
  } catch (e) {
    if (e.name === 'AbortError') throw new HttpError(504, 'LLM tidak merespons');
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

async function runAnthropic({ system, question, runTool }) {
  const model = config.llm.model || 'claude-haiku-5-5';
  const tools = TOOLS.map((t) => ({ name: t.name, description: t.description, input_schema: t.schema }));
  const messages = [{ role: 'user', content: question }];
  for (let turn = 0; turn < 5; turn++) {
    const res = await postJson(
      'https://api.anthropic.com/v1/messages',
      { 'x-api-key': config.llm.apiKey, 'anthropic-version': '2023-06-01' },
      { model, max_tokens: 1024, system, tools, messages },
    );
    const uses = (res.content || []).filter((b) => b.type === 'tool_use');
    if (res.stop_reason !== 'tool_use' || !uses.length || turn === 4) {
      return (res.content || []).filter((b) => b.type === 'text').map((b) => b.text).join('\n').trim();
    }
    messages.push({ role: 'assistant', content: res.content });
    const results = [];
    for (const u of uses) {
      const out = await runTool(u.name, u.input || {});
      results.push({ type: 'tool_result', tool_use_id: u.id, content: JSON.stringify(out) });
    }
    messages.push({ role: 'user', content: results });
  }
  return '';
}

async function runGemini({ system, question, runTool }) {
  if (!config.llm.model) throw new HttpError(503, 'LLM_MODEL wajib diisi untuk Gemini');
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(config.llm.model)}:generateContent`;
  const tools = [{ functionDeclarations: TOOLS.map((t) => ({ name: t.name, description: t.description, parameters: t.schema })) }];
  const contents = [{ role: 'user', parts: [{ text: question }] }];
  for (let turn = 0; turn < 5; turn++) {
    const res = await postJson(url, { 'x-goog-api-key': config.llm.apiKey }, { systemInstruction: { parts: [{ text: system }] }, contents, tools });
    const parts = res.candidates?.[0]?.content?.parts || [];
    const calls = parts.filter((p) => p.functionCall);
    if (!calls.length || turn === 4) return parts.map((p) => p.text || '').join('').trim();
    contents.push({ role: 'model', parts });
    const responses = [];
    for (const c of calls) {
      const out = await runTool(c.functionCall.name, c.functionCall.args || {});
      responses.push({ functionResponse: { name: c.functionCall.name, response: out } });
    }
    contents.push({ role: 'user', parts: responses });
  }
  return '';
}
