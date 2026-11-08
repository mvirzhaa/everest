#!/usr/bin/env bash
# Mengambil sumber whisper.cpp (versi terkunci) untuk dibangun bersama aplikasi.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/cpp/whisper.cpp
TAG=v1.9.5
if [ -d "$DEST/.git" ] || [ -f "$DEST/CMakeLists.txt" ]; then
  echo "whisper.cpp sudah ada di $DEST"
  exit 0
fi
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/whisper.cpp.git "$DEST"
echo "Selesai: whisper.cpp $TAG di $DEST"
