# camera-ai

Controle e visualização de uma **câmera IP de duas lentes** (XiongMai / app iCSee),
com interface mobile, detecção de pessoas/animais por IA e **sem depender do app nem da
nuvem do fabricante**. Tudo roda local, na sua rede.

![status](https://img.shields.io/badge/status-em%20desenvolvimento-blue)

## O que é

A câmera (modelo `XM232_X6S-WEQ-H_8M`, 2 lentes de 4MP = "8M") só tem o app iCSee e um
"Web Viewer" antigo de plugin que não funciona em navegador moderno. Este projeto fala
**ONVIF + RTSP** direto com ela e entrega:

- Vídeo ao vivo no navegador (PC e celular), escolhendo **Câmera 1 / Câmera 2 / Ambas**
  (as duas lentes vêm empilhadas num frame só; a interface recorta cada uma).
- Foto, gravação local, ouvir o microfone, ajustes de imagem (brilho/contraste/saturação/
  nitidez, WDR, dia/noite) — tudo via ONVIF.
- **Detecção por IA no próprio navegador** (TensorFlow.js + COCO-SSD): quadrados
  **verde = pessoa**, **laranja = animal**, **vermelho = incerto**. Nenhuma imagem sai do
  aparelho.
- Otimização automática de FPS/bitrate (a câmera vinha travada em 12fps).

Interface no estilo do [nevext.com.br](https://nevext.com.br) (fonte Geist, azul #2563eb,
claro/escuro automático).

## Como funciona (arquitetura)

```
  Câmera XM ──ONVIF(8899)/RTSP(554)──►  server/proxy.mjs (Node, sem dependências)
                                              │  traduz p/ HTTP simples + MJPEG (via ffmpeg)
                                              ▼
                         Interface web (index.html + assets/)  ──►  navegador (PC/celular)
                                              │
                                   IA de detecção roda aqui, no navegador
```

- **Navegador não toca RTSP.** O proxy usa o **ffmpeg** para converter RTSP → MJPEG e serve
  tudo (UI + API) na **porta 8787**.
- A interface e a API ficam na mesma origem, então funciona igual no PC e no celular.

### A câmera (dados confirmados)
- IP na rede: `192.168.0.41` (login ONVIF `admin`, senha vazia — padrão de fábrica).
- 1 fonte de vídeo 3072×2048 com as **2 lentes empilhadas** (cada uma ~3072×1024).
- Portas abertas: RTSP 554, ONVIF 8899, DVRIP 34567, HTTP 80.
- Detalhes técnicos e como tudo foi descoberto: [`docs/DESCOBERTA.md`](docs/DESCOBERTA.md).

## Como rodar

Pré-requisitos: **Node 18+** e o **ffmpeg** (baixe uma vez):

```powershell
powershell -ExecutionPolicy Bypass -File server/get-ffmpeg.ps1   # baixa ffmpeg p/ server/bin
node server/proxy.mjs                                             # sobe tudo na porta 8787
```

Abrir:
- **PC:** http://127.0.0.1:8787
- **Celular (mesma Wi-Fi):** http://192.168.0.33:8787
  - Se não abrir, liberar a porta no Firewall (uma vez, PowerShell admin):
    `New-NetFirewallRule -DisplayName "camera-ai" -Direction Inbound -Protocol TCP -LocalPort 8787 -Action Allow -Profile Private`

Login: a interface tenta `admin`/senha vazia e padrões XM sozinha. Trocou a senha no app?
Ponha no ⚙. Gravações caem em `server/gravacoes/`. A IA baixa o modelo na 1ª vez (precisa
de internet uma vez; depois roda offline).

## Estrutura

```
index.html            interface (player)
assets/app.css        estilo (tema nevext, Geist)
assets/app.js         lógica da UI (lentes, qualidade, PTZ, imagem, áudio, foto, gravar)
assets/detect.js      detecção por IA (COCO-SSD) no navegador
server/proxy.mjs      proxy ONVIF/RTSP + servidor web (porta 8787)
server/get-ffmpeg.ps1 baixa o ffmpeg estático p/ server/bin
docs/DESCOBERTA.md    como a câmera foi investigada e tudo que achamos
FEATURES.md           o que está pronto e o que falta
```

## Próximos passos

Ver [`FEATURES.md`](FEATURES.md) — inclui o empacotamento em **APK** e as funcionalidades
que faltam.
