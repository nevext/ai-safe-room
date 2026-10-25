# cam-control

Reescrita de controle de uma câmera IP Wi-Fi própria (app iCSee, família XiongMai/XM),
para liberar funcionalidades, tirar a dependência de nuvem/plugin e otimizar o FPS.

> Câmera do próprio usuário. Trabalho local, na rede dele. Nada de ataque a serviço de
> terceiros. Backup da flash **antes** de qualquer gravação de firmware.

## O alvo

- Produto: "Câmera Segurança IP Externa Wifi A28 App iCSee À Prova D'água".
- `iCSee` = app padrão de câmeras **XiongMai (XM)**. "A28" é nome genérico de vendedor;
  o chip (XM530/XM550/HiSilicon) só se confirma com a placa/firmware.
- Rede do usuário: `192.168.0.0/24`, gateway `192.168.0.1`, PC `192.168.0.33`.

## O que foi OBSERVADO (via REA, por CDP, sem varredura de porta)

Data: 2026-10-20. Ferramenta: REA (`list_browser_targets`, `inspect_web_page`,
`capture_web_screenshot`) contra o Chrome de depuração (porta 9222) apontado para a
página da câmera. Quem fez a rede foi o navegador; o REA só leu passivamente.

- Câmera em **`192.168.0.41`** e **`192.168.0.42`** — mesmo MAC `D8-3E-EB-AE-FA-47`
  (bloco OUI de fabricante de câmera). São o mesmo aparelho.
- Porta **80** aberta, servindo uma página de título **"Web Viewer"**.
  - `GET /` → HTML + `css/main.css` + `js/jquery-3.5.0.min.js` (jQuery 3.5.0).
  - Script inline (sha256 `81927bce…e080e6f`, 429 bytes): carrega
    `pluginVersion.js?version=<ts>` e depois `js/main.js?version=<version_web>`; dá
    `alert()` se qualquer um falhar. Padrão clássico do Web Viewer da XM.
  - Screenshot: **página em branco** no Chrome moderno — o viewer depende de plugin
    (ActiveX/NPAPI) que não existe mais. Ou seja, hoje a câmera **não entrega vídeo
    para um navegador comum**. Essa é a primeira "trava" prática (obsolescência).
- Evidence IDs (REA): target list `ev_eb629d7f…`, inspect salvo em
  `tool-results/mcp-rea-inspect_web_page-1792537914560.txt`, screenshot `ev_3a967774…`.

## Portas confirmadas (2026-10-20, via `!` powershell TcpClient)

| Porta | Serviço | Estado |
|-------|---------|--------|
| 554   | RTSP    | **ABERTA** |
| 34567 | DVRIP/XM ("sofia") | **ABERTA** |
| 8899  | ONVIF   | **ABERTA** |
| 80    | HTTP (Web Viewer) | **ABERTA** |
| 23    | telnet  | fechada |

**Conclusão:** o vídeo já sai da câmera por RTSP, ONVIF e DVRIP — **sem app e sem
nuvem**. A "trava" que incomodava (não ver no navegador) era só o Web Viewer de plugin
obsoleto. Não precisa reescrever firmware para assistir/gravar localmente; basta falar
o protocolo certo. Telnet fechado: para shell na câmera depois, será por UART/SPI ou
por brecha conhecida da XM, não por telnet aberto.

## Aparelho confirmado (via ONVIF, 2026-10-20)

- **Modelo**: `XM232_X6S-WEQ-H_8M` (XiongMai, sensor ~8 MP).
- **Firmware**: `V5.03.C01.000A19WS.10010.346543.S.ONVIF 21.06`.
- **Login**: `admin` com **senha vazia** (hash XM `tlJwpbo6` = senha em branco). Padrão de
  fábrica, nunca trocado no app. A interface tem campo de senha caso você troque.
- ONVIF em `http://192.168.0.41:8899/onvif/device_service` responde GetDeviceInformation,
  GetProfiles, GetStreamUri, GetSnapshotUri, PTZ e SetVideoEncoderConfiguration.

