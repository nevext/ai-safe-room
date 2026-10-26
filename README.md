# AI Safe Room

Câmeras de casa com inteligência, sem depender do app nem da nuvem do fabricante. O app abre como a **câmera normal**
(direto nela, como o app original dela) e a **IA é escolha da pessoa**: liga quando quiser, cada recurso separado.

![status](https://img.shields.io/badge/status-teste%200.x-blue) ![android](https://img.shields.io/badge/Android-8%2B-green)

## O que faz

**A câmera, sem IA** (celular e câmera, sem o computador):
- ao vivo direto da câmera, lentes de câmeras de duas lentes (Ambas, 1/2, 2/2), tela cheia com informações e funções;
- visão noturna, luz branca, girar a lente com setas e posição salva, seguir pessoa e detecção de pessoa da própria
  câmera, ouvir o quarto e falar pela câmera;
- foto, relógio, reiniciar, trocar a senha da câmera; o vídeo escolhido pelo que o celular aguenta.

**Com a IA** (no computador de casa, o camera-ai):
- atividades de pessoas e animais em visitas (quantas juntas), rigor da detecção, zonas para ignorar, "Foi engano";
- pessoas conhecidas pelo rosto (cadastro guiado, com consentimento), "Quem é?" para aprender, "Provavelmente" pela roupa;
- Proteção com avisos no celular (ou só de desconhecidos), por horário; Privacidade (a câmera cobre a imagem);
- seguir pessoa pela IA, resumo dos dias.

**Sempre:** conexão da câmera e da IA (Péssimo a Ótimo) com reconectar, diagnóstico (Wi-Fi da câmera, câmera e IA,
vistoria completa), família e aparelhos com acesso, ver de fora de casa, tema claro/escuro.

O que está pronto e o que falta: [`FEATURES.md`](FEATURES.md). O caminho até a loja: [`docs/PUBLICAR.md`](docs/PUBLICAR.md).

## As partes

```
                 ┌──────────── RTSP (vídeo) ─────────────┐
   Câmera ───────┤                                       ├──► App Android (AI Safe Room)
 (XM / iCSee)    └── DVRIP (controle), ONVIF ──┐         │      sem IA: fala direto com a câmera
        │                                       │         │      com IA: pelo camera-ai
        └──── 1 conexão de vídeo ──► camera-ai (computador de casa, porta 8787)
                                      IA, rostos, atividades, Proteção, avisos
                                         ├──► App Android
                                         └──► TVs com o nevtv
```

| Parte | Pasta | O que é |
|---|---|---|
| App Android | [`android/`](android/) | O AI Safe Room no celular |
| camera-ai | [`server/`](server/) | O "cérebro" no computador de casa: IA, rostos, atividades, avisos e a API das telas |
| Página web | `index.html`, [`assets/`](assets/) | A mesma câmera pelo navegador (PC e celular) |
| Perfis de câmera | [`profiles/`](profiles/) | O que sabemos de cada modelo de câmera |
| TV | [nevtv](https://github.com/nevext/nevtv) | A câmera nas TVs da casa, achando o camera-ai sozinha |

## Tecnologia

| Onde | Usa |
|---|---|
| **App Android** | Kotlin, Jetpack Compose (Material 3), AndroidX Media3 / ExoPlayer (RTSP direto da câmera), cliente DVRIP próprio em Kotlin (controle e conversa pela câmera, G.711), Android Keystore (AES-GCM para a senha da câmera e a chave), serviço em primeiro plano para os avisos; AGP 9, Gradle 9, JDK 21, Android 8+ |
| **camera-ai** | Node.js (ES modules, sem framework), ffmpeg (uma conexão com a câmera para tudo: vistas H.264 com NVENC/QSV/AMF ou x264, quadros para a IA, áudio), ONNX Runtime |
| **IA** | RF-DETR medium (pessoa e animal, Apache 2.0, na placa de vídeo), YuNet + SFace do OpenCV Zoo (rosto, Apache 2.0), cor da roupa por histograma HSV; YOLO11s e COCO-SSD (TensorFlow.js) de reserva |
| **Câmera** | ONVIF (descoberta, perfis, eventos de movimento), RTSP, DVRIP/Sofia (porta 34567: visão noturna, luz, PTZ, avisos 1504, conversa 1430/1432/1434, configurações), foto por HTTP |
| **Página web** | HTML, CSS e JavaScript puros (fonte Geist) |
| **TV (nevtv)** | Java, Android MediaPlayer, ONVIF |
| **Planejado** | Servidor na nuvem com Postgres, nuvens dos fabricantes (XM/JFTech, Tuya), Firebase Cloud Messaging, WebRTC |

## Como rodar

**camera-ai** (no computador de casa; Windows, Node 18+):

```powershell
powershell -ExecutionPolicy Bypass -File server/get-ffmpeg.ps1   # o ffmpeg, em server/bin
npm install                                                       # a IA (ONNX Runtime)
powershell -ExecutionPolicy Bypass -File server/get-rfdetr.ps1    # o detector de pessoa e animal
powershell -ExecutionPolicy Bypass -File server/get-faces.ps1     # os modelos de rosto (opcional)
node server/proxy.mjs                                             # porta 8787
powershell -ExecutionPolicy Bypass -File server/autostart.ps1     # ligar junto com o Windows (sem janela)
```

A senha do camera-ai é criada na primeira vez e aparece em `server\camera-ai.log`. No próprio PC não pede; celular e
TV pedem uma vez, ou alguém que já entrou toca em Permitir. Firewall: TCP 8787 e UDP 8788 (para as TVs acharem o PC).

**App Android:** abra `android/` no Android Studio, ou `android\gradlew assembleRelease` (a chave de assinatura fica
em `android/keystore.properties`, fora do git). Versões para baixar: [Releases](../../releases).

## Documentos

- [`FEATURES.md`](FEATURES.md): o que está pronto e o que falta
- [`docs/PUBLICAR.md`](docs/PUBLICAR.md): servidor, nuvens das marcas, Firebase, Google Play
- [`docs/PRIVACIDADE.md`](docs/PRIVACIDADE.md): política de privacidade (rascunho, LGPD)
- [`docs/TERMOS.md`](docs/TERMOS.md): termos de uso (rascunho)
- [`docs/DESCOBERTA.md`](docs/DESCOBERTA.md): como a câmera foi investigada
- [`docs/AI-SAFE-CAM.md`](docs/AI-SAFE-CAM.md): o plano do produto

## API das telas (camera-ai)

`GET /api/screen` (a câmera, as lentes, o vídeo, a IA e os controles), `POST /api/screen/control`,
`GET /api/screen/events` (detecções e movimento, NDJSON), `GET /api/screen/video?view=&format=h264|mjpeg`,
`GET /api/activity`, `GET/POST /api/ai`, `GET /api/connection`, `POST /api/diagnose`, `GET /api/camera/access`
(para o app falar com a câmera sozinho). As TVs acham o camera-ai por broadcast UDP na porta 8788. Todas as rotas,
menos o login e o pedido de acesso, exigem a chave do aparelho.
