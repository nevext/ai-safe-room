# AI Safe Room — o caminho até publicar

Plano combinado em 25/10/2026. O que está pronto fica em `FEATURES.md`; aqui fica o que falta para virar app de
loja, quem faz cada parte e as decisões tomadas.

## Decisões

- **O app abre como câmera normal, igual à TV** (direto na câmera). Toda função de IA (vídeo pelo camera-ai,
  detecção, seguir, rostos, Proteção) vem desligada; a pessoa liga cada uma, e o app explica o que faz e que depende
  da conexão da câmera. Hoje a IA do servidor liga sozinha: mudar no primeiro uso.
- **Outras marcas são prioridade desde já**: o encaixe por marca é desenhado primeiro, XM e Tuya juntas, e a câmera
  ONVIF genérica na rede de casa.
- **Avisos pelo FCM** porque, no Android, é o único jeito de o aviso chegar com o app fechado sem um serviço ligado o
  tempo todo (OneSignal e afins usam o FCM por baixo).
- **O banco é o Firebase** (decidido em 27/10/2026, no lugar do Postgres): contas (Auth), casas, família, convites,
  pedidos para entrar e o plano da casa no Firestore; avisos pelo FCM. Vídeo, fotos e clipes nunca vão para o
  Firebase: ficam na casa ou no Drive da pessoa.
- **O camera-ai roda num servidor na nuvem** para o pesado (vídeo, IA, nuvem das marcas) e é quem grava o plano depois
  de conferir a compra na Play (Admin SDK). Nada de caixinha vendida nem PC ligado na casa da pessoa.
- **O servidor chega na câmera pela nuvem do fabricante**, que é como os outros apps fazem (a câmera já fica
  conectada na nuvem da marca pelo número de série). Nada em casa, nada no roteador.
- **Não existe nuvem para todas as câmeras**: o servidor tem um encaixe por marca (vincular, vídeo ao vivo, girar,
  alarmes). IA, rostos, atividades e app são os mesmos para todas.
  - 1º **XM / JFTech** (iCSee, XMEye): é a nossa câmera. Plataforma oficial em open.jftech.com (OpenApi V3: vincular
    a câmera à conta, login com usuário e senha da câmera, ao vivo em HLS pelo número de série, PTZ, foto, config,
    mensagens por MQTT). Exemplo: github.com/jlinklab/jlink-restful-cpp-demo.
  - 2º **Tuya** (Smart Life e marcas próprias): iot.tuya.com.
  - Depois **Imou / Dahua** (open.imoulife.com) e **EZVIZ / Hikvision** (open.ezviz.com).
  - Sem nuvem aberta (Tapo, Mibo): só pela rede de casa (ONVIF/RTSP); de fora, o app em casa fazendo a ponte
    (plano B).
- **Ver ao vivo em casa continua direto na câmera** (o lag zero que conseguimos). De fora, pelo servidor. O HLS da
  nuvem tem alguns segundos de atraso: não importa para IA e avisos.
- **Avisos pelo Firebase (FCM)**, no lugar do serviço `Guard` sempre ligado.
- **Clipes no Google Drive da pessoa**: 30 dias grátis; se a assinatura falhar, os clipes são apagados. Cobrança pela
  Google Play.

## O que o dono faz (contas e pagamentos)

1. [ ] **XM / JFTech**: conta de desenvolvedor em open.jftech.com, criar o app "AI Safe Room" e pegar `uuid`,
       `appKey`, `appSecret`, `movecard` (vão em `server/jf.json`, fora do git). É o teste que confirma o plano.
       Ver se pede empresa (CNPJ), preço e limites.
2. [ ] **Servidor**: Oracle Cloud Always Free em Brazil East (São Paulo), Ampere A1, Ubuntu, 4 OCPU / 24 GB, com a
       chave SSH pública que eu gerar; passar o IP. Reserva: Vultr São Paulo (uns US$ 12–24/mês). Evitar quem não
       tem servidor no Brasil (Hetzner, DigitalOcean).
3. [ ] **Firebase**: projeto "AI Safe Room", app Android `io.nevext.aisaferoom`, `google-services.json` em
       `android/app/` e a chave da conta de serviço em `server/` (fora do git).
4. [ ] **Google Play**: conta de desenvolvedor (US$ 25, uma vez) e uns 12 testadores para o teste fechado de 14 dias
       (exigido de conta pessoal nova).
5. [ ] (Opcional, já) conta na **Tuya** em iot.tuya.com.

## Sem a IA, o app é o app da câmera (sem o computador)