### Streams (antes → depois da otimização)

| Perfil | Resolução | FPS antes | FPS depois | Bitrate antes → depois |
|--------|-----------|-----------|------------|------------------------|
| mainStream (000) | 3072×2048 H264 (no ONVIF; o RTSP entrega 2304×2592) | 12 | **20** | 1536 → **4096 kbps** |
| subStream (001)  | 800×448 H264   | 12 | **25** | 512 → **1024 kbps** |
| snapStream (002) | 800×448 JPEG   | — | — | — |

- **Causa da "queda de FPS"**: os dois streams vinham travados em 12 fps, e o principal
  com bitrate baixíssimo (1536 kbps p/ 6 MP) → picotava e borrava. O `SetVideoEncoder`
  via ONVIF com `ForcePersistence` subiu o FPS e o bitrate; a mudança **persiste** na
  câmera (vale pro app iCSee também, não só pra nossa interface).
- **Otimização é automática, não tem botão**: o proxy roda `maybeOptimize()` no
  `/api/connect`. Se algum stream está abaixo do alvo (main 20fps/4096k, sub 25fps/1024k)
  ele aplica; se já está bom, é no-op. Como persiste, depois da 1ª vez a câmera já
  reconecta otimizada. Os presets (Máx. FPS / Equilíbrio / Máx. nitidez) continuam para
  ajuste manual fino.
- Teto do sensor em resolução cheia fica ~15–20 fps (limite de ISP do chip XM 8M); por
  isso o principal vai a 20 e o fluido (resolução menor) a 25.

### Vídeo ao vivo

- RTSP: `rtsp://192.168.0.42:554/user=admin_password=tlJwpbo6_channel=0_stream=0&onvif=0.sdp?real_stream`
  (stream=0 principal, stream=1 fluido). A câmera embute a credencial no próprio URI.
- Navegador não toca RTSP direto → o `proxy.mjs` transcodifica para **MJPEG** com ffmpeg
  e a interface consome num `<img>`. Medido: ~25 fps no fluido (sub).
- **Snapshot HTTP** (`webcapture.jpg`) foi recusado: a câmera responde *"snap no support
  test user admin, please use other user"* — o endpoint de foto rejeita a auth por query.
  Contornado: o botão Foto puxa 1 frame via ffmpeg do RTSP.

### ffmpeg

- Não havia ffmpeg na máquina. **Não** foi usado o MSYS2: `pacman -S ffmpeg` exigia um
  upgrade que quebraria o `gcc` da sua toolchain (dependência `cc-libs` vs `gcc-libs`).
- Em vez disso, baixei um **ffmpeg estático** (BtbN win64) e deixei só o `.exe` em
  `server/bin/ffmpeg.exe`. É isolado e reversível (basta apagar a pasta). O proxy usa
  esse binário local; se um dia houver ffmpeg no PATH, ele também serve.

## Capacidades reais da câmera (confirmadas por ONVIF, 2026-10-20)

`GetCapabilities` + testes diretos (todos responderam sem fault):
- **PTZ**: 1 nó PTZ; `ContinuousMove` aceito. Mas é bullet — provavelmente fixa (motor
  inexistente); aceitar o comando ≠ mover. Confirmar olhando a câmera; se fixa, esconder d-pad.
- **Imaging**: Brightness, Contrast, ColorSaturation, Sharpness, **IrCutFilter** (dia/noite),
  **WideDynamicRange**, BacklightCompensation. Valores de fábrica: 50/50/50, nitidez 10,
  IrCut AUTO, WDR OFF. VideoSource token `V_SRC_000`.
- **Áudio**: microfone (in) e alto-falante (out) presentes. "Ouvir" = RTSP→MP3 via ffmpeg.
  Falar (back-channel) seria via DVRIP 34567 — não feito ainda.
- **Events/Analytics**: CellMotionDetector (movimento) + TamperEngine (sabotagem) ativos no
  firmware. Base para alerta de movimento / gravação por evento.

