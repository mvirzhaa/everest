# Unduh model whisper.cpp terkuantisasi ke MODELS_DIR di Windows.
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDir = Resolve-Path "$scriptDir\.."
$dir = if ($env:MODELS_DIR) { $env:MODELS_DIR } else { Join-Path $backendDir "data\models" }

if (!(Test-Path $dir)) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
}

$base = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
$models = @("ggml-base-q5_1.bin", "ggml-small-q5_1.bin")

foreach ($m in $models) {
    $targetPath = Join-Path $dir $m
    if ((Test-Path $targetPath) -and ((Get-Item $targetPath).Length -gt 0)) {
        Write-Host "sudah ada: $m"
        continue
    }
    Write-Host "Mengunduh $m ..."
    $url = "$base/$m"
    $partPath = "$targetPath.part"
    Invoke-WebRequest -Uri $url -OutFile $partPath
    Move-Item -Path $partPath -Destination $targetPath -Force
    Write-Host "Berhasil mengunduh $m"
}

Get-ChildItem -Path $dir

