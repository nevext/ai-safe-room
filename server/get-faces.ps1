# Baixa os modelos de rosto do OpenCV Zoo (licença Apache 2.0) para server\models\faces:
#   yunet.onnx  acha o rosto e 5 pontos (olhos, nariz, boca)       ~230 KB
#   sface.onnx  transforma o rosto num código de 128 números       ~37 MB
$dir = Join-Path $PSScriptRoot 'models\faces'
New-Item -ItemType Directory -Force $dir | Out-Null
$base = 'https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models'
Invoke-WebRequest "$base/face_detection_yunet/face_detection_yunet_2023mar.onnx" -OutFile (Join-Path $dir 'yunet.onnx')
Invoke-WebRequest "$base/face_recognition_sface/face_recognition_sface_2021dec.onnx" -OutFile (Join-Path $dir 'sface.onnx')
Write-Host "Modelos de rosto em $dir"
