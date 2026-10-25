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
      PC (`server/ai.mjs`) e vai para todas as telas; o navegador só desenha. RF-DETR medium na placa
      de vídeo (Apache 2.0, ~45 ms, até 10 análises/s; acerta mais que o YOLO11s na câmera de teste), YOLO11s
      (AGPL) e COCO-SSD de reserva.
- [ ] **Seguir pessoa (motor validado; falta o teste com alguém andando):** a IA acompanha a pessoa,
      move a lente inferior pelo DVRIP e volta ao preset 1 após 12 s sem detecção (conferido). Direções
      conferidas na câmera (pulso de 450 ms ≈ 1/4 da imagem). Cada movimento vai para o log. Fica
      lembrado entre reinícios. O rastreamento de fábrica permanece desligado. Só a lente móvel guia o
      motor (a busca pela lente fixa tirava a móvel da vista do quarto). Preset 1 salvo de novo em
      25/10 apontando para o quarto (`{"dir":"sethome"}` em `/api/ptz` salva onde a lente está).
- [x] **Teste da câmera (vistoria) e níveis** (`/api/vistoria`, menu ☰ → Teste da câmera): mede o
      vídeo como a câmera manda (quadros que chegam × filmados, rajadas, perdas, travadas, quedas), sem
      abrir conexão a mais; nível 1/2/3 em segundo plano (desce em ~30 s, sobe após 10 min estável);
      "Testar agora" mede o vídeo grande e o pequeno, 30 s cada, quando a câmera está livre.
- [x] **Vídeo da câmera pelo nível** (controle "Vídeo da câmera": Automático / Grande / Pequeno): no
      automático, o ao vivo puxa o vídeo pequeno só no Nível 1 e volta a tentar o grande depois de 10 min
      estável no pequeno. Conferido: pequeno ~0,1 Mbps, grande ~1,1 Mbps (à noite).
- [x] Reconexão: a sessão DVRIP se refaz sozinha, e se a câmera some ~90 s o camera-ai a procura de
      novo na rede (ela pode voltar com outro IP).
- [x] **Zoom que acompanha (seguir digital)** no navegador (menu ☰): amplia em volta das pessoas
      que a IA vê e desliza junto, sem motor (qualquer câmera); volta à imagem inteira após 3 s sem
      ninguém. Quadrados da IA agora no lugar certo com uma lente só (o corte do object-fit era ignorado).
      Falta: o mesmo no nevtv e o teste com alguém andando.
- [x] **Senha do camera-ai:** o próprio PC entra direto; celular e TV pedem a senha uma vez (o
      navegador guarda um cookie, a TV uma chave). Criada na primeira vez (aparece no log), trocada no
      menu ☰ → Senha. 5 erros seguidos: 1 minuto de espera. Só `index.html` e `assets/` são servidos
      (antes, `server/state.json` e os clipes saíam para qualquer um na rede).
      Sem a senha: a TV (ou um celular) pede acesso e quem já entrou toca em Permitir no navegador.
- [x] **Atraso da TV pelo medido:** `/api/screen` diz quanto a TV precisa segurar (maior rajada dos
      últimos 5 min × 1,3 + 0,25 s, de 0,5 a 4 s); no nevtv, Fluidez → Automático usa esse valor.
- [ ] **Senha da câmera** (`/api/camera-password`): pronto no camera-ai, ainda não aplicado. Sem senha,
      a câmera entrega até a senha do Wi-Fi pelo DVRIP.
- [x] **Perfil de cada câmera** (`server/perfil.mjs`, `server/perfis/<serial>.json`, `/api/perfil`): o que ela
      tem, o que foi conferido e o histórico (vistorias, níveis, conexões, recursos). Ao conectar, lê o que a câmera
      diz (ONVIF; nas XM, a lista `SystemFunction` pelo DVRIP). A **vistoria completa** (menu ☰ → Teste da câmera →
      Vistoria completa, `/api/perfil/vistoria`) confere de verdade: acende a luz, troca a visão noturna, gira a
      lente, e desfaz; só testa o que falta. Movimento confere sozinho no primeiro aviso. "Não tem" só com prova
      clara; sem dar para ver (ex.: noturna com o quarto claro) fica "a conferir" e o botão continua. As telas só
      mostram os botões que o perfil permite. Câmeras do mesmo modelo começam com o que já se sabe
      (`server/perfis/modelos/`). Nesta câmera: motor, visão noturna, luz branca e movimento conferidos.
- [x] **Uma conexão de vídeo por vez:** esta câmera não aguenta duas (medido em 25/10: com a TV direto no vídeo
      grande e o camera-ai no pequeno, a TV caiu para 5 quadros/s e o camera-ai travou). Sem tela assistindo pelo
      camera-ai, ele usa só o vídeo pequeno (~0,15 Mbps) para seguir e detectar; uma TV vendo direto avisa
      (`/api/screen/direct`, a cada 20 s) e o camera-ai solta a câmera até ela parar.
- [x] **Painel das telas enxuto:** só Visão noturna (vale até a próxima virada do horário) e Seguir pessoa.
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
- [ ] **Alerta de movimento** (ONVIF PullPoint) — avisar e/ou gravar só quando mexe. *Parte feita:* o
      camera-ai assina o movimento da câmera (`server/motion.mjs`) e manda `{type:"motion"}` em
      `/api/screen/events` (e `motion` no `/api/screen`); falta ver um "sim" de verdade (girar o motor
      não dispara), avisar no celular e gravar por evento.
- [ ] Notificação quando detectar **pessoa** (som/vibração/destaque).
- [ ] **Falar pela câmera** (push-to-talk pelo alto-falante) via DVRIP 34567.
- [ ] Confirmar se há **motor PTZ** (provável câmera fixa → esconder o direcional).
- [x] **Clipes de quando aparece alguém** (`server/clips.mjs`, menu ☰ → Eventos; na TV, "Gravar quando
      aparecer alguém"): 5 s antes até 10 s depois (máx. 60 s), MP4 sem reencodar, com foto; 7 dias no PC.
      Só quem *chega* gera clipe (quem fica parado, dormindo, não gera um atrás do outro). Desligado até ligar.
      Testado com clipe de teste e com a regra simulada; falta ver um de verdade.
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