Regra do dono (26/10): sem a IA o app funciona como o app original da câmera, só celular e câmera; o computador é
só para a IA.
- [x] Vídeo direto (RTSP), visão noturna, luz, girar, seguir e pessoa da câmera, foto, relógio, senha e falar, pelo
      DVRIP no celular (`Dvrip.kt`, `CameraLink.kt`). Testado com o camera-ai desligado.
- [ ] Atividades e avisos sem o computador: o celular ouve os avisos da câmera (pessoa, movimento) e guarda.
- [ ] Primeiro uso sem o computador: achar a câmera na rede e entrar com a senha dela (hoje o app aprende com o
      camera-ai uma vez).
- [ ] Fora de casa sem o computador: pela nuvem da marca (XM / JFTech).

## Família: donos, membros e convidados (feito na 0.2.0)

Até 2 **donos** (mexem em tudo, e só eles permitem quem entra, escolhendo o papel); **membros** veem ao vivo, ouvem,
falam, recebem os alertas e as atividades, luz, visão noturna, girar e sirene, mas **não** mexem na Proteção, na
Privacidade, na IA, nos rostos, nas senhas nem na família; **convidados** (babá, diarista) por 4 h, 8 h, 1 dia ou 7
dias, só o ao vivo, ouvir e falar. A regra fica no camera-ai (`allowed()` em `server/proxy.mjs`, 403 para o resto);
o app só esconde o que o papel não pode. O computador da casa é sempre dono. Quem já tinha acesso: o aparelho mais
antigo virou dono, os outros membros.

Falta: o vídeo direto da câmera leva a senha dela no endereço (RTSP), então um convidado vencido que guardou o
endereço ainda veria a câmera até a senha mudar. O certo é um usuário só de ver na câmera (XM aceita usuários extras).

## Ideia: configurar câmera nova pelo AI Safe Room

Quem comprou a câmera baixa o AI Safe Room para pôr ela no Wi-Fi (de graça), em vez do app da marca. É a porta de
entrada: o app já está na casa no primeiro dia e a IA fica a um toque. Cada marca põe a câmera no Wi-Fi de um jeito
(QR code que a câmera lê, ponto de acesso, som): vem junto com a nuvem de cada marca, XM primeiro, depois Tuya.

## O que eu (Claude) faço

1. [x] **Contas**: Entrar com o Google, a casa e a família no Firestore (0.3.0), pedir para entrar pelo Wi-Fi da
       câmera (0.3.2).
   - [x] **Base dos planos** (0.3.5): `homes/{id}.plan` = { tier, until }, só o servidor grava (regras); o app lê
         (`Plan.kt`) e mostra em Ajustes. Sem plano na casa = "teste", tudo liberado. Falta, com a Play: Play Billing
         no app, o servidor conferir a compra (notificações em tempo real da Play) e gravar o plano, e cada função
         olhar `plan.allows(...)`.
2. [ ] **Encaixe de nuvens**: a interface por marca e o da XM pronto para receber as chaves; testar com a nossa
       câmera assim que elas chegarem.
3. [ ] **Avisos pelo FCM**: servidor e app, faltando só as chaves.
4. [ ] **Regras da Play**:
   - [x] política de privacidade (rascunho em docs/PRIVACIDADE.md e na tela Privacidade; falta revisão jurídica)
   - [x] termos de uso (rascunho em docs/TERMOS.md e na tela Sobre → Termos)
   - [ ] "Apagar minha conta e meus dados" dentro do app
   - [ ] formulário Data safety (o texto pronto)
   - [ ] chave de assinatura, AAB, textos e imagens da loja
   - [ ] conferir o nome "AI Safe Room" (INPI e loja)
5. [ ] **Primeiro uso guiado**: criar conta → achar a câmera → senha da câmera → vistoria → pronto.
6. [ ] **Câmera ONVIF genérica** (outras marcas na rede de casa).
7. [ ] **Qualidade**: relatório de travamentos, telas de erro claras, atualização do servidor sem parar tudo.
8. [ ] **Clipes no Google Drive** com a assinatura.
9. [ ] **Assinatura pela Google Play** (só quando o app estiver lá): a assinatura é da casa, a família é grátis
       (planos em `docs/AI-SAFE-CAM.md`).

## Depois

- Falar pela câmera e sirene (pedir antes de fazer barulho no quarto).
- Vários cômodos e câmeras, widget, linha do tempo para voltar o vídeo, versão iPhone.
- Rostos: ajustar o limite da roupa (0,8) com imagens reais de dia.
- Trocar a senha da câmera: a tela existe, falta a confirmação explícita do dono para aplicar.
