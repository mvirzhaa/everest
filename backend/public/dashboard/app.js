// Dashboard Everest untuk satpam dan admin (tanpa framework, tanpa build)
const $ = (s) => document.querySelector(s);
const fmt = new Intl.DateTimeFormat('id-ID', { dateStyle: 'medium', timeStyle: 'short' });
const fmtTime = (iso) => (iso ? fmt.format(new Date(iso)) : '—');
const STATUS_LABEL = {
  aktif: 'Aktif', diterima: 'Diterima', ditangani: 'Ditangani', aman: 'Aman', dibatalkan: 'Dibatalkan',
  baru: 'Baru', diproses: 'Diproses', selesai: 'Selesai', ditolak: 'Ditolak',
};

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}
function ago(iso) {
  const s = Math.round((Date.now() - Date.parse(iso)) / 1000);
  if (s < 60) return `${s} dtk lalu`;
  if (s < 3600) return `${Math.round(s / 60)} mnt lalu`;
  return `${Math.round(s / 3600)} jam lalu`;
}
function toast(msg) {
  const t = document.createElement('div');
  t.className = 'toast';
  t.textContent = msg;
  document.body.append(t);
  setTimeout(() => t.remove(), 3500);
}

const BASE_PATH = window.location.pathname.replace(/\/dashboard\/?.*$/, '');

async function api(path, opts = {}) {
  const fullPath = BASE_PATH + (path.startsWith('/') ? path : '/' + path);
  const r = await fetch(fullPath, {
    credentials: 'same-origin',
    headers: opts.body ? { 'content-type': 'application/json' } : {},
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(data.error || `HTTP ${r.status}`), { status: r.status });
  return data;
}

let me = null;

// ---------- Login ----------
async function boot() {
  try {
    me = await api('/api/me');
  } catch (e) {
    if (e.status === 401) return showLogin();
    $('#login-msg').textContent = e.message;
    return showLogin();
  }
  if (!['guard', 'admin'].includes(me.role)) {
    showLogin();
    $('#login-msg').textContent = `Akun ${me.email} bukan satpam atau admin. Minta admin mengubah peran Anda.`;
    return;
  }
  startApp();
}

async function showLogin() {
  $('#login').classList.remove('hidden');
  const ssoBtn = $('#sso-btn');
  if (ssoBtn) ssoBtn.href = `${BASE_PATH}/auth/web/login`;
  const privLink = $('#privacy-link');
  if (privLink) privLink.href = `${BASE_PATH}/privasi`;
  const cfg = await api('/api/auth/config').catch(() => ({}));
  $('#sso-btn').classList.toggle('hidden', !cfg.sso);
  $('#dev-form').classList.toggle('hidden', !cfg.devLogin);
  if (!cfg.sso && !cfg.devLogin) $('#login-msg').textContent = 'Login SSO belum dikonfigurasi di server.';
}

$('#dev-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  try {
    await api('/api/auth/dev-login?cookie=1', { method: 'POST', body: { email: f.get('email'), role: f.get('role') } });
    location.reload();
  } catch (err) {
    $('#login-msg').textContent = err.message;
  }
});

$('#logout').addEventListener('click', async () => {
  await api('/auth/web/logout', { method: 'POST' }).catch(() => {});
  location.reload();
});

// ---------- Aplikasi ----------
function startApp() {
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  $('#who').textContent = `${me.name} · ${me.role === 'admin' ? 'Admin' : 'Satpam'}`;
  $('#duty').checked = Boolean(me.on_duty);
  document.querySelectorAll('[data-admin]').forEach((b) => b.classList.toggle('hidden', me.role !== 'admin'));
  document.querySelectorAll('nav.tabs button').forEach((b) => b.addEventListener('click', () => selectTab(b.dataset.tab)));
  initMap();
  loadPanics();
  if (me.role === 'admin') loadReportCount();
  connectSocket();
  setInterval(loadPanics, 30000); // cadangan bila socket terputus
}

function selectTab(name) {
  document.querySelectorAll('nav.tabs button').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.tab === name)));
  for (const t of ['panic', 'reports', 'history', 'users', 'audit']) $(`#tab-${t}`).classList.toggle('hidden', t !== name);
  if (name === 'panic') setTimeout(() => map.invalidateSize(), 50);
  if (name === 'reports') loadReports();
  if (name === 'history') loadHistory();
  if (name === 'users') loadUsers();
  if (name === 'audit') loadAudit();
}

