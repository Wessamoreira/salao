---
id: RT-CNV-001
titulo: "Webhook WhatsApp: validação de assinatura + idempotência por messageId"
modulo: conversacional
fase: 4
perfil: completo
status: implementado
depende_de: [RT-INF-005, RT-INF-006, RT-IAM-001]
permissoes: [config:manage]
eventos: [MensagemDeClienteRecebida]
regras: [RN-CNV-001, RN-CNV-002, RN-CNV-003, RN-CNV-004, RN-CNV-005]
atualizado_em: 2026-09-12
---

# RT-CNV-001 — Webhook do WhatsApp

## 1. Objetivo

Receber o que a cliente manda pelo WhatsApp: provar que veio da Meta, descobrir de qual salão é,
guardar uma vez só e avisar quem vai responder.

## 2. A rotina existe num lugar onde nada do sistema funciona

Toda requisição do produto chega com um JWT que diz de qual estabelecimento ela é. O
`TenantFilter` abre o escopo, o `TenantAwareTransactionManager` executa
`SET LOCAL app.tenant_id`, e a RLS filtra a partir daí.

O webhook chega **sem nada disso**:

| O que o resto do sistema tem | O que o webhook tem |
|---|---|
| JWT com `estabelecimentoId` | nada |
| `Authentication` no contexto | nada |
| permissão verificável por `@PreAuthorize` | nada |
| tenant resolvido antes do controller | o tenant é o que ele precisa **descobrir** |

Tudo nesta rotina decorre disso. A autenticação passa a ser um HMAC (RN-CNV-001), a descoberta do
tenant passa a ser uma leitura cross-tenant pela role de plataforma (seção 4), e o teste de
arquitetura precisou de uma categoria nova (seção 8).

## 3. Não há "integração": um webhook é um POST assinado

Não há SDK, não há polling, não há conexão persistente. A Meta faz um `POST` com um JSON e espera
`200` rápido. O que o sistema precisa é:

1. **provar que veio dela** — HMAC sobre os bytes, antes de qualquer parse;
2. **descobrir de quem é** — `metadata.phone_number_id` → estabelecimento;
3. **guardar uma vez** — índice único sobre o `wamid`;
4. **responder 200 e sair** — qualquer trabalho pesado vai para depois, por evento.

O passo 4 é limite de projeto, não preguiça: baixar o áudio, transcrever e chamar um LLM dentro da
requisição estouraria a janela de resposta e faria a Meta reenviar o lote — que chegaria enquanto o
primeiro ainda estivesse processando.

## 4. Descobrir o tenant é operação de plataforma

`canal_whatsapp` mapeia `phone_number_id → estabelecimento_id`. A consulta roda **antes** de
existir tenant, então ela não pode ir pela conexão da aplicação: a política de RLS de toda tabela
compara `estabelecimento_id` com `app.tenant_id`, que está vazio — e devolveria zero linhas.
Falha fechada, corretamente, e inútil aqui.

Vai pela `ConexaoDeManutencao`, com **`select` e mais nada**. É a mesma categoria do despachante de
mensagens (V30) e do purgador de idempotência (V4): operação de plataforma, com policy própria e
concessão estreita.

### Por que o roteamento não é cacheado

Seria tentador — a tabela é minúscula, quase nunca muda, e o pool de manutenção tem duas conexões.
Foi recusado por duas razões:

- o cache do projeto expira por escrita em 30 minutos, e esse prazo **é o teto do estrago** quando
  o ouvinte de invalidação cai (RT-INF-007). Aqui o estrago seria conversa caindo no salão errado
  por meia hora, sobre a única decisão que o webhook toma;
- a pressão sobre o pool é teórica: **nenhuma requisição chega à consulta sem HMAC válido**. Quem
  não tem o segredo é recusado antes de tocar no banco, então o que chega é o tráfego real do
  salão — dezenas de mensagens por dia, não por segundo.

Se o volume mudar isso, o caminho é cache com invalidação explícita na escrita do canal, não um TTL
maior.

