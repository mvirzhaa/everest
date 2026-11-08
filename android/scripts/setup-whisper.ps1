# Mengambil sumber whisper.cpp (versi terkunci) untuk dibangun bersama aplikasi Android.
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$androidDir = Resolve-Path "$scriptDir\.."
$dest = Join-Path $androidDir "app\src\main\cpp\whisper.cpp"
$tag = "v1.9.5"

if ((Test-Path "$dest\.git") -or (Test-Path "$dest\CMakeLists.txt")) {
    Write-Host "whisper.cpp sudah ada di $dest"
    exit 0
}

Write-Host "Mengunduh whisper.cpp ($tag) ke $dest..."
git clone --depth 1 --branch $tag https://github.com/ggml-org/whisper.cpp.git $dest
Write-Host "Selesai: whisper.cpp $tag berhasil diunduh ke $dest"
