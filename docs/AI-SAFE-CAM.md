# AI Safe Cam: inteligência artificial para a sua câmera

Plano do produto, montado a partir do camera-ai e das conversas de 24 e 25/10/2026. Diz o que vai ser
feito, como, e por quê. O que foi medido na câmera de teste está em [`DESCOBERTA.md`](DESCOBERTA.md).

## A ideia

Um app próprio, **AI Safe Cam**, que dá IA a qualquer câmera Wi-Fi que a pessoa já tem: ver ao vivo,
saber quando aparece uma pessoa ou um animal, receber aviso, ver de fora de casa, seguir a pessoa na
imagem. **Não depende do nevtv**: qualquer pessoa com câmera e celular usa.

O nevtv entra como parceiro: dentro do app, **"Quer ver suas câmeras na TV e deixar ela vigiando 24h?
Instale o nevtv: Liberte sua TV."** E quem tem nevtv ganha o convite para o AI Safe Cam. Um produto
puxa o outro.

## Por que assim (o que aprendemos com a câmera de teste)

- **A câmera é o gargalo, não o software.** A câmera XM de duas lentes não aguenta mais de uma
  conexão ao mesmo tempo: com duas, a segunda recebeu 0,2 quadro por segundo. E entrega o vídeo em
  rajadas pelo Wi-Fi dela, mesmo com sinal "excelente" (-33 dBm): o rádio é fraco por dentro.
- **Cada câmera é de um jeito.** Resolução, banda, fps à noite (12 nesta), motor, luz, protocolo do
  fabricante: muda de marca para marca e até de hora para hora (20 fps num momento, 7 meia hora
  depois, com a rede da casa congestionada).
- **O que é feito "dentro" da câmera é frágil.** O rastreamento de fábrica girou a lente e a deixou
  virada para a parede. Comandos do fabricante mudam de modelo para modelo.
- **O que é feito com a imagem funciona em qualquer câmera.** Detectar pessoa, desenhar o quadrado,
  dar zoom acompanhando, avisar, gravar: só precisa do vídeo, que quase toda câmera manda (RTSP/ONVIF).

Daí as três decisões: **(1)** a base é a imagem; **(2)** uma conexão só com cada câmera; **(3)** cada
câmera recebe o que ela aguenta, medido, não chutado.

## As três camadas

1. **Base, para todas as câmeras (pela imagem):** vídeo ao vivo, IA de pessoa e animal, seguir
   digital (zoom que acompanha a pessoa), avisos, clipes dos eventos, escolha automática do vídeo que
   a câmera aguenta.
2. **Padrão aberto (ONVIF), para a maioria:** brilho, contraste, cor, nitidez, dia/noite, aviso de
   movimento da própria câmera, motor quando ela aceita pelo padrão.
3. **Extras da marca, opcionais:** luz branca, rastreamento de fábrica, comandos próprios. Só para
   câmeras com perfil conhecido (`profiles/*.json`); sem perfil, nada quebra, só não aparece o extra.

## A vistoria: "Teste sua câmera"

O primeiro contato do cliente, sempre grátis. Roda **no app do celular ou na TV com nevtv** (no mesmo
Wi-Fi da câmera; um site não consegue falar com câmera da rede de casa). Uns 30 a 60 s:

1. **Conhecer a câmera:** marca, modelo, vídeos que oferece (grande e pequeno), ONVIF, motor,
   microfone, se ela mesma detecta pessoa.
2. **Medir a entrega de verdade:** pedir o vídeo pequeno e o grande, ~30 s cada, e contar quantas
   imagens chegam contra quantas deveriam, o quanto atrasam (rajadas), se a conexão cai, o tempo de
   resposta. **Força do sinal sozinha não basta** (a câmera de teste tinha -33 dBm e engasgava).
3. **Resultado claro:** "Nível 2 – Bom", a lista do que funciona (✅/❌) e uma dica para subir de nível
   ("aproxime a câmera do roteador").

A medição **continua em segundo plano**: se a câmera engasgar várias vezes, desce um nível sozinha;
estável por um bom tempo, sobe de volta. O nível aparece nas informações da tela.

### Os níveis

| Nível | Quando | O que tem |
|---|---|---|
| **1 – Básico** | câmera fraca ou instável | vídeo pequeno, reserva maior (Fluidez Suave), aviso de movimento, IA mais devagar |
| **2 – Bom** | estável no vídeo pequeno | + IA rápida, seguir digital, clipes quando aparece alguém |
| **3 – Completo** | estável no vídeo grande | + imagem nítida de cada lente, zoom nítido, menor atraso |
| **Extras** | a marca tem perfil | motor, luz, rastreamento de fábrica (independem do nível) |

## Onde a IA roda (sem PC)

Quase ninguém tem um PC ligado com placa de vídeo, então a IA de cada casa vem da melhor fonte que
houver:

1. **A própria câmera:** muitas já detectam pessoa e avisam pelo ONVIF. A mais leve; a vistoria descobre.
2. **A TV com nevtv:** casa com TV ou TV box sempre na tomada; o Android dela vigia 24h em segundo plano
   (aviso de movimento, IA da câmera). O chip da TV é fraco para IA pesada.
3. **O celular:** IA boa e rápida, mas só com o app aberto (o sistema para o app em segundo plano e a
   bateria acaba).
4. **A nuvem:** IA 24h de verdade, para o plano pago (é o que custa: processamento e banda).
5. **AI Safe Cam Box (opção):** um aparelhinho barato com chip de IA, vendido junto.
6. **PC com o camera-ai:** o modo avançado de hoje, como bônus para quem tem.