## 5. A armadilha: um POST não é de um salão — RN-CNV-003

A Meta agrupa por **app**. Um app que serve dez salões entrega, no mesmo POST, `entry[]` com
`changes[]`, e **cada change traz o seu próprio `phone_number_id`**.

```
POST /webhooks/whatsapp
└── entry[0].changes[0].value.metadata.phone_number_id = número do salão A
└── entry[1].changes[0].value.metadata.phone_number_id = número do salão B
```

Resolver o tenant uma vez, no começo do lote, é o jeito natural de escrever o laço — e põe a
conversa de uma cliente na caixa de outro salão. Sem erro, sem log, sem ninguém desconfiar até
alguém ler a conversa errada.

Guardado por `WebhookDoWhatsAppIT.lote_multi_tenant`, e verificado por mutação: resolvendo o dono
fora do laço, o teste fica vermelho.

## 6. Idempotência por mensagem, e não por requisição — RN-CNV-002

`RT-INF-005` já resolve idempotência de escrita, e **não serve aqui**. A diferença é de forma:

| | `Idempotencia` (RT-INF-005) | o webhook |
|---|---|---|
| chave | header `Idempotency-Key` que o cliente manda | `wamid`, que o provedor gera |
| granularidade | uma chave por operação | várias mensagens por requisição |
| o que o reenvio traz | a mesma requisição | as antigas **e** as novas |

É a última linha que decide. A Meta reenvia o lote inteiro, e um reenvio traz mensagens que
chegaram depois, na carona. Idempotência por requisição descartaria exatamente as novas.

Então: índice único `(estabelecimento_id, provedor_id)` e `on conflict do nothing`. Zero linhas
devolvidas é **resposta**, não erro — e o evento só é publicado no `ifPresent`, porque não basta a
linha não duplicar: o evento não pode sair duas vezes, e é ele que vai disparar o agente.

## 7. Quando responder 200, e quando não

A Meta reenvia o que não recebeu `200`. Isso é útil para falha passageira e **daninho** para falha
permanente — um payload que nunca vai ser aceito seria reenviado para sempre, e cada reenvio traz o
lote inteiro.

| Situação | Resposta | Por quê |
|---|---|---|
| segredo não configurado | **503** | a recusa é nossa, não de quem chamou (RN-CNV-001) |
| corpo acima de 512 KB | **413** | antes de calcular HMAC sobre ele |
| assinatura ausente ou inválida | **403** | 200 tornaria forjado indistinguível de legítimo |
| JSON ilegível, já assinado | **200** | defeito do provedor; reenviar não conserta |
| mensagem que a constraint recusa | **200** | permanente; conta como recusada e segue o lote |
| número sem canal ativo | **200** | nunca vai ter dono; conta em métrica (RN-CNV-004) |
| banco fora do ar | **500** | passageiro — é o caso em que o reenvio ajuda |
| lote recebido | **200** | |

O teto de 512 KB existe porque a rota é pública e o HMAC percorre o corpo inteiro: sem limite, um
POST de 2 GB é CPU e memória de graça para quem nem tem o segredo. Checado pelo `Content-Length`
**e** pela leitura, porque requisição `chunked` não declara tamanho.

## 8. A categoria que faltava no teste de arquitetura

`ArquiteturaTest.caso_de_uso_nao_tem_porta_sem_autorizacao` reprovou o build, e estava certo em
perguntar. As duas listas de exceção que existiam não servem:

- **`TRANCADOS_PELA_IDENTIDADE`** — lá a tranca é verificada *dentro* do caso de uso (o autor é o
  dono da conta). Aqui a tranca é o HMAC, verificado na **borda** — e o projeto desconfia de tranca
  na borda exatamente porque outro chamador pode aparecer e contorná-la.
- **`NAO_SAO_PORTA`** — a condição de lá é que nenhum controller alcance a classe, e um controller
  alcança esta.

