# Baixa o cloudflared (Windows x64) para server/bin/cloudflared.exe.
# O camera-ai usa ele para o celular ver a casa de fora, sem abrir porta no roteador.
# Uso:  powershell -ExecutionPolicy Bypass -File server/get-cloudflared.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$bin  = Join-Path $here 'bin'
$exe  = Join-Path $bin 'cloudflared.exe'
if (Test-Path $exe) { Write-Host "cloudflared já existe em $exe"; exit 0 }
New-Item -ItemType Directory -Force $bin | Out-Null
Write-Host "Baixando cloudflared..."
Invoke-WebRequest -Uri 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile $exe
Write-Host "Pronto: $exe"