## Planos

Referências de mercado (de matérias e agregadores, confirmar antes de decidir):

- Brasil: Vivo com câmeras Intelbras, de **R$ 16/mês** (1 câmera, só ao vivo) a **R$ 60/mês** (2
  câmeras com gravação); Positivo a partir de **R$ 14,90/mês**, plano de 5 câmeras **R$ 99,90/mês**.
- Fora: Wyze **US$ 2,99/mês** por câmera, Ring **US$ 4,99/mês**, Scrypted ~**US$ 40/ano** para 4.

Proposta:

| Plano | Preço | O que tem |
|---|---|---|
| **Grátis** | R$ 0 | teste da câmera (nível), ao vivo em casa, aviso de movimento |
| **Essencial** | ~R$ 12,90/mês | até 2 câmeras, IA de pessoa/animal, avisos no celular, ver de fora de casa, clipes por 7 dias |
| **Família** | ~R$ 24,90/mês | até 5 câmeras, clipes por 30 dias, seguir digital, várias pessoas da casa |

Desconto no anual. Fica abaixo de Vivo e Positivo, e com IA, que elas não destacam.

**Teste grátis de 30 dias** do plano completo:
- O **teste da câmera (nível) é sempre grátis**: é o que atrai.
- Os 30 dias começam quando a pessoa **conecta a primeira câmera**, não no cadastro.
- **Sem cartão** no começo (mais gente experimenta); lembrete no dia 25.
- Ao acabar, **volta para o Grátis**, não perde o app: continua por perto para assinar depois.
- No teste, a nuvem tem limite (clipes por 7 dias) para não pesar no custo.

## Licença da IA

O YOLO11 usado hoje no camera-ai é **AGPL-3.0**: para produto comercial, exige licença da Ultralytics
ou abrir todo o código. Para o AI Safe Cam, usar um modelo **Apache 2.0** (uso comercial livre):
**RF-DETR** (o mais preciso), **D-FINE**, **RT-DETR** ou **YOLOX**. Antes de lançar, conferir a licença no
repositório de cada um e os termos dos dados de treino.

## Nome, privacidade e LGPD

- Pesquisar **"AI Safe Cam"** no INPI e nas lojas (Google Play, App Store) antes de investir na marca.
- São imagens de dentro de casa (no teste, a câmera chegou a mostrar alguém dormindo). Então:
  processar **em casa por padrão**, consentimento claro, criptografia no que for para a nuvem, apagar
  sozinho depois do prazo, política de privacidade simples.
- Vira argumento de venda: **"suas imagens não saem da sua casa"**.

## O que já existe e serve de base

- **camera-ai:** conexão única com a câmera (`server/live.mjs`), IA (`server/ai.mjs`: YOLO11s na placa
  de vídeo ou COCO-SSD), vídeo H.264 em MPEG-TS com a hora de cada imagem, som, dia/noite por horário,
  API das telas (`/api/screen`), descoberta pela rede (UDP 8788), perfis de câmera.
- **nevtv (TV):** player com Fluidez (atraso fixo pela hora de cada imagem), quadrados da IA no tempo da
  imagem, informações na tela, painel montado a partir do que o camera-ai lista, modo direto quando o
  camera-ai não está.
- **Conhecimento da câmera XM:** ONVIF, eventos de movimento, DVRIP (configurações), limites de vídeo.

## Como vai ser feito (fases)

1. **Vistoria e níveis no nevtv** ("Testar minha câmera"): vídeo, medição, nível, dicas. Usa o que já existe.
   A medição e o nível já estão no camera-ai (`server/quality.mjs`, `/api/vistoria`, 25/10/2026);
   primeira medida da câmera de teste, à noite: 12,5 de 12,4 quadros/s, 1,1 Mbps no grande e 0,1 no
   pequeno, rajadas de 0,4 s, sem perdas nem travadas = Nível 3. Falta a tela no nevtv e a versão que
   roda sem o PC (o mesmo medidor no app/TV).
2. **Seguir digital** na tela (zoom que acompanha o quadrado da IA) e **escolha automática do vídeo**
   (grande ou pequeno) pelo nível.
3. **Trocar o modelo de IA** por um Apache 2.0 (RF-DETR ou D-FINE) e medir na câmera de teste.
4. **App AI Safe Cam no celular:** conectar câmera, vistoria, ao vivo, avisos; o nevtv como "TV que vigia".
5. **Conta, planos e teste de 30 dias** (loja de apps), acesso de fora de casa, clipes na nuvem.
6. **IA na nuvem** e/ou **AI Safe Cam Box** para o 24h de verdade.

## Em aberto

- **Medir de dia o limite de banda** da câmera de teste (à noite ela só manda ~1 Mbps) e fixar o
  "limite menos um degrau".
- **Por que a câmera derruba a conexão:** o camera-ai agora registra a hora e o motivo de cada queda;
  ler depois de um dia de uso e procurar padrão.
- **Seguir pessoa com o motor:** feito à parte (pelo Codex), ainda não funcionou e não aparece no
  painel da TV.
- **Firmware aberto (OpenIPC)** na câmera de teste: há caminho sem abrir (Coupler), mas voltar exige abrir,
  o modelo é raro e o risco é alto. Conversa pendente.
- **Senha do modem** (aguardando a operadora): ver limite de banda por aparelho, canal e congestionamento
  do 5 GHz, e bloquear a internet só da câmera (tirar ela dos servidores do fabricante).
- **Senha na câmera:** sem ela, qualquer aparelho da rede lê as configurações, até a senha do Wi-Fi.
