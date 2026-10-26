# AI Safe Room — o caminho até publicar

Plano combinado em 25/10/2026. O que está pronto fica em `FEATURES.md`; aqui fica o que falta para virar app de
loja, quem faz cada parte e as decisões tomadas.

## Decisões

- **O camera-ai roda num servidor na nuvem**, com banco de dados de verdade (Postgres). Nada de caixinha vendida
  nem PC ligado na casa da pessoa.
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

## O que eu (Claude) faço

1. [ ] **Servidor com contas**: Postgres, criar conta e entrar no app, família compartilhada (no lugar das chaves por
       aparelho), câmeras por conta.
2. [ ] **Encaixe de nuvens**: a interface por marca e o da XM pronto para receber as chaves; testar com a nossa
       câmera assim que elas chegarem.
3. [ ] **Avisos pelo FCM**: servidor e app, faltando só as chaves.
4. [ ] **Regras da Play**:
   - [ ] política de privacidade (rostos são dado biométrico, sensível na LGPD: consentimento, onde fica, como apagar)
   - [ ] "Apagar minha conta e meus dados" dentro do app
   - [ ] formulário Data safety (o texto pronto)
   - [ ] chave de assinatura, AAB, textos e imagens da loja
   - [ ] conferir o nome "AI Safe Room" (INPI e loja)
5. [ ] **Primeiro uso guiado**: criar conta → achar a câmera → senha da câmera → vistoria → pronto.
6. [ ] **Câmera ONVIF genérica** (outras marcas na rede de casa).
7. [ ] **Qualidade**: relatório de travamentos, telas de erro claras, atualização do servidor sem parar tudo.
8. [ ] **Clipes no Google Drive** com a assinatura.

## Depois

- Falar pela câmera e sirene (pedir antes de fazer barulho no quarto).
- Vários cômodos e câmeras, widget, linha do tempo para voltar o vídeo, versão iPhone.
- Rostos: ajustar o limite da roupa (0,8) com imagens reais de dia.
- Trocar a senha da câmera: a tela existe, falta a confirmação explícita do dono para aplicar.