### Design da interface
Estilo **player Apple Home + marca nevext.com.br** (2026-10-20):
- **Vídeo ocupa a tela toda**; tocar nele mostra/esconde os controles (somem após 4s).
- Botão **expandir** (tela cheia nativa) e **menu ☰** (abre Imagem / Configurações / Tela cheia).
- Controles flutuam sobre o vídeo: segmentado Nítido/Fluido + ações (Foto, Gravar, Ouvir,
  Imagem). PTZ flutua por cima. Painéis como bottom sheets iOS.
- **Identidade nevext**: fonte **Geist** (títulos/corpo) e **Geist Mono** (fps, IP, pills),
  via Google Fonts. Paleta: azul da marca `#2563eb` + indigo `#4f46e5`, fundos escuros
  azulados `#0b0e14`/`#101319`, claros `#f8f9fc`/`#fff`, vermelho `#fb2c36`, verde `#00bb7f`.
  Claro/escuro automático. (Cores/fonte extraídas do site por REA + CSS.)
Tudo em `index.html` + `assets/app.css`; lógica em `assets/app.js`.

> **Câmera de DUAS LENTES** (corrigido 2026-10-20 após print do usuário): é um aparelho só
> (1 MAC, 1 fonte ONVIF `V_SRC_000`), mas o firmware **junta as duas lentes num
> frame único, empilhadas**: lente 1 em cima, lente 2 embaixo. O ONVIF diz 3072×2048, mas o
> RTSP entrega **2304×2592** (conferido com ffmpeg, 2026-10-24): cada lente é **2304×1296, 16:9**.
> O "8M" = 2×4MP. Por isso parecia "dois vídeos cortados".
>
> **Solução na interface**: seletor **Câmera 1 / Câmera 2 / Ambas** (no lugar do antigo
> Nítido/Fluido). "Câmera 1/2" recorta uma lente e preenche a tela (palco vira 3:1, `#live`
> altura 200% deslizando — ver `.lens-top/.lens-bottom` no CSS). A lente única usa o stream
> **main (alta resolução)** → cada lente sai nítida (ganho real de qualidade). "Ambas"
> mostra o frame 3:2 inteiro no stream fluido. A detecção por IA mapeia os quadrados pra
> metade visível (`window.__lensMode` em `detect.js`).
>
> **Qualidade** (menu ☰): auto (lente única=alta, ambas=fluido) / alta / fluido.
> Upscaling AI por frame ao vivo não é viável no navegador/celular; o ganho vem de usar o
> stream de alta resolução na lente única.

### Na TV (nevtv, 2026-10-24)

O nevtv (home screen para Google TV/Android TV, repo `C:\dev\nevtv`) mostra esta câmera em
*gaveta → Câmeras*. Três travas apareceram, e o nevtv resolve as três sozinho, **em qualquer
TV que tenha o nevtv** (está no app, não na TV):

1. **Android bloqueia HTTP sem criptografia.** ONVIF é HTTP puro, e o app tem
   `usesCleartextTraffic="false"`, então o Android recusava toda conversa com a câmera
   (`Cleartext HTTP traffic to 192.168.0.42 not permitted`). Isso aparecia como "pede usuário
   e senha", mas nenhuma senha funcionaria. **Solução:** `Onvif.call` fala HTTP/1.0 direto num
   socket, e só com endereços da rede de casa (site-local/link-local); o resto do app segue
   proibindo HTTP aberto.
2. **Login.** Esta câmera não tem senha (admin/vazio). O nevtv tenta primeiro **sem login** e só
   pergunta se a câmera recusar. O login, quando existe, fica guardado com a câmera (pede uma vez).
