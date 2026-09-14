# Runbook — ligar o WhatsApp de um salão

> Escrito antes de acontecer, como o de reuso de refresh. A regra do projeto é escrever o runbook
> na primeira ocorrência; a exceção aqui é que **três dos quatro modos de falha desta integração
> são silenciosos** — a cliente manda mensagem, ninguém responde, e nada fica vermelho. Quem for
> ligar isso na primeira vez precisa saber onde olhar antes de precisar.

**Rotina:** RT-CNV-001 · **Decisão:** [ADR-0006](../adr/0006-whatsapp-cloud-api.md)

## Antes de tudo: o que é do app e o que é do salão

Confundir isso é o erro que custa mais tempo.

| | Onde vive | Escopo |
|---|---|---|
| **app secret** | `WHATSAPP_APP_SECRET` (ambiente) | **do app** — um só, para todos os salões |
| **token de verificação** | `WHATSAPP_TOKEN_VERIFICACAO` (ambiente) | **do app** — um só |
| **`phone_number_id`** | tabela `canal_whatsapp` | **do salão** — um por número |

Um app da Meta serve vários números, e a assinatura do webhook é a mesma para todos. Guardar o
segredo por estabelecimento faria o primeiro salão a trocá-lo quebrar o webhook de todos.

## 1. Ambiente

```
WHATSAPP_APP_SECRET=<App Secret, em Configurações do app > Básico>
WHATSAPP_TOKEN_VERIFICACAO=<qualquer string longa que VOCÊ escolhe>
```

O token de verificação não vem da Meta: você o inventa e digita nos dois lados. Gere algo que não
se adivinha — ele é o que impede alguém de completar o handshake no seu lugar.

**Sem essas duas variáveis a aplicação sobe normalmente** e o webhook recusa tudo com 503, com um
WARN na subida dizendo isso. É de propósito (RN-CNV-001): o resto do sistema não depende do
WhatsApp, e aceitar sem poder validar seria pior do que não receber.

## 2. Cadastrar a URL na Meta

Em *WhatsApp > Configuração > Webhook*:

- **Callback URL:** `https://<host>/webhooks/whatsapp`
- **Verify token:** o mesmo de `WHATSAPP_TOKEN_VERIFICACAO`
- depois de verificar, **assine o campo `messages`** — sem essa assinatura a URL fica verificada e
  nada nunca chega. É o passo mais esquecido.

A Meta faz um `GET` com `hub.challenge` e espera o desafio de volta em **texto puro**. Se
responder 403, confira o token; se responder 404, a URL está errada.

## 3. Configurar o leitor do número no salão

`phone_number_id` está em *WhatsApp > Configuração da API*, ao lado do número. É um número longo,
**não** é o telefone.

```bash
curl -X PUT https://<host>/api/v1/canais/whatsapp \
  -H "Authorization: Bearer <token de quem tem config:manage>" \
  -H 'Content-Type: application/json' \
  -d '{"phoneNumberId":"123456789012345","numeroExibicao":"5511999990000"}'
```

`numeroExibicao` é **E.164 puro**, só dígitos — `5511999990000`, não `+55 11 99999-0000`. A rota
recusa o formatado de propósito: dois formatos do mesmo dado no banco fazem a comparação com
`cliente.telefone` falhar em silêncio.

Para desligar sem perder o cadastro, mande o mesmo PUT com `"ativo": false`. **Não apague a
linha** — o banco proíbe, e o motivo é que sem cadastro as mensagens passam a ser descartadas em
silêncio em vez de deliberadamente.

## 4. Conferir que está recebendo

Mande uma mensagem de um telefone qualquer para o número do salão e olhe a métrica:

```
conversacional_webhook_mensagens_total{desfecho="gravada"}
```

E o log da requisição:

```
Webhook do WhatsApp: 1 gravadas, 0 repetidas, 0 sem dono, 0 recusadas
```

## Os quatro modos de falha

### `desfecho="sem_dono"` subindo — o pior, porque é silencioso

O número está apontado para o app e **não está cadastrado aqui**, ou o canal está inativo. Toda
mensagem se perde, a requisição responde 200, e a primeira notícia é a cliente reclamando que
ninguém respondeu.

```
Mensagem de número sem canal ativo: phoneNumberId=<o número>
```

