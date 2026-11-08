# Everest

Aplikasi Android untuk penjelajah di lingkungan Universitas Ibn Khaldun Bogor, dengan widget enam tombol di layar utama:

| Tombol | Fungsi |
| --- | --- |
| **Rekam** | Merekam suara, ditranskrip **offline di HP** (whisper.cpp) tiap potongan 30 detik, disimpan dan dicari per tanggal/kata kunci |
| **Track** | Mengirim lokasi tiap 10 detik ke kontak pilihan sampai dimatikan; tanpa sinyal, titik disimpan lalu dikirim begitu online |
| **Farhan** | Asisten suara: cari rekaman, riwayat lokasi, buat/lihat jadwal Google Calendar, ringkasan & tanya-jawab (cloud) |
| **Report** | Kamera → foto → deskripsi (bisa didikte) → simpan; terkirim ke server, dilihat pelapor dan admin |
| **Sirine** | Hitung mundur 5 detik (bisa batal) → sirine keras lewat jalur alarm (tetap bunyi saat HP senyap) + lampu senter berkedip; tekan tombol yang sama sekali lagi untuk mematikan |
| **Panic** | Hitung mundur 5 detik (bisa batal) → lokasi dikirim ke kontak darurat **dan** satpam kampus (wajib), lalu tracking 10 detik otomatis; tanpa internet → SMS cadangan. Bila tidak ada yang menekan *Terima* dalam 2 menit → diteruskan ke **semua** satpam, 5 menit → ke admin, dan pengirim diberi tahu |

Aplikasi juga memuat menu **Doa & Ayat** (offline): 15 doa perjalanan dan bepergian, Ayat Kursi, dan ayat-ayat ruqyah (20 rangkaian ayat + 4 doa ruqyah dari hadits), lengkap dengan teks Arab, latin, arti, dan sumber. Farhan bisa membukanya lewat suara ("bacakan doa naik motor") dan menyalakan/mematikan sirine.

**Tampilan:** nuansa putih dan oranye cerah (`#F26B0F`) sebagai bawaan, dengan pilihan **Gelap** dan **Ikuti sistem** di Profil (aplikasi) dan tombol *Tema* (web). Mode gelap memakai latar cokelat-hitam hangat dan oranye yang lebih terang (`#FF9150`); logo diberi pelat putih karena garisnya biru tua. Widget tetap putih. Tulisan berwarna memakai oranye tua `#B45309` agar terbaca di atas putih; Sirine berwarna kuning amber dan Panic merah supaya tetap menonjol. Dashboard web dan peta live memakai palet yang sama. Logo Everest dipakai sebagai splash screen (layar putih, logo di tengah, minimal 0,9 detik lalu memudar), ikon aplikasi (logo lengkap di latar putih, plus versi siluet untuk ikon bertema Android 13+), layar login, dan dashboard web. Sumber logo: `tools/logo/everest-logo.png`.

Semua data **offline-first**: ditulis ke database HP (terenkripsi SQLCipher) dulu, masuk antrean, dan terkirim otomatis begitu ada sinyal — termasuk setelah HP dinyalakan ulang.

```
everest/
├── backend/            Node.js + PostgreSQL + Socket.IO (server kampus, cukup CPU)
│   ├── src/            API, auth SSO/OIDC, FCM, LLM Farhan, Google Calendar
│   ├── public/         Dashboard satpam/admin (/dashboard) dan peta live (/live/<token>)
│   ├── migrations/     Skema database
│   └── test/           Tes integrasi (19 tes, PostgreSQL sungguhan)
├── android/            Proyek Android Studio (Kotlin, Compose, Glance, CameraX, whisper.cpp)
├── tools/doa/          Pembangun data menu Doa (assets/doa.json) + pemeriksa potongan ayat
└── docker-compose.yml  Backend + PostgreSQL untuk server kampus
```

Rancangan sistem lengkap: dokumen *Rancangan Sistem Aplikasi Everest*.

---

## 1. Backend

### Menjalankan untuk uji coba (lokal)

```bash
cd backend
npm install
createdb everest                       # PostgreSQL 14+
DEV_LOGIN=true npm start               # migrasi otomatis, buka http://localhost:8080/dashboard/
```

Tes otomatis (butuh database `everest_test`):

```bash
createdb everest_test
npm test
```

### Produksi di server kampus (`sc.uika-bogor.ac.id/everest`)