3. **Tela preta = vídeo maior que o decodificador da TV.** O RTSP conecta (`DESCRIBE/SETUP/PLAY
   200 OK`), mas o main (2304×2592 de verdade) passa do decodificador H.264 da TV
   (`Failed to create video/avc decoder`). A Philips Google TV TA6 LT (chip MediaTek mt5867)
   decodifica H.264 só até **1920×1088**, que é o comum em TVs. **Solução:** ao adicionar, o nevtv
   lê os perfis (`GetProfiles`) e escolhe o **maior H.264 que o decodificador daquela TV aceita**
   (`MediaCodecList` → `isSizeSupported`). Cada TV escolhe o seu: numa TV 4K com decodificador
   maior, ela pega o main; nesta, pega o sub (001, 800×448).

**Resolução da câmera não dá para ajustar:** `GetVideoEncoderConfigurationOptions` oferece
**uma só** resolução por perfil (000: só 3072×2048; 001: só 800×448). O
`SetVideoEncoderConfiguration` muda FPS/bitrate, mas não a resolução. Então não existe um
"1920×1080" para pedir a esta câmera; na TV ela sai pelo sub. Câmeras que oferecem várias
resoluções poderiam ter o sub ajustado para 1080p, mas isso muda a câmera para todos (app
iCSee, este proxy), então fica como escolha manual, não automática.

Como a câmera aparece em dois IPs (.41 e .42, mesmo MAC), a busca da TV mostra as duas: é o mesmo
aparelho, basta adicionar uma.

**Controles na TV** (OK no vídeo abre o painel; ↑ ↓ escolhe, ← → muda):

| Função do camera-ai | Na TV | Como |
|---|---|---|
| Câmera 1 / Câmera 2 / Ambas | Mostrar: metade de cima / de baixo / inteira | recorte local (TextureView), lembrado por câmera |
| Ouvir | Som ligado/desligado | o áudio G711 já vem no RTSP |
| Dia/Noite | Automático / Dia / Noite | `IrCutFilter` AUTO / ON / OFF |
| Imagem | Brilho, Contraste, Cor, Nitidez, Contraluz (WDR) | `SetImagingSettings` com `ForcePersistence` (vale pro iCSee e pro proxy também) |

Os limites vêm do `GetOptions` (nesta câmera: 0–100, e nitidez 0–15). O serviço de imagem é
`/onvif/image_service`, fonte `V_SRC_000`. Ficaram de fora na TV: **PTZ** (não confirmado que tenha
motor), **Foto/Gravar** (precisam de ffmpeg ou de guardar arquivo na TV), **otimização de FPS**
(já persistida pelo proxy) e **detecção por IA**. No modo metade, o vídeo é o sub (800×448), então
cada lente sai em 800×224 ampliada: a TV não decodifica o main.

### IA: YOLO11s na placa de vídeo (2026-10-25)

O detector passou a ser o **YOLO11s** (ONNX Runtime, `server/ai.mjs`), exportado com
`server/get-yolo.ps1` em 640×736 (o quadro da IA, 640×720, mais umas linhas). Na RTX 3060 pelo
DirectML: **~12 ms por quadro** (no processador, ~150 ms); a IA passou a 10 análises por segundo. Na
mesma cena, o COCO-SSD via a pessoa na mesa com 40% ("incerto"); o YOLO, com ~60%. Sem o modelo ou
sem `onnxruntime-node`, volta ao COCO-SSD sozinho. Pesos do YOLO11: AGPL-3.0 (Ultralytics).

### O camera-ai como "cérebro" (2026-10-24)

A IA saiu do navegador e passou a rodar **no camera-ai** (`server/ai.mjs`), uma vez por câmera,
mandando o resultado para todas as telas (`/api/screen/events`). Medido no PC: o modelo carrega em
~27 s na 1ª vez (download, depois fica em `server/models/`) e analisa um quadro em **~75 ms** (WASM),
então 5 análises por segundo sobram. A análise usa o sub (800×448) e só roda enquanto alguma tela
escuta.