Compare o `phoneNumberId` do log com o que está em `canal_whatsapp`. Quase sempre é o `wa_id`
cadastrado no lugar do `phone_number_id`, ou um salão apontando para o número de outro.

**Alerta recomendado:** qualquer valor de `sem_dono` acima de zero por 5 minutos.

### 403 em série no log de acesso

```
Webhook com assinatura inválida recusado
```

O `WHATSAPP_APP_SECRET` não é o do app que está entregando. Duas causas comuns: o segredo foi
regenerado no painel da Meta e não atualizado aqui, ou há mais de um app (teste e produção)
apontando para a mesma URL. A Meta reenvia por horas, então isso aparece como uma rajada.

### 503 em série

A variável não chegou ao processo. Confira no WARN da subida — ele aparece uma vez, no start.

### `desfecho="recusada"` subindo

A forma da mensagem não cabe no modelo. O log diz o tipo e o motivo do banco, **nunca o
conteúdo**:

```
Mensagem recusada pelo modelo: tipo=<type da Meta> motivo=<constraint>
```

Se o tipo for novo — a Meta acrescenta tipos sem aviso —, ele deveria estar caindo em
`NAO_SUPORTADO` e não em recusa. Um `recusada` com tipo conhecido é bug nosso.

## O que este runbook NÃO resolve

- **Responder a cliente.** RT-CNV-001 só recebe. Mandar mensagem é `notificacao`, e o agente é
  RT-CNV-002 em diante.
- **Áudio.** Entra como id de mídia; baixar e transcrever é RT-CNV-003.
- **Recibo de entrega.** `statuses` chega e é ignorado — ligá-lo a `mensagem.status` é pendência.

## Áudio (RT-CNV-003)

### Credencial

```
WHATSAPP_TOKEN_ACESSO=<token do usuário de sistema do app, com whatsapp_business_messaging>
WHATSAPP_GRAPH_URL=https://graph.facebook.com/v23.0
```

É **outra** credencial: o app secret autentica o webhook; este token autoriza ler da Graph API. Sem
ele, os áudios chegam, entram na fila e **esperam** — sem gastar tentativa. A subida avisa.

A Meta desliga versões da Graph API em cerca de dois anos. Quando a versão configurada estiver perto
do fim, troque `WHATSAPP_GRAPH_URL`; nada mais muda.

### Transcrição

**Não há provedor de STT configurado** (pergunta 14). Os áudios são baixados, guardados e param em
`BAIXADA`. Quando um provedor entrar, eles seguem sozinhos.

### Olhar a fila

```sql
select status, count(*), max(ultimo_erro)
  from transcricao_de_audio
 group by status;
```

Métrica: `conversacional_audio_total{etapa, desfecho}` — `desfecho` é `ok`, `adiada` ou `falhou`.

### Modos de falha

**`PENDENTE` crescendo com `tentativas = 0`** — o token de acesso não está configurado. Não é falha:
é espera.

**`FALHOU` com "URL de conteúdo fora dos hosts da Meta"** — a Meta passou a servir mídia de um CDN
que não está em `MidiaDoWhatsAppGraph.PoliticaDeUrl.daMeta()`. **Não afrouxe a lista sem olhar o
host**: é a defesa que impede o token de ir para quem não é a Meta. Confirme que o domínio é da Meta
e acrescente-o no código.

**`FALHOU` com "respondeu 404"** — a mídia expirou na Meta antes de ser baixada. Não há o que
recuperar.

**`adiada` em série com "respondeu 401"** — o token venceu ou foi trocado. Depois de consertar, as
linhas voltam sozinhas; as que já chegaram a `FALHOU` precisam ser reenfileiradas:

```sql
-- Só depois de consertar a causa. Volta para a etapa em que parou.
update transcricao_de_audio
   set status = case when arquivo_id is null then 'PENDENTE' else 'BAIXADA' end,
       tentativas = 0, proxima_tentativa_em = now(), reservada_ate = null
 where status = 'FALHOU' and ultimo_erro like '%401%';
```

**`FALHOU` com "áudio recusado"** — os bytes não eram áudio. O arquivo `RECUSADO` fica registrado.

## Agente (RT-CNV-004 / RT-CNV-005)

### Credencial e preço