1. **Persiapan di server (pertama kali):**
```bash
git clone https://github.com/<organisasi-atau-user>/everest.git
cd everest
cp backend/.env.example backend/.env   # isi semua nilai (lihat tabel di bawah)
mkdir -p secrets && cp /path/fcm-service-account.json secrets/fcm.json
```

2. **Jalankan dengan Docker Compose:**
```bash
DB_PASSWORD=... docker compose up -d --build
docker compose exec backend sh -c 'MODELS_DIR=/data/models sh scripts/download-models.sh'
```

3. **Pembaruan rutin via GitHub:**
```bash
chmod +x tools/deploy/deploy.sh
./tools/deploy/deploy.sh
```

`download-models.sh` mengunduh `ggml-base-q5_1.bin` dan `ggml-small-q5_1.bin` (whisper.cpp) ke server. HP mengunduh model dari server kampus, bukan dari internet.

### Konfigurasi Reverse Proxy Nginx (`sc.uika-bogor.ac.id/everest`)

Reverse proxy nginx harus meneruskan WebSocket (untuk dashboard dan peta live) dan subpath `/everest/` (file konfigurasi siap pakai: `tools/nginx/everest.conf`):

```nginx
# Letakkan di dalam server block: server { server_name sc.uika-bogor.ac.id; ... }

location = /everest {
    return 301 /everest/;
}

location /everest/ {
    proxy_pass http://127.0.0.1:8088/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Prefix /everest;
    client_max_body_size 20m;
    proxy_read_timeout 86400s;
    proxy_send_timeout 86400s;
}
```

### Konfigurasi penting (`backend/.env`)

| Variabel | Keterangan |
| --- | --- |
| `PUBLIC_URL` | URL publik backend: `https://sc.uika-bogor.ac.id/everest` |
| `OIDC_ISSUER`, `OIDC_AUDIENCES`, `OIDC_WEB_CLIENT_ID/SECRET` | SSO E-Portal UIKA (OpenID Connect). HP mengirim `id_token`, server memverifikasi tanda tangan via JWKS |
| `ADMIN_EMAILS` | Email yang otomatis menjadi admin. Admin lalu menetapkan satpam di dashboard |
| `FCM_SERVICE_ACCOUNT` | File service account Firebase untuk push. Tanpa ini, push hanya dicatat di log |
| `GOOGLE_CLIENT_ID/SECRET`, `ENC_KEY` | Google Calendar. Refresh token disimpan terenkripsi AES-256-GCM |
| `LLM_PROVIDER` (`anthropic`/`gemini`), `LLM_API_KEY`, `LLM_MODEL` | Farhan cloud. Kunci API hanya di server. Kuota harian per pengguna: `FARHAN_DAILY_QUOTA` |
| `DEV_LOGIN` | Login tanpa SSO **khusus uji coba**. Wajib `false` di produksi |
| `ESCALATE_GUARDS_SEC`, `ESCALATE_ADMINS_SEC` | Eskalasi panic yang belum diterima: bawaan 120 dtk ke semua satpam, 300 dtk ke admin |
| `SECURITY_PHONE` | Nomor pos satpam yang disarankan ke pengirim panic saat dieskalasi (selain 112) |
| `PRIVACY_VERSION` | Versi kebijakan privasi di `/privasi`. Ubah bersama `Privacy.VERSION` di aplikasi bila isinya berubah, agar pengguna diminta setuju ulang |

### Dashboard web

- `/everest/dashboard/` — satpam: panic aktif di peta (alarm suara, jejak lokasi live, label **Eskalasi** bila belum diterima), tombol *Terima & tangani* / *Tandai ditangani*, saklar bertugas. Admin: plus laporan dalam tampilan **Daftar** atau **Peta sebaran** (penanda berwarna per status, klik untuk foto dan tindak lanjut), riwayat panic, pengguna & peran, log audit.
- `/everest/live/<token>` — peta live untuk penerima notifikasi: jejak real-time, status panic, tombol **Lokasi saya** (jarak dan arah ke korban), *Ikuti*, *Telepon*, *Navigasi*. Token acak per sesi, kedaluwarsa 1 jam setelah sesi berakhir. Aplikasi Android membuka halaman yang sama di dalam aplikasi.
- `/everest/privasi` — kebijakan privasi (draf: isi bagian bertanda `[ … ]` dan minta tinjauan bagian hukum/Pejabat PDP kampus).