$('#duty').addEventListener('change', async (e) => {
  try {
    await api('/api/guard/duty', { method: 'POST', body: { on_duty: e.target.checked } });
    toast(e.target.checked ? 'Anda tercatat sedang bertugas' : 'Status bertugas dimatikan');
  } catch (err) {
    e.target.checked = !e.target.checked;
    toast(err.message);
  }
});

// ---------- Peta & panic ----------
let map;
const markers = new Map(); // panic_id → { marker, trail }
let panics = [];
let selected = null;

function initMap() {
  map = L.map('map').setView([-6.5579, 106.7962], 15); // kampus UIKA Bogor
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; kontributor OpenStreetMap',
  }).addTo(map);
}

const panicIcon = () =>
  L.divIcon({
    className: '',
    html: '<div style="width:22px;height:22px;border-radius:50%;background:var(--danger);border:3px solid #fff;box-shadow:0 0 0 6px rgba(192,53,43,.25)"></div>',
    iconSize: [22, 22],
    iconAnchor: [11, 11],
  });

async function loadPanics() {
  try {
    panics = await api('/api/panic/active');
  } catch (e) {
    if (e.status === 401) location.reload();
    return;
  }
  renderPanics();
}

function renderPanics() {
  const list = $('#panic-list');
  const count = $('#panic-count');
  count.textContent = panics.length;
  count.classList.toggle('hidden', !panics.length);
  document.title = panics.length ? `(${panics.length}) PANIC · Everest` : 'Everest · Dashboard';
  if (!panics.length) {
    list.innerHTML = '<div class="empty">Tidak ada panic aktif.</div>';
  } else {
    list.innerHTML = panics
      .map((p) => {
        const lat = p.last_lat ?? p.lat;
        const lng = p.last_lng ?? p.lng;
        const maps = lat !== null && lat !== undefined ? `https://maps.google.com/?q=${lat},${lng}` : null;
        return `<div class="alert-card ${selected === p.id ? 'selected' : ''}" data-id="${p.id}">
          <div class="row"><span class="name">${esc(p.user_name)}</span><span>${
            p.status === 'aktif' && p.escalation_level > 0
              ? `<span class="pill eskalasi" title="Belum ada yang menekan Terima">Eskalasi ${p.escalation_level === 1 ? 'satpam' : 'admin'}</span> `
              : ''
          }<span class="pill ${p.status} ${p.status === 'aktif' ? 'pulse' : ''}">${STATUS_LABEL[p.status]}</span></span></div>
          <div class="meta">${fmtTime(p.created_at)} · ${ago(p.created_at)}${p.sms_sent ? ' · terkirim saat offline' : ''}</div>
          <div class="meta">${p.user_phone ? `HP ${esc(p.user_phone)} · ` : ''}${p.last_battery ?? p.battery ?? '?'}% baterai · update ${p.last_at ? ago(p.last_at) : '—'}</div>
          ${p.note ? `<div class="meta">Catatan: ${esc(p.note)}</div>` : ''}
          <div class="actions">
            ${p.status === 'aktif' ? `<button class="danger" data-act="diterima">Terima & tangani</button>` : ''}
            ${p.status === 'diterima' ? `<button class="primary" data-act="ditangani">Tandai ditangani</button>` : ''}
            ${maps ? `<a href="${maps}" target="_blank" rel="noopener"><button>Google Maps</button></a>` : ''}
            ${p.live_url ? `<a href="${esc(p.live_url)}" target="_blank" rel="noopener"><button>Peta live</button></a>` : ''}
          </div>
        </div>`;
      })
      .join('');
  }
  // Sinkronkan marker
  const ids = new Set(panics.map((p) => p.id));
  for (const [id, m] of markers) {
    if (!ids.has(id)) {
      m.marker.remove();
      m.trail.remove();
      markers.delete(id);
    }
  }
  for (const p of panics) {
    const lat = p.last_lat ?? p.lat;
    const lng = p.last_lng ?? p.lng;
    if (lat === null || lat === undefined) continue;
    if (!markers.has(p.id)) {
      const marker = L.marker([lat, lng], { icon: panicIcon() }).addTo(map).bindTooltip(esc(p.user_name));
      marker.on('click', () => focusPanic(p.id));
      const trail = L.polyline([], { color: '#c0352b', weight: 3, opacity: 0.7 }).addTo(map);
      markers.set(p.id, { marker, trail, loaded: false });
      if (p.session_id) socket?.emit('watch', p.session_id);
    } else {
      markers.get(p.id).marker.setLatLng([lat, lng]);
    }
  }
  if (!selected && panics[0]) focusPanic(panics[0].id, false);
}

