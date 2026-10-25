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
- **Detecção por IA no próprio camera-ai** (YOLO11s na placa de vídeo, ou COCO-SSD de reserva): quadrados
  **verde = pessoa**, **laranja = animal**, **vermelho = incerto**. Roda uma vez, no PC, e vai
  para **todas as telas** (navegador, celular, TVs com o nevtv). Nenhuma imagem sai do PC.
- **A câmera em qualquer TV da casa** pelo [nevtv](https://github.com/nevext/nevtv): a TV acha o
  camera-ai sozinha e mostra o que ele oferece (vídeo da lente nítido, IA, controles). Função
  nova aqui aparece nas TVs sem atualizar o nevtv. Com o PC desligado, a TV fala direto com a
  câmera e fica com o que consegue sozinha (imagem, dia/noite, aviso de movimento; sem IA).
- Otimização automática de FPS/bitrate (a câmera vinha travada em 12fps).

Interface no estilo do [nevext.com.br](https://nevext.com.br) (fonte Geist, azul #2563eb,
claro/escuro automático).

## Como funciona (arquitetura)

```
  Câmera XM ──ONVIF(8899)/RTSP(554)──►  server/proxy.mjs  (o "cérebro" da câmera, no PC)
                                         │  IA (server/ai.mjs), MJPEG via ffmpeg, controles ONVIF
                                         │
           ┌─────────────────────────────┼──────────────────────────────┐
           ▼                             ▼                              ▼
   navegador (PC/celular)       TVs com o nevtv                  app do celular
   index.html + assets/         /api/screen (porta 8787)         /api/screen
                                acha o PC por UDP 8788
```

**API das telas** (`/api/screen…`): é o contrato com o nevtv e qualquer outra tela.
- `GET /api/screen`: a câmera (nome, modelo, série, IPs), as lentes, o vídeo, o estado da IA e os
  **controles** (`choice`, `level`, `toggle`, com rótulo `pt`/`en`). A tela monta o painel com o que
  vier; controle novo aqui = controle novo em todas as TVs.
- `POST /api/screen/control` `{id, value}`: muda um controle; devolve todos de novo.
- `GET /api/screen/events`: NDJSON, uma linha por análise (`detections`: caixas em fração do
  quadro inteiro, 0–1), `motion` (`active`: o detector de movimento da própria câmera, quando muda)
  e um `ping` a cada 5 s. A IA só roda enquanto alguma tela escuta.
- `GET /api/screen/video?view=whole|top|bottom&width=&height=&format=h264|mjpeg`: o vídeo já recortado
  na lente e no tamanho da tela (o PC decodifica o vídeo grande, que as TVs não aguentam).
  `h264`: H.264 de baixo atraso na placa de vídeo (NVENC/QSV/AMF, ou x264 no processador), ~3 Mbps,
  **~1 s de atraso**; se a tela ficar para trás, a conexão fecha e ela reconecta no ao vivo. `mjpeg`:
  JPEG a JPEG, sempre o mais novo (os do meio são pulados), para navegador e TVs sem H.264.
- `GET /api/vistoria`: o teste da câmera. Nível (1 Básico, 2 Bom, 3 Completo), as medidas da entrega de
  verdade (quadros/s que chegam × filmados, Mbps, rajadas, perdas, travadas, quedas na última hora), a
  lista do que a câmera tem e dicas. Medido em segundo plano enquanto o vídeo está aberto; o nível
  também vem em `GET /api/screen` (`quality`). `POST /api/vistoria` `{seconds}`: teste completo, vídeo
  grande e pequeno (o pequeno só com a câmera livre: ela não aguenta duas conexões).
- Descoberta: a TV manda `camera-ai?` em broadcast UDP na **porta 8788** e recebe
  `{hub, port, serial, hosts}`.
- Ao ligar, o proxy conecta sozinho: `CAMERA=ip`, ou a última câmera (`server/state.json`), ou a
  primeira que responder na rede (WS-Discovery).

- **Navegador não toca RTSP.** O proxy usa o **ffmpeg** para converter RTSP → MJPEG e serve
  tudo (UI + API) na **porta 8787**.
- A interface e a API ficam na mesma origem, então funciona igual no PC e no celular.

### A câmera (dados confirmados)
- IP na rede: `192.168.0.41` (login ONVIF `admin`, senha vazia — padrão de fábrica).
- 1 fonte de vídeo com as **2 lentes empilhadas**: o ONVIF diz 3072×2048, mas o RTSP entrega
  **2304×2592** (cada lente 2304×1296, 16:9).
- Portas abertas: RTSP 554, ONVIF 8899, DVRIP 34567, HTTP 80.
- Detalhes técnicos e como tudo foi descoberto: [`docs/DESCOBERTA.md`](docs/DESCOBERTA.md).

## Como rodar

Pré-requisitos: **Node 18+** e o **ffmpeg** (baixe uma vez):

```powershell
powershell -ExecutionPolicy Bypass -File server/get-ffmpeg.ps1   # baixa ffmpeg p/ server/bin
npm install                                                       # a IA (ONNX Runtime + TensorFlow.js)
powershell -ExecutionPolicy Bypass -File server/get-yolo.ps1      # o YOLO11s (opcional, bem mais preciso)
node server/proxy.mjs                                             # sobe tudo na porta 8787
```

**Ligar junto com o Windows** (sem janela; se cair, volta sozinho em 5 s):

```powershell
powershell -ExecutionPolicy Bypass -File server/autostart.ps1           # instala e já liga
powershell -ExecutionPolicy Bypass -File server/autostart.ps1 -Remove   # tira e desliga
```

Cria a tarefa agendada `camera-ai` (ao entrar no Windows), que roda `server/hidden.vbs` →
`server/run.ps1` → `node server/proxy.mjs`. Log da sessão em `server/camera-ai.now.log`.

Sem o `npm install` tudo funciona, só sem IA. O modelo baixa na 1ª vez para `server/models/`
e depois roda sem internet.

Abrir:
- **PC:** http://127.0.0.1:8787
- **Celular (mesma Wi-Fi):** http://192.168.0.33:8787
  - Se não abrir, liberar a porta no Firewall (uma vez, PowerShell admin):
    `New-NetFirewallRule -DisplayName "camera-ai" -Direction Inbound -Protocol TCP -LocalPort 8787 -Action Allow -Profile Private`
  - As TVs também precisam da porta **UDP 8788** (para achar o PC); quando o Windows perguntar
    ao subir o Node, permitir. Se a rede de casa estiver como *Pública* no Windows, a regra
    precisa valer para *Public* também.

Login: a interface tenta `admin`/senha vazia e padrões XM sozinha. Trocou a senha no app?
Ponha no ⚙. Gravações caem em `server/gravacoes/`.

## Estrutura

```
index.html            interface (player)
assets/app.css        estilo (tema nevext, Geist)
assets/app.js         lógica da UI (lentes, qualidade, PTZ, imagem, áudio, foto, gravar)
assets/detect.js      desenha a detecção: usa a IA do camera-ai (ou roda a própria, se ela faltar)
server/proxy.mjs      proxy ONVIF/RTSP + servidor web + API das telas (porta 8787, UDP 8788)
server/ai.mjs         a IA (YOLO11s na placa de vídeo, ou COCO-SSD): analisa o vídeo e manda para as telas
server/get-yolo.ps1   baixa e converte o YOLO11s para server/models/yolo11s/
server/live.mjs       a conexão única com a câmera: vistas H.264 para as telas e quadros para a IA
package.json          dependências da IA (npm install)
profiles/             o que sabemos de cada câmera (lido pelo proxy e pelos apps)
server/get-ffmpeg.ps1 baixa o ffmpeg estático p/ server/bin
docs/DESCOBERTA.md    como a câmera foi investigada e tudo que achamos
FEATURES.md           o que está pronto e o que falta
```

## Próximos passos

O plano do produto que nasce daqui, o **AI Safe Cam** (níveis por câmera, vistoria, planos, onde roda a IA): [`docs/AI-SAFE-CAM.md`](docs/AI-SAFE-CAM.md).

Ver [`FEATURES.md`](FEATURES.md) — inclui o empacotamento em **APK** e as funcionalidades
que faltam.