---

## 2. Aplikasi Android

### Persiapan

1. Android Studio Ladybug (2024.2) atau lebih baru, NDK dan CMake 3.22.1 (SDK Manager → SDK Tools).
2. Ambil sumber whisper.cpp (versi terkunci v1.9.5):
   ```bash
   cd android && ./scripts/setup-whisper.sh
   ```
3. Salin `everest.properties.example` → `everest.properties`, isi `SERVER_URL`, OIDC, Google, dan FCM.
   Untuk server kampus: `SERVER_URL=https://sc.uika-bogor.ac.id/everest`
   Untuk uji coba lokal tanpa SSO: `DEV_LOGIN=true` dan `SERVER_URL=http://10.0.2.2:8080`.
4. Buka folder `android/` di Android Studio → Run.

Uji di emulator: `SERVER_URL=http://10.0.2.2:8080` (build debug mengizinkan HTTP).

### Pendaftaran yang perlu dilakukan kampus

| Layanan | Yang didaftarkan |
| --- | --- |
| E-Portal (OIDC) | Client publik `everest-android` dengan redirect `id.ac.uika.everest:/oauth2redirect` (PKCE), dan client web untuk dashboard dengan redirect `https://sc.uika-bogor.ac.id/everest/auth/web/callback` |
| Firebase | Aplikasi Android `id.ac.uika.everest` → isi `FCM_*` di `everest.properties`; service account untuk backend |
| Google Cloud | OAuth client *Web application* (dipakai backend dan `GOOGLE_SERVER_CLIENT_ID`) + OAuth client *Android* (package + SHA-1); aktifkan Calendar API |

### Cara kerja penting

- **Widget → activity transparan → foreground service.** Android 14 mewajibkan aplikasi terlihat saat memulai servis mikrofon/lokasi, jadi tombol widget membuka activity sesaat.
- **Transkripsi:** model *base* (RAM ≤ 4 GB) atau *small* (RAM ≥ 6 GB) dipilih otomatis, bahasa dikunci `id`. Potongan hening dilewati; audio dikompres ke AAC (~14 MB/jam). Rekaman > 30 menit diproses saat dicas (bisa diubah di Profil).
- **Antrean offline (`outbox`):** prioritas panic → sesi track → titik lokasi → laporan → jadwal → catatan. Setiap item ber-ID UUID dari HP sehingga kirim ulang tidak menggandakan data (diuji di backend).
- **Farhan:** perintah dikenali di HP oleh parser bahasa Indonesia berbasis aturan (tanggal, "minggu lalu", "besok jam 9", "kemarin sore", lokasi "di ruang 204"). Pertanyaan yang butuh penalaran dikirim ke LLM lewat server dengan hanya potongan transkrip relevan. Membuat jadwal selalu meminta konfirmasi "ya/batal".
- **Sirine:** nada sintetis 650–1550 Hz yang dibangkitkan di HP (tanpa file audio), diputar lewat `USAGE_ALARM` dengan volume alarm dinaikkan ke maksimum lalu dikembalikan setelah dimatikan. Foreground service tipe `mediaPlayback`. Bisa dimatikan dari widget, notifikasi, aplikasi, atau Farhan.
- **Widget:** 2 baris × 3 tombol (Rekam, Track, Farhan / Report, Sirine, Panic); bila widget dibuat pendek, otomatis 1 baris.
- **Panic penerima:** notifikasi berbunyi alarm dengan tombol *Buka Maps*, *Peta live*, dan *Terima* (satpam). Satpam diminta mengizinkan alarm layar penuh saat onboarding. *Peta live* terbuka **di dalam aplikasi** (layar tetap menyala, izin lokasi untuk jarak ke korban).
- **Eskalasi:** server memeriksa tiap 15 detik. Panic berstatus *aktif* yang melewati batas diteruskan (tahap 1 semua satpam, tahap 2 admin) dengan alarm bertanda *ESKALASI*; pengirim menerima notifikasi berisi tombol *Telepon 112*. Atomik di database, aman bila server lebih dari satu instans.
- **Layar Petugas real-time:** WebSocket (protokol Socket.IO v4, klien ringan di atas OkHttp tanpa pustaka tambahan) hanya selama layar terlihat. Panic baru, perubahan status, eskalasi, dan posisi terbaru langsung muncul; polling hanya cadangan (15 detik saat terputus, 60 detik saat tersambung).
- **Persetujuan privasi (UU PDP):** sebelum login pengguna membaca ringkasan data per fitur dan menyetujui; Farhan cloud opsional. Persetujuan dicatat di server (`consent_log`) lewat antrean, juga saat Farhan cloud dinyalakan/dimatikan.
- **Edit transkrip:** tekan lama baris transkrip untuk memperbaiki salah dengar atau menghapus baris. Kata kunci dan ringkasan otomatis dihitung ulang; catatan disinkron ulang (versi editan menang).