```
AGENTE_CHAVE_API=<chave do provedor de LLM>
AGENTE_MODELO=claude-opus-5
AGENTE_PRECO_ENTRADA=<dólares por milhão de tokens de entrada, da tabela do provedor>
AGENTE_PRECO_SAIDA=<dólares por milhão de tokens de saída>
AGENTE_TETO_DIARIO_USD=2.00
AGENTE_LIMITE_TELEFONE_HORA=30
AGENTE_INTENCAO_VALIDADE=PT10M
```

**Sem chave OU sem preço, o agente não roda** — as conversas esperam na fila. O preço não tem valor
padrão de propósito: sem preço não há custo, e sem custo não há teto (R-17). Confira a tabela do
provedor ao trocar de modelo.

Antes de ligar em produção: mensagem, nome de quem escreve e resultado de ferramenta vão para o
provedor — é tratamento por terceiro, e pede contrato de processamento de dados (LGPD).

### Olhar

```sql
select status, motivo_recusa, count(*) from execucao_do_agente group by 1, 2;

select dia, chamadas, custo_confirmado_usd, custo_reservado_usd
  from consumo_de_ia where estabelecimento_id = '<salão>' order by dia desc limit 7;

-- RT-CNV-005: o que o agente propôs, e o que foi decidido
select status, motivo, count(*) from intencao_pendente
 where criada_em > now() - interval '7 days' group by 1, 2;

-- pendentes que já venceram sem resposta (continuam PENDENTE; a próxima proposta as marca)
select count(*) from intencao_pendente where status = 'PENDENTE' and expira_em < now();
```

Métrica: `conversacional_agente_total{desfecho}` — `concluida`, `recusada_identidade`,
`recusada_limite`, `recusada_teto`, `adiada`, `falhou`; e, das confirmações, `confirmada`,
`confirmacao_recusada`, `confirmacao_vencida`, `confirmacao_ja_decidida`, `confirmacao_sem_pedido`.

### Modos de falha

**`RECUSADA` com `TELEFONE_DESCONHECIDO` de alguém da equipe** — o telefone não está ligado à conta
(`PUT /api/v1/usuarios/{id}/telefone`), ou chegou sem o nono dígito (RT-CNV-002).

**`RECUSADA` com `PERFIL_EXIGE_SEGUNDO_FATOR`** — dono e gerente não conversam pelo canal. É regra
(RN-IAM-020), não defeito.

**`recusada_teto` subindo** — o salão atingiu o teto do dia. Volta sozinho à meia-noite do fuso do
salão. Subir o teto é decisão de custo, não correção.

**`custo_reservado_usd` alto e parado** — um processo morreu entre reservar e liquidar. A reserva só
é liberada no dia seguinte; é o lado seguro de errar.

**`adiada` com "modelo respondeu 401"** — chave vencida ou trocada.

**Ferramenta com desfecho `SEM_PERMISSAO` em conversa legítima** — a ferramenta declara uma
permissão e o caso de uso exige outra. Corrigir a DECLARAÇÃO da ferramenta; nunca afrouxar o caso de
uso.

**Ferramenta com desfecho `FALHA`** — o log registra `Ferramenta <nome> falhou (<classe da exceção>)`,
sem a mensagem, que pode trazer dado pessoal. Para ver a causa, reproduza no IT. Se a ferramenta grava
no caminho de leitura ou de simulação, o Postgres recusa com `cannot execute ... in a read-only
transaction`: é defeito da ferramenta, e se corrige nela. **Nunca** tirar a transação somente leitura
(RN-CNV-014).

**Intenção `RECUSADA` com `ER-AGD-CONFLITO_HORARIO`** — o horário foi tomado entre o resumo e o "SIM".
É o esperado: a simulação não reserva nada. A pessoa pede de novo.

**Intenção `RECUSADA` com `SEM_PERMISSAO`** — o perfil mudou entre o resumo e o "SIM" (RN-CNV-017).

**Intenção `RECUSADA` com `ER-CNV-ACAO_INDISPONIVEL`** — um deploy tirou a ferramenta entre a proposta
e a confirmação. Nada foi gravado.

**`confirmacao_sem_pedido` subindo** — código digitado errado, ou pedido de confirmação que não chegou
à pessoa. **Hoje nenhum chega:** a entrega pelo WhatsApp ainda não existe (pendência de RT-CNV-004/005),
e sem ela o agente não marca nada em produção.
