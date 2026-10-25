# Baixa o YOLO11s e converte para ONNX no tamanho dos quadros da IA (640 de largura, 736 de altura: o
# quadro da câmera, 640×720, mais umas linhas), em server\models\yolo11s\. Usa um Python temporário com
# o Ultralytics (~300 MB, apagado no fim). Depois disso o camera-ai usa o YOLO sozinho, na placa de
# vídeo se tiver (DirectML), senão no processador.
#
#   powershell -ExecutionPolicy Bypass -File server\get-yolo.ps1
#
# Licença: os pesos do YOLO11 (Ultralytics) são AGPL-3.0. Uso em casa, tudo bem; para distribuir o
# camera-ai com eles, ver https://www.ultralytics.com/license.
$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'models\yolo11s'
$work = Join-Path $env:TEMP 'camera-ai-yolo'
New-Item -ItemType Directory -Force $out, $work | Out-Null
$py = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $py) { throw 'Precisa do Python 3 (python.org).' }
& $py -m venv (Join-Path $work 'venv')
$vpy = Join-Path $work 'venv\Scripts\python.exe'
& $vpy -m pip install -q --disable-pip-version-check torch --index-url https://download.pytorch.org/whl/cpu
& $vpy -m pip install -q --disable-pip-version-check ultralytics onnx onnxslim
Push-Location $work
try {
    & $vpy -c "from ultralytics import YOLO; YOLO('yolo11s.pt').export(format='onnx', imgsz=(736, 640), opset=17, simplify=True, dynamic=False)"
    Copy-Item (Join-Path $work 'yolo11s.onnx') $out -Force
} finally {
    Pop-Location
}
Remove-Item -Recurse -Force $work
Write-Output "YOLO11s pronto em $out. Reinicie o camera-ai."