---

## 3. Menu Doa & Ayat

Data dibangun ulang dengan:

```bash
git clone --depth 1 https://github.com/sutanlab/quran-api.git /tmp/quran-api
python3 tools/doa/build_doa.py /tmp/quran-api/data/quran.json android/app/src/main/assets/doa.json
```

- **Teks Arab Al-Qur'an** diambil apa adanya dari Tanzil (Unicode standar); **terjemah** dari Kementerian Agama RI. Tidak ada ayat yang diketik manual.
- **Doa dari hadits** ditulis di `tools/doa/content.py` beserta nomor haditsnya. Potongan ayat di dalam doa (mis. Az-Zukhruf 13–14 dalam doa safar, Hud 41) dicocokkan huruf demi huruf dengan mushaf; build gagal bila ada salah ketik.
- **Latin** ditulis untuk bacaan pendek yang sering dibaca (Al-Fatihah, Ayat Kursi, Al-Baqarah 285–286, 3 Qul, semua doa). Ayat ruqyah yang panjang sengaja tanpa latin agar tidak salah baca.
- **Mohon ditinjau ustadz/LDK kampus** sebelum rilis, terutama transliterasi latin dan terjemah doa hadits.

## 4. Status pengujian

| Bagian | Status |
| --- | --- |
| Backend API, auth OIDC, idempotensi antrean, hak akses, foto tanpa EXIF, socket live, Farhan tools & kuota, eskalasi panic, persetujuan privasi, protokol WebSocket mentah | **19 tes integrasi lolos** terhadap PostgreSQL 16 |
| Dashboard satpam/admin (termasuk peta laporan, mode gelap) dan peta live | Dijalankan dan diperiksa di browser (Chromium) dengan data contoh |
| Parser perintah Farhan, kata kunci, pencocok doa, nada sirine, bingkai Socket.IO, aturan edit transkrip, cakupan persetujuan (Kotlin murni) | **48 tes unit lolos** (`app/src/test`) |
| Klien real-time | Pengurai bingkai Kotlin diuji ujung-ke-ujung terhadap server sungguhan: tersambung, event panic, titik lokasi, dan eskalasi tahap 1 diterima |
| Data doa | Potongan ayat dicocokkan otomatis dengan mushaf; pemeriksa terbukti menangkap salah ketik satu huruf |
| Jembatan JNI whisper.cpp | Lolos cek sintaks terhadap header whisper.cpp v1.9.5 |
| Aplikasi Android (UI, servis, widget, kamera, FCM) | **Belum dikompilasi**: repositori Google (Android SDK/Maven) tidak bisa diakses dari lingkungan pembuatan. Kode lolos pemeriksaan sintaks Kotlin; kompilasi pertama dan uji di HP perlu dilakukan di Android Studio |

## 5. Batasan versi ini

- Pemisahan pembicara (diarization) dan unggah audio ke server belum dibuat (endpoint backend sudah ada).
- Kontak darurat yang hanya berupa nomor HP (tanpa akun Everest) baru menerima SMS bila panic **tidak** terkirim lewat internet.
- Fitur hapus akun dan penghapusan otomatis transkrip lama belum dibuat (disebut di kebijakan privasi sebagai hak pengguna lewat permintaan).
- Pengenal intent Farhan di HP berbasis aturan; integrasi LLM on-device (Gemma) belum dibuat.
- Pengenal suara Farhan memakai `SpeechRecognizer` HP: offline hanya bila paket bahasa Indonesia offline terpasang.
- Teks Arab doa dibaca dari teks, tidak diputar audionya; TTS bahasa Indonesia tidak membacakan teks Arab.
- Sirine memakai volume alarm maksimum; di sebagian HP mode "Jangan Ganggu" yang memblokir alarm tetap bisa meredam bunyinya.
- Retensi data (1 tahun transkrip, 30 hari titik Track) masih usulan dan perlu persetujuan kampus.