| | PC ligado (camera-ai) | PC desligado ou caiu |
|---|---|---|
| Vídeo na TV | a lente recortada do main, 1920×1080 nítido (`/api/screen/video`) | o sub direto da câmera, recortado na TV |
| IA (pessoa/animal) | sim, a mesma em todas as telas | não |
| Movimento | sim (detector da própria câmera) | sim (detector da própria câmera) |
| Dia/noite, brilho, cor, nitidez, WDR | pela lista de controles do camera-ai | direto na câmera (ONVIF) |
| Lentes, som | sim | sim |
| Funções novas do camera-ai | chegam sozinhas | não |

**Atraso** (medido pelo relógio que a câmera escreve na imagem, 2026-10-24): antes, MJPEG em fila,
~15 s na TV e 6 s (crescendo) numa tela a 8 fps. Agora: MJPEG pulando para o quadro mais novo, 2 s
estáveis na mesma tela; H.264 na NVENC (RTX 3060), ~1 s. O ffmpeg abre o RTSP com `-fflags nobuffer
-flags low_delay -probesize 500000 -analyzeduration 500000` (o padrão junta ~5 s antes de começar).

A TV troca de modo sozinha: se o camera-ai cai, segue direto da câmera; a cada 20 s procura de novo
e volta quando ele aparece.

**Uma conexão só com a câmera** (`server/live.mjs`, 2026-10-24): com duas conexões ao vídeo
principal, a segunda recebia **0,2 quadro por segundo** (buracos de até 25 s): a câmera não aguenta
servir vários pelo Wi-Fi dela. Agora um ffmpeg só lê o principal e entrega as três vistas em H.264
(inteira, cima, baixo, NVENC) e os quadros da IA; o navegador e o MJPEG também saem dele. Medido com
duas telas ao mesmo tempo: **23,7 fps em cada**. A câmera ainda entrega em rajadas (14 pausas de mais
de 0,5 s em 30 s, a maior 0,8 s): é o Wi-Fi dela. A TV absorve com uma reserva (Fluidez). É o mesmo
que o Frigate faz com o go2rtc: uma conexão por câmera e as telas no restream.

**Limite de banda do Wi-Fi da câmera** (2026-10-25, madrugada): 8192 kbps → o vídeo parou (0 fps) e o
ping da câmera foi de ~6 ms para ~100 ms; voltou com 4096. Em 4096 deu 20 fps num momento e 7,6 fps
(imagens até 6,8 s atrasadas) meia hora depois, com o **roteador também a ~97 ms** (rede da casa
congestionada por outro aparelho). Conclusão até aqui: 4096 é o que funciona; o degrau acima precisa
ser medido de novo com a rede calma (ping do roteador < 10 ms), em vários horários. A câmera está no
Wi-Fi **5 GHz** (a TV no 2,4 GHz).

**De novo com a rede calma (2026-10-25, ~00h30, roteador a 9 ms):** 5, 6, 7, 8 e 9 Mbps funcionaram
(12 fps, imagens no máximo ~0,6 s atrasadas). Mas à noite a câmera manda **só ~1 Mbps** de verdade,
qualquer que seja o limite (imagem P&B parada comprime muito), então o limite configurado nem é usado.
**O teste do limite só vale de dia** (cor, 20 fps, movimento). A falha de 8 Mbps foi com a rede
congestionada. Fica em 4096 até medir de dia; depois: o maior degrau estável menos um.

**Noite = 12 fps, e não é a exposição:** desligar `LowLuxMode` e cortar `ExposureParam.MostTime` pela
metade (0x10000 → 0x8000) não mudou nada (12,2 → 12,3 fps contados pelo PTS). No infravermelho o
sensor filma a ~12 fps. As duas mudanças foram desfeitas (`LowLuxMode 1`, `MostTime 0x00010000`).

**2,4 GHz × 5 GHz (câmera no 5, TV no 2,4)** — o caminho do vídeo é câmera →(Wi-Fi 5 GHz)→ roteador
→(cabo)→ PC (camera-ai) →(cabo)→ roteador →(Wi-Fi 2,4 GHz)→ TV.
- **5 GHz:** mais rápido e com menos interferência (vizinhos, Bluetooth, micro-ondas ficam no 2,4), mas
  alcança menos e perde muito mais força atravessando paredes e lajes.
