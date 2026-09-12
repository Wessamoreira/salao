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
- **Não baixa mídia.** Áudio e imagem entram como *id* — baixar dentro do webhook estouraria a
  janela de resposta da Meta. O download é RT-CNV-003.
- **Não chama LLM nenhum.** Orquestração, allowlist de tools e teto de custo são RT-CNV-004.

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
- `shared` — `ConexaoDeManutencao` (o roteamento roda sem tenant), `Relogio`, `ErroDeDominio`

Não depende de `cliente`, `agenda` nem `notificacao`. Vai depender, e a ordem importa: a
dependência entra quando a rotina que precisa dela chegar, não antes.

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-CNV-001](RT-CNV-001-webhook-whatsapp.md) — webhook: assinatura + idempotência | implementada |

## Rotas

| Rota | O que faz | Permissão |
|---|---|---|
| `GET /webhooks/whatsapp` | handshake de cadastro da URL na Meta | **pública** — token de verificação |
| `POST /webhooks/whatsapp` | recebe o lote de mensagens | **pública** — HMAC `X-Hub-Signature-256` |
| `GET /api/v1/canais/whatsapp` | qual número está ligado | `config:manage` |
| `PUT /api/v1/canais/whatsapp` | liga, troca ou desliga o número | `config:manage` |

## Regras

Ver [regras.md](regras.md) — RN-CNV-001 a RN-CNV-005.