Então nasceu `AUTENTICADOS_PELO_PROVEDOR`, com a condição própria e estreita: **só o controller do
webhook pode alcançá-las**, verificado por `o_que_o_provedor_autentica_so_e_alcancado_pelo_webhook`.

Sem essa condição, `GravarMensagemRecebidaUseCase` — que recebe `estabelecimentoId` como parâmetro
e não checa permissão — ficaria a uma tela de distância de injetar mensagem falsa na caixa de
entrada de qualquer salão. No dia em que "reprocessar mensagem" virar tela, o build reprova e
obriga a decidir a permissão.

## 9. Duas classes para uma transação

`GravarMensagemRecebidaUseCase` é separada de `ReceberLoteDoWhatsAppUseCase` e isso **não é
organização**: é a única forma de a transação existir.

O laço roda sem tenant e abre o escopo antes de gravar. Se os dois morassem na mesma classe, a
chamada seria interna, o proxy do Spring não a interceptaria, e `@Transactional` não valeria nada —
a inserção iria em autocommit e o evento cairia fora da transação.

Mesma lição de `FechamentosJdbc.travarEstabelecimento` (RT-FIN-006) e de `EstornoIT`: *anotação de
transação ou de segurança só vale onde o Spring consegue interceptar.*

## 10. O que não é guardado, e por quê

**O payload cru.** Seria conveniente para depurar uma integração que não controlamos, e foi
recusado: é o mesmo dado pessoal duas vezes, num `jsonb` cuja forma a Meta muda quando quiser — e
a anonimização de cliente teria de encontrar nome e telefone lá dentro. O que se entende vira
coluna; o que não se entende vira `NAO_SUPORTADO`, e o log registra a **estrutura** (o `type`
literal), nunca os valores.

**O nome de perfil do WhatsApp.** Vem em `contacts[].profile.name`. Duplicaria PII para responder
"quem é esta pessoa", que é RT-CNV-002 e se responde pelo telefone.

**O texto no evento.** `MensagemDeClienteRecebida` carrega `estabelecimentoId`, `mensagemId` e
`tipo`. O payload do evento vive em `event_publication`, que não tem RLS: os eventos de todos os
salões convivem ali, e telefone ou texto seria dado pessoal fora do perímetro (RN-INF-009). O tipo
vai porque decide *quem* trata — áudio para o STT, texto direto para a orquestração — e não é dado
pessoal.

## 11. O número de exibição é E.164, e isso nasceu de um erro meu

O `numeroExibicao` do canal é validado como **E.164 puro** (`5511999990000`), e não aceita
`+55 11 99999-0000`. Isso nasceu de um erro: a validação recusou a minha própria fixture de teste,
e a saída fácil era afrouxar a validação.

A correção foi a fixture. `cliente.telefone` é E.164 em todo o projeto, a própria Meta manda
`display_phone_number` assim, e o front já tem componente que formata. Aceitar as duas formas
criaria dois formatos do mesmo dado no banco, com a comparação entre eles falhando em silêncio no
dia em que alguém precisar casar este número com o de um cliente.

## 12. Testes obrigatórios

- [x] `AssinaturaDoWebhookTest` — 8 casos: assinatura correta · **um byte trocado no corpo
  invalida** · outro segredo não confere · **sem segredo NADA confere** · cabeçalho ausente, sem
  prefixo ou com hex torto é só "não confere" (nunca exceção, porque 500 numa rota pública é sinal
  para quem sonda) · prefixo em qualquer caixa · hex maiúsculo · token do handshake, incluindo
  **prefixo certo não basta**.
- [x] `CargaDoWhatsAppTest` — 11 casos: texto completo · **lote com dois salões** · áudio como
  bilhete de mídia · recibo de entrega não é mensagem · assunto que não é `messages` · **tipo não
  tratado é guardado, não descartado** · uma mensagem estranha não derruba o lote · texto sem corpo
  e mídia sem id caem para `NAO_SUPORTADO` · timestamp ilegível vira 1970 e não "agora" · sem
  `metadata` é pulado · payloads degenerados não estouram.