- **2,4 GHz:** alcança mais longe e atravessa melhor as paredes, mas é mais lento e mais disputado.
- **Estarem em faixas diferentes não atrapalha, até ajuda:** o vídeo que chega da câmera e o que sai para
  a TV não disputam o mesmo rádio. A TV no 2,4 está ótima (sinal -36 dBm, 144 Mbps de conexão; precisa
  de ~4 Mbps).
- **Medido (2026-10-25, DVRIP `SystemInfo` `WifiAP` = busca de redes, e `WifiRouteInfo`):** o sinal da
  câmera no 5 GHz é **-33 dBm, "Excellent", `SignalLevel` 100**. A "NEVES 2.4" chega a -38 dBm (canal 2),
  e no 2,4 há mais 6 redes vizinhas, uma delas mais forte que a da casa (GTM_FAMILIA NEVES 2.4, -26 dBm,
  canal 11). Então **o sinal não é o problema** e passar a câmera para o 2,4 não ajudaria (seria mais
  lento e mais disputado): ficou no 5 GHz. As rajadas e o limite de ~4 Mbps vêm do rádio da própria
  câmera ou do roteador congestionado (ping do roteador em ~97 ms na mesma hora), não do alcance.

**DVRIP (porta 34567, admin / senha vazia, só leitura)** — o que o ONVIF não mostra:
- `SystemInfo`: `HardWare XM232_X6S-WEQ-H_8M`, `DeviceModel X6S-WEQ-H`, firmware de 2026-03-23.
- `SystemFunction`: **`SupportGunBallTwoSensorCamera`** (uma lente fixa "gun" + uma que gira "ball"),
  `SupportGunBallTwoSensorPtzLocate` (apontar a móvel pela fixa), **`SupportDetectTrack`** (seguir pessoa
  de fábrica), `SupportPTZDirectionControl`, `SupportPTZTour`, `SupportCameraWhiteLight`.
- `Camera.WhiteLight`: `WorkMode: Intelligent`, das 18h às 6h, acende **60 s a cada movimento**
  (`MoveTrigLight`). É a "luz branca que acende do nada". **Trocado para `Infrared` em 2026-10-25**
  (a pedido: à noite só infravermelho, imagem P&B, sem luz branca). Para voltar: `WorkMode: Intelligent`
  pelo DVRIP (`CONFIG_SET` 1040, mesmo objeto) ou no app iCSee.
- `Camera.Param`: `ExposureParam.MostTime 0x10000` (até ~65 ms por imagem: no escuro não passa de
  ~15 fps). `Camera.ParamEx`: `LowLuxMode 1` (modo pouca luz), `DayNightSwitch` com dia 07–18h.
- `Detect.DetectTrack`: `Enable 0` (seguir pessoa desligado), `Sensitivity 2`, `ReturnTime 300`.
  **Testado em 2026-10-25:** a lente de baixo **tem motor** (é a "ball") e o rastreamento a moveu, mas
  ela parou virada para a parede e **não voltou** (o `ReturnTime` de 300 s não a trouxe de volta, e o
  `GotoHomePosition` do ONVIF não move nada). Desligado de novo (`Enable 0`); a lente foi trazida de
  volta à vista da sala no olho, em passos pelo DVRIP, e essa posição salva como **preset 1**.
- **PTZ pelo DVRIP** (o ONVIF aceita mas não move): `OPPTZControl` (msg 1400), `Command`
  `DirectionLeft/Right/Up/Down`, `Parameter.Preset` 65535 para andar e -1 para parar (mandar sempre os
  dois), `SetPreset` / `GotoPreset` com `Preset` N. O motor é rápido (~0,5 s já muda bastante a vista),
  e com a lente inclinada esquerda/direita saem invertidas na imagem.
- `NetWork.Wifi` devolve a **senha do Wi-Fi em texto** para quem logar com a senha vazia: pôr senha
  na câmera (o camera-ai e a TV aceitam login).

