# Baixa um ffmpeg estático (Windows x64) para server/bin/ffmpeg.exe.
# O proxy usa esse binário para transcodificar RTSP->MJPEG, snapshot e gravação.
# Uso:  powershell -ExecutionPolicy Bypass -File server/get-ffmpeg.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$bin  = Join-Path $here 'bin'
$exe  = Join-Path $bin 'ffmpeg.exe'
if (Test-Path $exe) { Write-Host "ffmpeg já existe em $exe"; exit 0 }
New-Item -ItemType Directory -Force $bin | Out-Null
$zip = Join-Path $env:TEMP 'ffmpeg-camera-ai.zip'
Write-Host "Baixando ffmpeg estático (BtbN win64)..."
Invoke-WebRequest -Uri 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip' -OutFile $zip
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($zip)
$entry = $z.Entries | Where-Object { $_.FullName -match 'bin/ffmpeg.exe$' } | Select-Object -First 1
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $exe, $true)
$z.Dispose(); Remove-Item $zip -Force
Write-Host "Pronto: $exe"