async function focusPanic(id, highlight = true) {
  selected = id;
  const p = panics.find((x) => x.id === id);
  const m = markers.get(id);
  if (m) {
    map.setView(m.marker.getLatLng(), Math.max(map.getZoom(), 17));
    if (!m.loaded && p?.session_id) {
      m.loaded = true;
      const pts = await api(`/api/tracks/${p.session_id}/points`).catch(() => []);
      m.trail.setLatLngs(pts.map((x) => [x.lat, x.lng]));
    }
  }
  if (highlight) renderPanics();
}

$('#panic-list').addEventListener('click', async (e) => {
  const card = e.target.closest('.alert-card');
  if (!card) return;
  const act = e.target.closest('button[data-act]')?.dataset.act;
  if (!act) {
    if (!e.target.closest('a')) focusPanic(card.dataset.id);
    return;
  }
  let note = null;
  if (act === 'ditangani') note = prompt('Catatan penanganan (opsional):') || null;
  try {
    await api(`/api/panic/${card.dataset.id}`, { method: 'PATCH', body: { status: act, note } });
    stopAlarm();
    loadPanics();
  } catch (err) {
    toast(err.message);
  }
});

// ---------- Alarm suara ----------
let alarmTimer = null;
let audioCtx = null;
function startAlarm() {
  if (alarmTimer) return;
  audioCtx ||= new AudioContext();
  const beep = () => {
    const o = audioCtx.createOscillator();
    const g = audioCtx.createGain();
    o.frequency.value = 880;
    g.gain.setValueAtTime(0.0001, audioCtx.currentTime);
    g.gain.exponentialRampToValueAtTime(0.4, audioCtx.currentTime + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + 0.35);
    o.connect(g).connect(audioCtx.destination);
    o.start();
    o.stop(audioCtx.currentTime + 0.4);
  };
  beep();
  alarmTimer = setInterval(() => (panics.some((p) => p.status === 'aktif') ? beep() : stopAlarm()), 1200);
}
function stopAlarm() {
  clearInterval(alarmTimer);
  alarmTimer = null;
}
document.addEventListener('click', () => audioCtx?.resume(), { once: true });

// ---------- Socket real-time ----------
let socket = null;
function connectSocket() {
  socket = io({ path: (BASE_PATH || '') + '/socket.io', transports: ['websocket', 'polling'] });
  socket.on('connect', () => panics.forEach((p) => p.session_id && socket.emit('watch', p.session_id)));
  socket.on('panic', (d) => {
    toast(`PANIC: ${d.user_name}`);
    startAlarm();
    if (Notification?.permission === 'granted') new Notification('PANIC Everest', { body: `${d.user_name} membutuhkan bantuan`, requireInteraction: true });
    loadPanics();
  });
  socket.on('panic_status', () => loadPanics());
  socket.on('panic_escalated', (d) => {
    toast(`ESKALASI: panic belum ditanggapi ${d.minutes} menit`);
    startAlarm();
    loadPanics();
  });
  socket.on('panic_location', (d) => {
    const p = panics.find((x) => x.id === d.panic_id);
    if (!p) return;
    p.last_lat = d.lat;
    p.last_lng = d.lng;
    p.last_at = d.recorded_at;
    p.last_battery = d.battery;
    const m = markers.get(p.id);
    if (m) {
      m.marker.setLatLng([d.lat, d.lng]);
      m.trail.addLatLng([d.lat, d.lng]);
    }
  });
  socket.on('report', (d) => {
    toast(`Laporan baru dari ${d.user_name}`);
    loadReportCount();
    if (!$('#tab-reports').classList.contains('hidden')) loadReports();
  });
}
if ('Notification' in window && Notification.permission === 'default') {
  document.addEventListener('click', () => Notification.requestPermission(), { once: true });
}

// ---------- Laporan ----------
let reports = [];
async function loadReportCount() {
  const r = await api('/api/admin/stats').catch(() => null);
  if (!r) return;
  $('#report-count').textContent = r.reports_new;
  $('#report-count').classList.toggle('hidden', !r.reports_new);
}

