#!/usr/bin/env bash
# ==============================================================================
# Script Pembaruan / Deployment Otomatis Everest di Server Kampus
# Penggunaan:
#   chmod +x tools/deploy/deploy.sh
#   ./tools/deploy/deploy.sh
# ==============================================================================
set -euo pipefail

echo "==> [1/5] Mengambil perubahan terbaru dari Git..."
git pull origin main || git pull origin master

echo "==> [2/5] Memeriksa konfigurasi backend/.env..."
if [ ! -f backend/.env ]; then
  echo "ERROR: backend/.env belum ditemukan!"
  echo "Salin backend/.env.example ke backend/.env lalu sesuaikan nilainya:"
  echo "  cp backend/.env.example backend/.env"
  exit 1
fi

echo "==> [3/5] Memeriksa folder secrets..."
mkdir -p secrets
if [ ! -f secrets/fcm.json ]; then
  echo "[Peringatan] secrets/fcm.json belum ada. Membuat file kosong sementara agar docker mount tidak error."
  echo "{}" > secrets/fcm.json
fi

echo "==> [4/5] Membangun ulang dan menyalakan kontainer Docker..."
docker compose up -d --build

echo "==> [5/5] Memeriksa status kontainer..."
docker compose ps

echo "=============================================================================="
echo "Deployment selesai!"
echo "Everest berjalan di belakang reverse proxy: https://sc.uika-bogor.ac.id/everest"
echo "Periksa log dengan: docker compose logs -f backend"
echo "=============================================================================="

