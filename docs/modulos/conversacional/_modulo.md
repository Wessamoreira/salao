# Módulo `conversacional`

- **Sigla:** CNV · **Fase de introdução:** 4 · **Status:** em construção

## O que ele responde

*O que a cliente pediu pelo WhatsApp, e o que o sistema fez com isso.*

## Fronteira

Expõe hoje o evento `MensagemDeClienteRecebida` e o record `CanalDoWhatsApp`. Nada mais: o
conteúdo da mensagem não sai do módulo, e quem quiser processá-la reage ao evento e pede o
conteúdo aqui dentro.

## A porta que ele abre, e por que ela é diferente de todas as outras

`POST /webhooks/whatsapp` é a **única rota pública de escrita do sistema**. Toda outra requisição
chega com um JWT que diz de qual salão ela é; esta chega da Meta, sem token, sem cookie e sem
tenant — o tenant é justamente o que ela precisa descobrir.

Três consequências que atravessam o módulo inteiro:

1. **Quem autentica é o HMAC**, não um principal (RN-CNV-001). Não existe `Authentication` no
   contexto, então `@PreAuthorize` recusaria 100% das chamadas. Isso abriu uma categoria nova no
   teste de arquitetura — `AUTENTICADOS_PELO_PROVEDOR` — com a condição estreita de que só o
   controller do webhook alcance aqueles casos de uso.
2. **O roteamento é a única coisa que o webhook tem.** Um `phone_number_id` mal cadastrado põe a
   conversa de uma cliente na caixa de outro salão, e nada no sistema desconfia.
3. **Mensagem de cliente é entrada não confiável.** A defesa contra prompt injection é a permissão
   do usuário efetivo (RT-CNV-002/004), nunca o texto do system prompt. Este módulo só *guarda* o
   que chegou; ele não interpreta nada.

## O que ele **não** faz (ainda)

- **Não responde.** Mandar mensagem é do `notificacao`, e por ora `RT-CNV-001` só recebe.
- **Não sabe quem é a pessoa.** O telefone entra como texto; ligá-lo a um `cliente` é RT-CNV-002.
- **Não baixa mídia no webhook.** Áudio entra como *id*; o download e a transcrição são
  RT-CNV-003, numa fila fora de transação. Imagem e documento continuam só como *id*.
- **Não entrega de verdade, ainda.** Desde RT-CNV-006 respostas, pedidos e desfechos entram na saída
  para a equipe, na ordem e junto com o fato — mas não há provedor de envio, e a fila espera.

## O que ele **não** guarda

**O payload cru da Meta.** Recusado de propósito: é o mesmo dado pessoal duas vezes, num formato
de terceiro que muda sem aviso — e a anonimização de cliente teria de encontrar nome e telefone
dentro de um `jsonb` cuja forma não controlamos. O que se entende vira coluna; o que não se
entende vira `NAO_SUPORTADO`, e o log registra a **estrutura**, nunca os valores.

**O segredo do app e o token de verificação.** São do *app da Meta*, não do salão: um app serve
todos os números e a assinatura é a mesma para todos. Guardá-los por estabelecimento sugeriria que
cada salão tem o seu, e o primeiro a trocar o valor quebraria o webhook de todos. Vivem em
variável de ambiente.

**O nome de perfil do WhatsApp.** Vem no payload (`contacts[].profile.name`) e não é gravado:
duplicaria PII para resolver uma pergunta — quem é esta pessoa — que é de RT-CNV-002 e se responde
pelo telefone.

## Depende de

- `iam` — `AuditoriaApi` (cadastro do canal) e `Permissao.CONFIG_GERENCIAR`
- `arquivos` — `IngestaoDeArquivoApi` (o áudio guardado e relido, RT-CNV-003)
- `iam` — `UsuarioEfetivoApi` e `EstabelecimentoApi` (em nome de quem, e o fuso do teto, RT-CNV-004);
  `NomesDeUsuariosApi` (o nome de hoje de quem pediu, no painel, RT-CNV-007)
- `shared.agente` — `Ferramenta`, que os outros módulos implementam (RN-CNV-013)
- `shared` — `ConexaoDeManutencao` (o roteamento roda sem tenant), `Relogio`, `ErroDeDominio`