let reportView = 'grid';
document.querySelectorAll('.seg [data-view]').forEach((b) =>
  b.addEventListener('click', () => {
    reportView = b.dataset.view;
    document.querySelectorAll('.seg [data-view]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    $('#report-grid').classList.toggle('hidden', reportView !== 'grid');
    $('#report-map-view').classList.toggle('hidden', reportView !== 'map');
    if (reportView === 'map') renderReportMap();
  }),
);

// ---------- Peta sebaran laporan ----------
const REPORT_COLOR = { baru: '#c0352b', diproses: '#e08a00', selesai: '#2f7d4f', ditolak: '#7a7067' };
let reportMap = null;
let reportLayer = null;

function renderReportMap() {
  if (!reportMap) {
    reportMap = L.map('report-map').setView([-6.5579, 106.7962], 15);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; kontributor OpenStreetMap' }).addTo(reportMap);
    reportLayer = L.featureGroup().addTo(reportMap);
    $('#report-legend').innerHTML = Object.entries(REPORT_COLOR)
      .map(([k, c]) => `<div><i style="background:${c}"></i>${STATUS_LABEL[k]}</div>`)
      .join('');
  }
  setTimeout(() => reportMap.invalidateSize(), 50);
  reportLayer.clearLayers();
  const located = reports.filter((r) => r.lat !== null && r.lat !== undefined);
  for (const r of located) {
    const icon = L.divIcon({ className: '', html: `<div class="rpin" style="background:${REPORT_COLOR[r.status] || '#555'}"></div>`, iconSize: [18, 18], iconAnchor: [9, 9] });
    L.marker([r.lat, r.lng], { icon, title: r.description.slice(0, 60) })
      .bindPopup(
        `<div class="rpop" data-id="${r.id}">
          <img src="${esc(r.photo_url)}" alt="">
          <div><span class="pill ${r.status}">${STATUS_LABEL[r.status]}</span> <span class="small muted">${esc(r.category || 'lainnya')}</span></div>
          <div class="small">${esc(r.description.slice(0, 140))}${r.description.length > 140 ? '…' : ''}</div>
          <div class="small muted">${esc(r.user_name)} · ${fmtTime(r.created_at)}</div>
          <button type="button" data-open-report="${r.id}">Detail & tindak lanjut</button>
        </div>`,
      )
      .addTo(reportLayer);
  }
  $('#report-map-count').textContent = `${located.length} dari ${reports.length} laporan berlokasi`;
  const missing = reports.length - located.length;
  $('#report-nolok').textContent = missing ? `${missing} laporan tanpa lokasi hanya tampil di tampilan Daftar.` : '';
  if (located.length) reportMap.fitBounds(reportLayer.getBounds(), { padding: [40, 40], maxZoom: 18 });
}

document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-open-report]');
  if (b) openReportDialog(b.dataset.openReport);
});

async function loadReports() {
  const status = $('#report-filter').value;
  reports = await api(`/api/admin/reports${status ? `?status=${status}` : ''}`).catch((e) => (toast(e.message), []));
  if (reportView === 'map') renderReportMap();
  const grid = $('#report-grid');
  if (!reports.length) {
    grid.innerHTML = '<div class="empty">Tidak ada laporan.</div>';
    return;
  }
  grid.innerHTML = reports
    .map(
      (r) => `<article class="report" data-id="${r.id}">
        <img src="${esc(r.photo_url)}" alt="" loading="lazy">
        <div class="body">
          <div class="row" style="display:flex;justify-content:space-between;gap:8px"><strong>${esc(r.user_name)}</strong><span class="pill ${r.status}">${STATUS_LABEL[r.status]}</span></div>
          <div class="small muted">${fmtTime(r.created_at)} · ${esc(r.category || 'lainnya')}</div>
          <div class="desc">${esc(r.description)}</div>
        </div>
        <div class="foot"><button data-open>Detail & tindak lanjut</button></div>
      </article>`,
    )
    .join('');
}
$('#report-filter').addEventListener('change', loadReports);
$('#report-refresh').addEventListener('click', loadReports);

