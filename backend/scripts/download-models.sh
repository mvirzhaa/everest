#!/usr/bin/env bash
# Unduh model whisper.cpp terkuantisasi ke MODELS_DIR. HP mengunduhnya dari server ini, bukan dari internet.
set -euo pipefail
DIR="${MODELS_DIR:-$(dirname "$0")/../data/models}"
mkdir -p "$DIR"
BASE=https://huggingface.co/ggerganov/whisper.cpp/resolve/main
for m in ggml-base-q5_1.bin ggml-small-q5_1.bin; do
  if [ -s "$DIR/$m" ]; then echo "sudah ada: $m"; continue; fi
  echo "mengunduh $m ..."
  curl -fL --retry 3 -o "$DIR/$m.part" "$BASE/$m" && mv "$DIR/$m.part" "$DIR/$m"
done
ls -lh "$DIR"