**Firmware aberto (OpenIPC):** para XiongMai compatíveis existe o *Coupler*, instalado pelo botão de
atualização da própria câmera, **sem abrir**. Mas: voltar ao original só por UART (aí sim abrindo); o
suporte é bom no XM530 e parcial nos outros; há relato de câmera XM que ligou sem vídeo por sensor sem
driver. Não há registro desta placa (XM232 X6S, duas lentes, uma com motor). Risco alto, ganho incerto.

**Dia/noite por horário** (padrão): noite das 19h às 6h (`IrCutFilter` OFF), dia no resto (ON), conferido
a cada minuto e salvo em `server/state.json` (`dayNight`). No automático a câmera decide pela luz e
fica trocando no fim de tarde; a **luz branca forte** dela acende e apaga junto com essa troca. Escolher
Automático/Dia/Noite à mão desliga o horário. Só roda com o camera-ai ligado (a câmera guarda o
último modo). Se a luz branca incomodar à noite, o modo de luz (infravermelho / colorido / inteligente)
é do app iCSee; pelo ONVIF não dá para mudar (seria pelo DVRIP, porta 34567, ainda não feito).

**Movimento sem PC:** a câmera tem detector próprio por eventos ONVIF, sem login:
`CreatePullPointSubscription` em `/onvif/event_service`, depois `PullMessages` (precisa dos
cabeçalhos WS-Addressing `To` e `Action`). Tópicos: `tns1:VideoSource/MotionAlarm` (`State`) e
`tns1:RuleEngine/CellMotionDetector/Motion` (`IsMotion`).

**Série** `a6c24791bc60bb3e7pt4` nos dois IPs: é assim que a TV sabe que .41 e .42 são o mesmo aparelho.

### Funções expostas na interface
- Ver ao vivo (Nítido/Fluido), Foto, Gravar (local, `-c copy`), Ouvir (microfone),
  Imagem (brilho/contraste/saturação/nitidez + WDR + Dia/Noite), PTZ (se houver motor).
- Otimização automática de FPS/bitrate na conexão.
- **Detecção por IA no navegador** (menu ☰ → Detecção): TensorFlow.js + COCO-SSD
  (`lite_mobilenet_v2`), roda localmente, nenhuma imagem sai do aparelho. Desenha quadrados:
  **verde = pessoa**, **laranja = animal** (gato/cachorro/pássaro/etc.), **vermelho =
  detectou mas com pouca confiança**. Só pessoa/animais (objetos genéricos ignorados;
  `showObjects` liga objetos). Arquivos: `assets/detect.js` + `<canvas id=overlay>`. O
  modelo baixa da CDN na 1ª vez (precisa de internet uma vez; depois fica em cache).

### Endpoints do proxy
`/api/connect` `/api/state` `/api/stream` `/api/snapshot` `/api/ptz` `/api/encoder`
`/api/optimize` `/api/record` `/api/imaging` (GET/POST) `/api/daynight` `/api/audio`.

### Próximos possíveis
- Alerta de movimento (ONVIF PullPoint) + gravação só em evento.
- Push-to-talk (alto-falante) via DVRIP.
- Reboot / sync de hora / OSD de data via ONVIF.

## Firmware (OpenIPC / patch) — ainda em aberto

Não é mais urgente: a câmera já faz tudo (ver, PTZ, gravar local, otimizar) sem app e sem
nuvem, só por ONVIF/RTSP. Reescrever o firmware só valeria para: tirar de vez qualquer
"ligar pra casa" (telemetria p/ servidor XM), telnet/SSH permanente, ou subir FPS além do
que o firmware de fábrica deixa. Se formos: backup da flash por SPI/UART **antes**, e
checar se o chip (XM é bem suportado) entra no OpenIPC.

## Plano

