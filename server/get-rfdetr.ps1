# Baixa o RF-DETR medium já em ONNX (onnx-community, no Hugging Face), em server\models\rfdetr_medium\
# (~120 MB). Não precisa de Python. Depois disso o camera-ai usa ele sozinho, no lugar do YOLO11s, na placa
# de vídeo se tiver (DirectML), senão no processador.
#
#   powershell -ExecutionPolicy Bypass -File server\get-rfdetr.ps1
#
# Licença: Apache 2.0 (RF-DETR, Roboflow; a versão ONNX também): uso comercial livre, ao contrário do
# YOLO11 (AGPL-3.0). Conferir em https://huggingface.co/onnx-community/rfdetr_medium-ONNX antes de lançar.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # a barra de progresso do PowerShell 5 deixa o download 10x mais lento
$out = Join-Path $PSScriptRoot 'models\rfdetr_medium'
$base = 'https://huggingface.co/onnx-community/rfdetr_medium-ONNX/resolve/main'
New-Item -ItemType Directory -Force $out | Out-Null
Invoke-WebRequest "$base/preprocessor_config.json" -OutFile (Join-Path $out 'preprocessor_config.json')
Invoke-WebRequest "$base/onnx/model.onnx" -OutFile (Join-Path $out 'model.onnx')
Write-Output "RF-DETR medium pronto em $out. Reinicie o camera-ai."
