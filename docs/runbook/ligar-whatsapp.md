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