1. **Confirmar portas e protocolo** (RTSP/ONVIF/DVRIP). Keystone — destrava o resto.
2. **Backup da flash** (SPI/UART) antes de tocar em firmware. Backup = volta garantida.
3. **Decidir o caminho:**
   - OpenIPC (se o chip for compatível): firmware aberto, RTSP/ONVIF, sem nuvem. É o
     "reescrever inteiro e otimizar".
   - Patch no firmware original (se o chip não for compatível): desligar só as travas.
4. **Otimizar FPS**: a queda de FPS em câmera XM quase sempre é bitrate/encoder mal
   configurado (CBR alto, GOP curto, resolução acima do que o sensor entrega a 25/30 fps,
   smart-codec/H.265 brigando com o decoder). Ajuste via ONVIF/DVRIP ou no firmware novo.

## Entregável deste diretório

- `index.html` + `assets/` — **interface mobile** de controle (live, PTZ, snapshot,
  gravação, presets de qualidade/FPS, configurações).
- `server/proxy.mjs` — proxy local que fala com a câmera (RTSP/ONVIF/DVRIP) e expõe uma
  API HTTP/WS limpa para a interface. A câmera não serve vídeo direto pro navegador, por
  isso o proxy. Pontos de ligação marcados com `TODO(confirmar-porta)`.

## Como rodar

```
node server/proxy.mjs            # sobe tudo (UI + API) numa porta só; acha o ffmpeg em server/bin
```
Depois é só abrir:
- PC: `http://127.0.0.1:8787`
- Celular (mesma rede Wi-Fi): `http://192.168.0.33:8787`

O proxy serve a interface e a API na **porta 8787** (bind em 0.0.0.0 p/ o celular alcançar).
A interface usa a mesma origem, então não precisa configurar endereço. Login: tenta
`admin`/senha vazia e padrões XM sozinho; trocou a senha no app? põe no ⚙. Gravações em
`server/gravacoes/`.

### Testar no celular (passo a passo)
1. No PC, rodar `node server/proxy.mjs` (deixar a janela aberta).
2. Celular na **mesma rede Wi-Fi** do PC.
3. Abrir no navegador do celular: **`http://192.168.0.33:8787`** (IP do PC nesta rede;
   se o IP mudar, ver com `ipconfig`).
4. Não abriu? É o **Firewall do Windows** bloqueando o Node. Liberar a porta 8787 (uma vez),
   em PowerShell como admin:
   `New-NetFirewallRule -DisplayName "cam-control" -Direction Inbound -Protocol TCP -LocalPort 8787 -Action Allow -Profile Private`
   (ou, quando o Windows perguntar ao subir o node, marcar "Redes privadas" → Permitir).
5. A detecção por IA precisa de internet no celular na 1ª vez (baixar o modelo); depois roda offline.

## Verificado end-to-end (2026-10-20)

- [x] Conexão ONVIF (admin/vazio), device info e perfis lidos.
- [x] Vídeo ao vivo real na interface (MJPEG via ffmpeg) — print em `evidence/interface.png`.
- [x] Snapshot (`evidence/snapshot-teste.jpg`, 92 KB).
- [x] Otimização aplicada e persistida: main 12→20 fps/4096k, sub 12→25 fps/1024k.
- [x] Stream MJPEG medido (~25 fps no sub).

## Status

- [x] Câmera localizada e identificada via REA.
- [x] Interface mobile completa (live, PTZ, zoom, presets, foto, gravação, áudio, noturno).
- [x] Proxy ONVIF/RTSP funcionando (sem app, sem nuvem).
- [x] Portas confirmadas: RTSP 554, DVRIP 34567, ONVIF 8899, HTTP 80.
- [x] Credencial resolvida (admin / senha vazia).
- [x] Vídeo ao vivo fora do app.
- [x] FPS otimizado.
- [ ] (Opcional) Backup da flash + firmware OpenIPC/patch.
- [ ] (Próximo) PTZ: confirmar se este modelo tem motor; se for fixa, esconder o d-pad.
- [ ] (Próximo) Áudio/Noturno: ligar via DVRIP (porta 34567) — hoje são stubs.