- [x] `WebhookDoWhatsAppControllerTest` — 8 casos: **503 sem segredo, sem chegar ao caso de uso** ·
  403 com assinatura inválida e **JSON nunca interpretado** · 403 sem cabeçalho · **413 acima do
  teto** · corpo grande dentro do teto passa · JSON ilegível assinado devolve 200 · handshake em
  texto puro · handshake recusado por token, modo ou desafio.
- [x] `WebhookDoWhatsAppIT` — 21 casos: roteamento pelo número · **lote multi-tenant vai para as
  duas caixas** · número sem dono é contado · canal desligado não recebe · **reenvio não duplica** ·
  **reenvio com mensagem nova entrega só a nova** · mesmo `wamid` em dois salões · **evento sai uma
  vez** · **evento carrega só identificadores** · áudio · tipo não suportado · recibo de status ·
  número de outro salão recusado · registrar é idempotente · `phoneNumberId` com espaço ·
  **número formatado recusado** · salão sem canal · sem `config:manage` · isolamento entre salões ·
  **grant de coluna** (só `processada_em`) · **canal não se apaga**.
- [x] `WebhookDoWhatsAppWebIT` — 4 casos no fio: **POST assinado passa pela cadeia de segurança**
  (401 aqui significa que o `permitAll` não existe) · 403 sem assinatura válida · handshake em
  `text/plain` e recusado com token errado · **o cadastro do canal continua exigindo 401**.
- [x] `ArquiteturaTest.o_que_o_provedor_autentica_so_e_alcancado_pelo_webhook` — verificado por
  mutação: apontando a exceção para uma classe inexistente, o build reprova.

### Mutações verificadas

| Linha removida | Teste que ficou vermelho |
|---|---|
| `on conflict … do nothing` | `reenvio_nao_duplica`, `reenvio_com_mensagem_nova` |
| tenant resolvido dentro do laço | `lote_multi_tenant` |

## 13. Pendências

- [ ] **O evento não está no outbox ainda.** O Spring Modulith grava uma publicação **por
      listener**, e `MensagemDeClienteRecebida` não tem nenhum — `event_publication` fica vazia. A
      garantia de entrega passa a valer em RT-CNV-002, quando o consumidor existir. Hoje o evento
      é publicação em processo, e uma queda entre o commit e o consumo não perde nada porque não há
      consumo. **É a primeira coisa a verificar na próxima rotina.**
- [ ] **Recibo de entrega é ignorado.** `value.statuses` traz `sent`/`delivered`/`read` das
      mensagens que o salão mandou, e ligá-los a `mensagem.status` (RT-NOT-001) fecharia o ciclo
      "a cliente recebeu o lembrete?". Não é desta rotina, mas o dado está chegando e sendo
      jogado fora.
- [ ] **Sem limite de taxa próprio.** A rota está fora de `/api`, então o limitador de RT-INF-011
      não a alcança — de propósito: ele conta por IP, e a Meta é *uma* origem para todos os salões.
      Um limite por `phone_number_id`, depois da assinatura conferir, seria o controle certo.
- [ ] **Não há tela de cadastro do canal.** A rota existe (`PUT /api/v1/canais/whatsapp`) e o lugar
      natural dela é a tela de configuração.
- [ ] **Nada impede cadastrar o número errado.** A unicidade impede dois salões com o mesmo número;
      não impede um salão cadastrar um número que não é dele — e o efeito seria receber a conversa
      alheia. Confirmar a posse exigiria consultar a Cloud API com o token do salão, que esta
      rotina não tem.
- [ ] **Retenção da caixa de entrada não existe.** `mensagem_recebida` cresce para sempre, e é
      dado pessoal: a LGPD pede prazo. É trabalho de plataforma, como o purgador de idempotência.
- [ ] **O `wamid` não é validado quanto ao tamanho.** Coluna `text` sem limite; um provedor
      hostil (ou um bug deles) poderia mandar uma chave enorme. O teto do corpo limita o estrago.