Não depende de `cliente`, `agenda` nem `notificacao`. Vai depender, e a ordem importa: a
dependência entra quando a rotina que precisa dela chegar, não antes.

## O que o agente grava — e quem grava

Nada pelo modelo. RT-CNV-005 selou o contrato de ferramenta em **leitura** e **escrita**, e a escrita
não tem `executar`: o modelo alcança só a simulação, que vira uma intenção pendente com resumo do
servidor e um código. Grava a mensagem da própria pessoa — "SIM" e o código —, reconhecida antes e
fora do modelo, com as trancas resolvidas de novo e a intenção decidida no mesmo commit da gravação
(RN-CNV-014 a 017).

A primeira escrita é `agendar`, da `agenda`. Remarcar e cancelar entram pelo mesmo contrato.

## Falar com a equipe — RT-CNV-006

Este módulo é o dono do número do salão, e passou a ser o dono do envio também: `mensagem_para_equipe`
e o port `EnviadorDoWhatsApp`. É por aqui que saem a resposta do agente, o pedido "SIM + código", o
pedido de aceite com botões à profissional e o desfecho a quem pediu (RN-CNV-020). O adaptador não mora
na `notificacao`, como ADR-0006 previa: o número está aqui, e o contrário seria dependência circular.

Horário fora da jornada não é recusado: a profissional aceita ou recusa por botão, pelo telefone do
cadastro, sem conta de usuário (RN-CNV-018, 019), e o aceite vence em duas horas (RN-CNV-021).

## A trilha — RT-CNV-007

Toda conversa encerrada vira uma linha imutável em `acao_ia`, no mesmo commit do encerramento: o que foi
pedido, as ferramentas com o desfecho de cada tranca, as intenções, se gravou, se foi bloqueada, quanto
custou (RN-CNV-022 a 024). O dono e a gerência leem em `/agente`, com `agente:review` (RN-CNV-025). O
conteúdo sai aos 90 dias e a linha, aos 12 meses (RN-CNV-026).

RT-CNV-002 entregou, **no `iam`**, `UsuarioEfetivoApi` — é por ela que a orquestração (RT-CNV-004)
vai agir em nome de quem escreveu. O módulo ainda não a usa: quem a usa é o primeiro caso de uso
que age.

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-CNV-001](RT-CNV-001-webhook-whatsapp.md) — webhook: assinatura + idempotência | implementada |
| [RT-CNV-002](../iam/RT-CNV-002-usuario-efetivo.md) — usuário efetivo pelo telefone (mora no `iam`) | implementada |
| [RT-CNV-003](RT-CNV-003-audio-transcricao.md) — áudio: download, guarda e transcrição | implementada, sem provedor de STT |
| [RT-CNV-004](RT-CNV-004-orquestracao.md) — orquestração: ferramentas por permissão, teto, limite | implementada, sem provedor de LLM |
| [RT-CNV-005](RT-CNV-005-agendar-pelo-agente.md) — marcar pelo agente: consultar, simular, confirmar pelo código | implementada |
| [RT-CNV-006](RT-CNV-006-aceite-e-saida-para-equipe.md) — aceite da profissional por botão, validade, saída para a equipe | implementada, sem provedor de envio |
| [RT-CNV-007](RT-CNV-007-trilha-do-agente.md) — trilha do agente (`acao_ia`) e painel de revisão | implementada |

## Rotas

| Rota | O que faz | Permissão |
|---|---|---|
| `GET /webhooks/whatsapp` | handshake de cadastro da URL na Meta | **pública** — token de verificação |
| `POST /webhooks/whatsapp` | recebe o lote de mensagens | **pública** — HMAC `X-Hub-Signature-256` |
| `GET /api/v1/canais/whatsapp` | qual número está ligado | `config:manage` |
| `PUT /api/v1/canais/whatsapp` | liga, troca ou desliga o número | `config:manage` |
| `GET /api/v1/agente/acoes` | a trilha, com recorte, pessoa, período e cursor | `agente:review` |
| `GET /api/v1/agente/acoes/resumo` | os totais do período | `agente:review` |
| `GET /api/v1/agente/acoes/pessoas` | quem conversou no período | `agente:review` |

## Regras

Ver [regras.md](regras.md) — RN-CNV-001 a RN-CNV-026.
