// Peta live: dibuka lewat token tautan dari notifikasi (web) atau disematkan di aplikasi (?embed=1).
(function () {
  const token = location.pathname.split('/').pop();
  const params = new URLSearchParams(location.search);
  if (params.get('embed') === '1') document.body.classList.add('embed');
  const $ = (s) => document.querySelector(s);
  const fmt = new Intl.DateTimeFormat('id-ID', { dateStyle: 'medium', timeStyle: 'medium' });
  const STATUS = { aktif: 'Aktif', diterima: 'Diterima petugas', ditangani: 'Ditangani', aman: 'Aman', dibatalkan: 'Dibatalkan' };
  const CLOSED = new Set(['ditangani', 'aman', 'dibatalkan']);

  const map = L.map('livemap', { zoomControl: true }).setView([-6.5579, 106.7962], 15);
  L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
    maxZoom: 19,
    subdomains: 'abcd',
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/">CARTO</a>',
  }).addTo(map);
  const trail = L.polyline([], { color: '#f26b0f', weight: 4, opacity: 0.85 }).addTo(map);
  let marker = null;
  let last = null;
  let pts = [];
  let me = null; // { lat, lng, acc }
  let meMarker = null;
  let meCircle = null;
  let follow = true;

  // Geser peta oleh pengguna = berhenti mengikuti otomatis
  map.on('dragstart', () => setFollow(false));

  function setFollow(on) {
    follow = on;
    const b = $('#follow-btn');
    b.setAttribute('aria-pressed', String(on));
    b.textContent = on ? 'Ikuti: nyala' : 'Ikuti: mati';
    if (on) frame();
  }
  $('#follow-btn').addEventListener('click', () => setFollow(!follow));

  /** Arahkan peta: korban saja, atau korban + posisi saya bila keduanya ada. */
  function frame() {
    if (!last) return;
    const target = L.latLng(last.lat, last.lng);
    if (me) map.fitBounds(L.latLngBounds([target, L.latLng(me.lat, me.lng)]), { padding: [60, 60], maxZoom: 18 });
    else map.setView(target, Math.max(map.getZoom(), 17));
  }

  function haversine(a, b) {
    const R = 6371000;
    const rad = (d) => (d * Math.PI) / 180;
    const dLat = rad(b.lat - a.lat);
    const dLng = rad(b.lng - a.lng);
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(h));
  }
  function bearing(a, b) {
    const rad = (d) => (d * Math.PI) / 180;
    const y = Math.sin(rad(b.lng - a.lng)) * Math.cos(rad(b.lat));
    const x = Math.cos(rad(a.lat)) * Math.sin(rad(b.lat)) - Math.sin(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.cos(rad(b.lng - a.lng));
    const deg = ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
    return ['utara', 'timur laut', 'timur', 'tenggara', 'selatan', 'barat daya', 'barat', 'barat laut'][Math.round(deg / 45) % 8];
  }
  function updateDistance() {
    if (!me || !last) return;
    const d = haversine(me, last);
    const txt = d < 1000 ? `${Math.round(d)} m` : `${(d / 1000).toFixed(d < 10000 ? 1 : 0)} km`;
    $('#dist').textContent = `Jarak dari Anda: ${txt} ke arah ${bearing(me, last)}`;
  }

  function setLast(p) {
    last = p;
    const ll = [p.lat, p.lng];
    if (!marker) {
      marker = L.circleMarker(ll, { radius: 10, color: '#fff', weight: 3, fillColor: '#c0352b', fillOpacity: 1 }).addTo(map);
      map.setView(ll, 17);
    } else {
      marker.setLatLng(ll);
    }
    for (const id of ['#gmaps', '#nav']) $(id).classList.remove('hidden');
    $('#gmaps').href = `https://maps.google.com/?q=${p.lat},${p.lng}`;
    $('#nav').href = `https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lng}`;
    $('#updated').textContent = `Posisi terakhir ${fmt.format(new Date(p.recorded_at))}${p.battery !== null && p.battery !== undefined ? ` · baterai ${p.battery}%` : ''}`;
    updateDistance();
    if (follow) frame();
  }

  function setStatus(status) {
    $('#status').className = `pill ${status}`;
    $('#status').textContent = STATUS[status] || status;
  }
  function ended(at) {
    $('#status').className = 'pill aman';
    $('#status').textContent = `Berbagi lokasi berakhir ${at ? fmt.format(new Date(at)) : ''}`;
  }

  // ---- Lokasi saya ----
  $('#me-btn').addEventListener('click', () => {
    if (!('geolocation' in navigator)) {
      $('#dist').textContent = 'Peramban ini tidak mendukung lokasi';
      return;
    }
    $('#me-btn').disabled = true;
    $('#dist').textContent = 'Mencari lokasi Anda…';
    navigator.geolocation.watchPosition(
      (pos) => {
        me = { lat: pos.coords.latitude, lng: pos.coords.longitude, acc: pos.coords.accuracy };
        const ll = [me.lat, me.lng];
        if (!meMarker) {
          meMarker = L.marker(ll, { icon: L.divIcon({ className: '', html: '<div class="me-dot"></div>', iconSize: [16, 16], iconAnchor: [8, 8] }) })
            .addTo(map)
            .bindTooltip('Anda');
          meCircle = L.circle(ll, { radius: me.acc, color: '#1a73e8', weight: 1, fillOpacity: 0.08 }).addTo(map);
          if (follow) frame();
        } else {
          meMarker.setLatLng(ll);
          meCircle.setLatLng(ll).setRadius(me.acc);
        }
        $('#me-btn').textContent = 'Lokasi saya: aktif';
        updateDistance();
      },
      (err) => {
        $('#me-btn').disabled = false;
        $('#dist').textContent = err.code === 1 ? 'Izin lokasi ditolak' : 'Lokasi Anda belum didapat';
      },
      { enableHighAccuracy: true, maximumAge: 5000, timeout: 20000 },
    );
  });

  const BASE_PATH = window.location.pathname.replace(/\/live\/[^/]+\/?$/, '');

  fetch(`${BASE_PATH}/api/live/${encodeURIComponent(token)}`)
    .then(async (r) => {
      const d = await r.json();
      if (!r.ok) throw new Error(d.error || 'Tautan tidak valid');
      return d;
    })
    .then((d) => {
      const s = d.session;
      $('#title').textContent = s.kind === 'panic' ? `PANIC — ${s.user_name}` : `${s.user_name} membagikan lokasi`;
      document.title = `${$('#title').textContent} · Everest`;
      if (s.kind === 'panic') setStatus(s.panic_status || 'aktif');
      if (s.user_phone) {
        $('#call').href = `tel:${s.user_phone.replace(/[^+0-9]/g, '')}`;
        $('#call').classList.remove('hidden');
      }
      if (s.ended_at) ended(s.ended_at);
      pts = d.points;
      trail.setLatLngs(pts.map((p) => [p.lat, p.lng]));
      if (d.points.length) setLast(d.points[d.points.length - 1]);
      else $('#updated').textContent = 'Menunggu titik lokasi pertama…';
      if (d.points.length > 1 && !me) map.fitBounds(trail.getBounds(), { padding: [40, 40], maxZoom: 17 });

      const socket = io({ path: (BASE_PATH || '') + '/socket.io', auth: { live_token: token }, transports: ['websocket', 'polling'] });
      socket.on('points', (m) => {
        // Titik tertunda dari antrean offline bisa datang belakangan: urutkan ulang berdasarkan waktu rekam
        const t = (p) => Date.parse(p.recorded_at);
        pts = pts.concat(m.points).sort((a, b) => t(a) - t(b));
        trail.setLatLngs(pts.map((p) => [p.lat, p.lng]));
        const newest = pts[pts.length - 1];
        if (!last || t(newest) >= t(last)) setLast(newest);
      });
      socket.on('status', (m) => {
        setStatus(m.status);
        if (CLOSED.has(m.status)) $('#updated').textContent += ' · kejadian ditutup';
      });
      socket.on('ended', (m) => ended(m.ended_at));
    })
    .catch((e) => {
      $('#title').textContent = e.message;
      for (const id of ['#me-btn', '#follow-btn']) $(id).classList.add('hidden');
      $('#dist').textContent = '';
    });
})();
