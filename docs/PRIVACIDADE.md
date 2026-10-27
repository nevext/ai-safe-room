# AI Safe Room — Política de privacidade

Versão de 27/10/2026. O AI Safe Room é mantido por David Neves (Nevext, projeto independente). Contato:
nevext@outlook.com.

## Quem somos e a quem isto vale

O AI Safe Room é um app para ver e cuidar das câmeras da sua casa. O vídeo, as imagens e os rostos ficam **na sua
casa**: no celular, na câmera e, se você usar a IA, no seu computador (o camera-ai). Nós não recebemos o seu vídeo, as
suas imagens nem os rostos das pessoas da sua casa. Na nuvem fica só a sua conta e a sua casa (veja abaixo).

Uso doméstico: a LGPD não se aplica a quem trata dados "para fins exclusivamente particulares e não econômicos" (art.
4º, I), que é o caso de quem filma a própria casa. Mesmo assim o app segue as regras da lei, porque as câmeras podem
filmar outras pessoas (visitas, quem trabalha na casa), e porque, quando houver o servidor na nuvem, nós passaremos a
tratar dados de quem usa.

## O que o app guarda, onde e por quanto tempo

| O quê | Onde | Por quanto tempo |
|---|---|---|
| Vídeo ao vivo | Não é guardado: vai da câmera para a tela | — |
| Atividades (pessoa, movimento, a hora) | No computador de casa | 30 dias |
| Miniaturas das atividades | No computador de casa | 30 dias |
| Rostos cadastrados (o código do rosto e uma foto pequena) | No computador de casa | Até você apagar a pessoa |
| Rostos desconhecidos vistos numa visita (para o "Quem é?") | No computador de casa | 7 dias |
| Senha da câmera e chave de acesso | Neste celular, criptografadas (Android Keystore) | Até desinstalar o app ou sair |
| Aparelhos com acesso (nome e quando entrou) | No computador de casa | Até você remover o aparelho |
| Sua conta: nome, e-mail e foto do Google | Firebase (Google), na nuvem | Até você apagar a conta |
| A casa: quem é dono, membro ou convidado (nome e e-mail de cada um), convites por e-mail | Firestore (Google), São Paulo | Até o dono apagar a casa ou tirar a pessoa |
| O endereço e o login da câmera (para a família abrir a câmera sem o computador) | Firestore, só quem é da casa lê | Até o dono trocar ou apagar |

Nada disso vai para backup na nuvem do Google nem para outro celular (o backup do app está desligado).

## Dados sensíveis: o rosto

O rosto é dado biométrico, dado pessoal sensível (art. 5º, II, e art. 11). Por isso:
- só é cadastrado com o **consentimento** da pessoa, marcado no app na hora do cadastro (art. 11, I);
- serve só para dizer quem chegou nas atividades e, se você quiser, para a Proteção avisar só de desconhecidos;
- a pessoa pode pedir para sair a qualquer momento: em Ajustes → Pessoas conhecidas, apagar a pessoa apaga o código
  do rosto e a foto;
- rostos de quem não foi cadastrado não viram cadastro sozinhos: só se alguém confirmar no "Quem é?", de novo com o
  consentimento da pessoa.

## Câmeras que filmam outras pessoas

Se a câmera filma visitas, quem trabalha na casa ou áreas comuns, avise essas pessoas (uma plaquinha ou um aviso
basta) e não aponte a câmera para a casa de vizinhos. Quem trabalha na casa deve saber da câmera e do que ela grava.

## Permissões do celular

- **Internet e rede**: para falar com a câmera e com o computador de casa.
- **Notificações**: para os avisos da Proteção, se você ligar.
- **Microfone**: só enquanto você segura o botão de falar pela câmera.

## Google Drive (opcional)

Se você conectar o Google Drive (Ajustes → Armazenamento), o AI Safe Room envia as fotos das atividades e os clipes
da sua câmera para uma pasta "AI Safe Room" no **seu** Drive, e apaga de lá o que passar de 30 dias.

- O acesso pedido é só o escopo `drive.file`: o app vê e mexe **apenas nos arquivos que ele mesmo criou**. Ele não
  lê, não lista e não apaga nenhum outro arquivo do seu Drive.
- O login é feito por você, na sua conta Google; a autorização fica guardada só no computador de casa (o camera-ai),
  nunca em servidor nosso.
- Os dados do Google não são vendidos, não são usados para publicidade, nem para treinar IA, nem passados a
  ninguém. O uso segue a [Política de dados do usuário dos serviços de API do Google](https://developers.google.com/terms/api-services-user-data-policy),
  incluindo os requisitos de Uso Limitado.
- Para desconectar: Ajustes → Armazenamento → Desconectar, ou em myaccount.google.com → Segurança → Apps de
  terceiros. O que já foi enviado continua no seu Drive até você apagar.

## Seus direitos (art. 18)

Você pode ver, corrigir e apagar os dados a qualquer momento, pelo próprio app (atividades com "Foi engano", pessoas
conhecidas, aparelhos com acesso) e, no computador, apagando a pasta `server/atividades` e `server/perfis`. Quando
houver contas no servidor, o app terá "Apagar minha conta e meus dados".

## A conta (Entrar com o Google)

Para usar o app você entra com a sua conta Google. O Google nos passa o seu nome, e-mail e foto; a senha nunca passa
por nós. Com isso o app sabe quem é dono da casa e quem é da família, e você não perde nada ao trocar de celular.

Esses dados ficam no Firebase, do Google (Firebase Authentication e Cloud Firestore, no Brasil). Regras de segurança
garantem que cada pessoa só veja as casas de que faz parte, que só os donos mudem a casa e que um convidado só entre
com o papel do convite. Não vendemos, não usamos para publicidade e não passamos esses dados a ninguém.

Para apagar: o dono tira a pessoa da casa em Ajustes → Família; para apagar a conta inteira, escreva para
nevext@outlook.com (o botão "Apagar minha conta" no app está a caminho).

## O que muda com mais recursos na nuvem

Se o app passar a mostrar a câmera de fora de casa pela nuvem, a avisar sem o computador ou a usar IA na nuvem, esta
política será atualizada antes, dizendo o que vai para o servidor, onde fica, por quanto tempo e com quem é
compartilhado (por exemplo a nuvem do fabricante da câmera, para chegar até ela).

## Contato

David Neves (Nevext) · nevext@outlook.com — também o encarregado de dados (art. 41).
