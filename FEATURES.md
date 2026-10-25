# camera-ai — funcionalidades

Lista do que já funciona e do que falta. Marcar aqui conforme avança.

## ✅ Pronto (testado na câmera real)

- [x] Descoberta e identificação da câmera (XM dual-lens) — ver `docs/DESCOBERTA.md`.
- [x] Proxy ONVIF/RTSP (`server/proxy.mjs`), UI + API na porta 8787, acessível no celular.
- [x] Vídeo ao vivo (RTSP→MJPEG via ffmpeg), sem app e sem nuvem.
- [x] Seletor de lente: **Câmera 1 / Câmera 2 / Ambas** (recorte das lentes empilhadas).
- [x] Lente única usa stream de alta resolução (nitidez).
- [x] Foto (download) e gravação local (`-c copy`).
- [x] Ouvir o microfone (RTSP→MP3).
- [x] Ajustes de imagem: brilho, contraste, saturação, nitidez, WDR, **Dia/Noite** (IrCut).
- [x] Otimização automática de FPS/bitrate na conexão (12→20/25 fps).
- [x] **Detecção por IA no camera-ai** (pessoa=verde, animal=laranja, incerto=vermelho): roda no
      PC (`server/ai.mjs`) e vai para todas as telas; o navegador só desenha. YOLO11s na placa de
      vídeo (~12 ms, 10 análises/s), COCO-SSD de reserva.
- [ ] **Seguir pessoa (motor validado; falta o teste com alguém andando):** a IA acompanha a pessoa,
      move a lente inferior pelo DVRIP e volta ao preset 1 após 12 s sem detecção (conferido). Direções
      conferidas na câmera (pulso de 450 ms ≈ 1/4 da imagem). Cada movimento vai para o log. Fica
      lembrado entre reinícios. O rastreamento de fábrica permanece desligado.
- [x] **Teste da câmera (vistoria) e níveis** (`/api/vistoria`, menu ☰ → Teste da câmera): mede o
      vídeo como a câmera manda (quadros que chegam × filmados, rajadas, perdas, travadas, quedas), sem
      abrir conexão a mais; nível 1/2/3 em segundo plano (desce em ~30 s, sobe após 10 min estável);
      "Testar agora" mede o vídeo grande e o pequeno, 30 s cada, quando a câmera está livre.
- [x] Reconexão: a sessão DVRIP se refaz sozinha, e se a câmera some ~90 s o camera-ai a procura de
      novo na rede (ela pode voltar com outro IP).
- [x] **API das telas** (`/api/screen`): as TVs com o nevtv mostram a câmera com vídeo nítido, IA e
      os controles que o camera-ai listar; sem o PC, seguem direto da câmera (sem IA).
- [x] Interface estilo nevext (Geist, azul #2563eb), player com controles que somem,
      tela cheia e menu.
- [x] Qualidade: automática / alta / fluido (menu ☰).

## 🔜 Pendente / próximos

### Empacotar como app (APK)
- [ ] Transformar a interface web em **APK Android** (WebView apontando pro proxy, ou
      Capacitor / PWA→APK com Bubblewrap). Decidir abordagem.
- [ ] Ícone + splash do app (marca nevext).
- [ ] Modo de descoberta do IP da câmera/proxy (hoje fixo 192.168.0.41 / 8787).
- [ ] Rodar o proxy: ou embarcado (precisa de ffmpeg no Android — complicado) ou o proxy
      fica num PC/servidor e o app só consome. Definir arquitetura do APK.

### Funcionalidades da câmera
- [ ] **Alerta de movimento** (ONVIF PullPoint) — avisar e/ou gravar só quando mexe.
- [ ] Notificação quando detectar **pessoa** (som/vibração/destaque).
- [ ] **Falar pela câmera** (push-to-talk pelo alto-falante) via DVRIP 34567.
- [ ] Confirmar se há **motor PTZ** (provável câmera fixa → esconder o direcional).
- [ ] Gravação **agendada** / contínua com rotação de arquivos.
- [ ] Reiniciar câmera / sincronizar hora / ligar-desligar OSD (data na imagem) via ONVIF.
- [ ] Cartão SD: listar e reproduzir gravações que ficam na própria câmera (se houver slot).

### Imagem / qualidade
- [ ] **Upscaling por IA na FOTO** (ex.: ESRGAN) — melhorar a resolução de um print
      (ao vivo não é viável no navegador).
- [ ] Zoom digital (recortar e ampliar um pedaço da lente de alta resolução).
- [ ] Filtro de nitidez opcional no vídeo.

### Robustez / produto
- [ ] Suporte a **mais de uma câmera** (grade / troca) quando houver outro aparelho.
- [ ] Autenticação na interface (hoje qualquer um na rede acessa o proxy).
- [ ] Empacotar o proxy como serviço (iniciar junto com o Windows).

## Ideias / dúvidas em aberto
- Firmware aberto (OpenIPC) ou patch: só se quiser matar telemetria XM ou subir FPS além
  do que o firmware deixa. Exige backup da flash por SPI/UART antes. (Aí o REA volta a ser útil.)