let openReport = null;
$('#report-grid').addEventListener('click', (e) => {
  const card = e.target.closest('.report');
  if (card) openReportDialog(card.dataset.id);
});
async function openReportDialog(id) {
  const r = await api(`/api/reports/${id}`).catch((err) => toast(err.message));
  if (!r) return;
  openReport = r;
  $('#rd-photo').src = r.photo_url;
  $('#rd-status').className = `pill ${r.status}`;
  $('#rd-status').textContent = STATUS_LABEL[r.status];
  $('#rd-cat').textContent = r.category || '';
  $('#rd-desc').textContent = r.description;
  $('#rd-meta').innerHTML = `${esc(r.user_name)} · ${fmtTime(r.created_at)}${
    r.lat !== null ? ` · <a target="_blank" rel="noopener" href="https://maps.google.com/?q=${r.lat},${r.lng}">lokasi di Google Maps</a>` : ''
  }`;
  $('#rd-history').innerHTML = r.history
    .map((h) => `<li><span class="pill ${h.status}">${STATUS_LABEL[h.status]}</span><span>${fmtTime(h.created_at)} · ${esc(h.actor_name || '')}${h.note ? ` — ${esc(h.note)}` : ''}</span></li>`)
    .join('');
  $('#rd-new-status').value = r.status === 'baru' ? 'diproses' : r.status === 'diproses' ? 'selesai' : r.status;
  $('#rd-note').value = '';
  $('#report-dlg').showModal();
}
$('#rd-close').addEventListener('click', () => $('#report-dlg').close());
$('#rd-save').addEventListener('click', async () => {
  try {
    await api(`/api/admin/reports/${openReport.id}`, { method: 'PATCH', body: { status: $('#rd-new-status').value, note: $('#rd-note').value || null } });
    $('#report-dlg').close();
    toast('Status laporan diperbarui, pelapor diberi notifikasi');
    loadReports();
    loadReportCount();
  } catch (err) {
    toast(err.message);
  }
});

// ---------- Riwayat, pengguna, audit ----------
async function loadHistory() {
  const rows = await api('/api/admin/panics').catch(() => []);
  $('#history-body').innerHTML = rows
    .map(
      (p) => `<tr><td>${fmtTime(p.created_at)}</td><td>${esc(p.user_name)}</td><td><span class="pill ${p.status}">${STATUS_LABEL[p.status]}</span></td>
      <td>${esc(p.acked_by_name || '—')}${p.acked_at ? `<div class="small muted">${fmtTime(p.acked_at)}</div>` : ''}</td>
      <td>${esc(p.resolved_by_name || '—')}${p.resolved_at ? `<div class="small muted">${fmtTime(p.resolved_at)}</div>` : ''}</td>
      <td>${esc(p.note || '')}</td>
      <td>${p.lat !== null ? `<a target="_blank" rel="noopener" href="https://maps.google.com/?q=${p.lat},${p.lng}">Peta</a>` : '—'}</td></tr>`,
    )
    .join('') || '<tr><td colspan="7" class="muted">Belum ada data.</td></tr>';
}

let userTimer;
$('#user-q').addEventListener('input', () => {
  clearTimeout(userTimer);
  userTimer = setTimeout(loadUsers, 300);
});
async function loadUsers() {
  const term = $('#user-q').value.trim();
  const rows = await api(`/api/admin/users${term ? `?q=${encodeURIComponent(term)}` : ''}`).catch(() => []);
  $('#users-body').innerHTML = rows
    .map(
      (u) => `<tr><td>${esc(u.name)}</td><td>${esc(u.email)}</td><td>${esc(u.phone || '—')}</td>
      <td><select data-user="${u.id}" ${u.id === me.id ? 'disabled' : ''}>
        ${['user', 'guard', 'admin'].map((r) => `<option value="${r}" ${u.role === r ? 'selected' : ''}>${{ user: 'Pengguna', guard: 'Satpam', admin: 'Admin' }[r]}</option>`).join('')}
      </select></td><td>${u.role === 'user' ? '—' : u.on_duty ? 'Ya' : 'Tidak'}</td></tr>`,
    )
    .join('');
}
$('#users-body').addEventListener('change', async (e) => {
  const sel = e.target.closest('select[data-user]');
  if (!sel) return;
  try {
    await api(`/api/admin/users/${sel.dataset.user}`, { method: 'PATCH', body: { role: sel.value } });
    toast('Peran diperbarui');
  } catch (err) {
    toast(err.message);
    loadUsers();
  }
});

async function loadAudit() {
  const rows = await api('/api/admin/audit').catch(() => []);
  $('#audit-body').innerHTML = rows
    .map((l) => `<tr><td>${fmtTime(l.created_at)}</td><td>${esc(l.actor_name || '—')}</td><td>${esc(l.action)}</td><td class="small muted">${esc(l.target || '')}</td></tr>`)
    .join('');
}

boot();
