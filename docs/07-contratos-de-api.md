# Contratos de API

Regras válidas para **todo** endpoint. Não repita em cada rotina — referencie este arquivo.

## Base

`/api/v1`. Versão só muda em quebra de contrato. Data em ISO-8601 com offset. `application/json`.

**A exceção é `/webhooks/*`**, e ela é deliberada (RT-CNV-001). Webhook não é API do produto: não
tem versão negociada com front nenhum, nenhum navegador o chama — então não recebe CORS — e o
contrato dele é do provedor, não nosso. Pôr o webhook do WhatsApp em `/api/v1` o colocaria sob o
limite de taxa por IP, que conta por origem: a Meta é *uma* origem para todos os salões, e o limite
pensado para conter ataque passaria a descartar mensagem de cliente. Nada mais neste arquivo vale
para `/webhooks/*`: quem manda ali é quem chama.

## Erro — RFC 9457 Problem Details, com código estável

```json
{
  "type": "https://api.salao.app/erros/conflito-horario",
  "title": "Horário indisponível",
  "status": 409,
  "detail": "A profissional Ana já tem atendimento das 10:00 às 11:00.",
  "instance": "/api/v1/agendamentos",
  "codigo": "ER-AGD-CONFLITO_HORARIO",
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "campos": [
    { "campo": "inicio", "codigo": "ER-AGD-CONFLITO_HORARIO", "mensagem": "..." }
  ]
}
```

`codigo` é o contrato com o front. **O front nunca faz `if` em `detail`** — ele mapeia `codigo`
para a mensagem que quer exibir. Isso é o que permite trocar o texto sem tocar no front, e
traduzir depois sem reescrever nada.

Todo código novo entra no catálogo `docs/modulos/<mod>/regras.md`, seção "Erros".

| HTTP | Quando |
|---|---|
| 400 | Payload malformado |
| 401 | Sem autenticação ou token expirado |
| 403 | Autenticado, sem permissão para a **ação** |
| 404 | Não existe **ou** existe fora do escopo do usuário (não confirme existência) |
| 409 | Conflito de estado: horário ocupado, comanda já fechada, versão desatualizada |
| 422 | Semanticamente inválido: regra de negócio violada |
| 429 | Rate limit |

## Paginação — keyset, nunca offset

```
GET /api/v1/agendamentos?limite=50&cursor=eyJpbmljaW8iOiIyMDI2...
```

```json
{ "itens": [...], "proximoCursor": "eyJ...", "temMais": true }
```

O cursor é opaco (base64 do último valor ordenado). Sem `totalDeItens` em listagem grande —
`count(*)` custa mais que a própria página. Onde o total for realmente necessário, é endpoint
separado e cacheado.

## Idempotência

```
POST /api/v1/agendamentos
Idempotency-Key: 8f14e45f-ea0a-4b8f-9d1e-1a2b3c4d5e6f
```

Obrigatório em toda escrita vinda de webhook, bot ou retry. Mesma chave + mesmo payload devolve
a resposta original com `Idempotent-Replay: true`. Mesma chave + payload diferente → 422
`ER-INF-IDEMPOTENCIA_CONFLITO`. TTL de 7 dias.

## Concorrência

Todo recurso REST mutável expõe `versao`, e `PUT`/`PATCH` exige a versão de volta; divergência →
409 `ER-INF-VERSAO_DESATUALIZADA`. Isso resolve duas recepcionistas na mesma comanda, que é o
caso comum e não o exótico.

**A versão vai no corpo, não em `If-Match`.** Este documento pedia o cabeçalho, e a implementação
seguiu outro caminho a partir de RT-CLI-007 — está sendo corrigido aqui, e não o contrário, porque
a razão é boa: o cabeçalho obriga o cliente a costurar dois lugares (corpo e header) para uma
informação que já é do formulário, e o front acaba montando o `If-Match` a partir do próprio corpo.
Vale para `cliente` (RT-CLI-007) e `recurso` (RT-CAT-003), e é o padrão para o que vier.

*(Aqui "recurso" é o do REST. O `Recurso` do salão — lavatório, cadeira, sala — é entidade de
`catalogo`, RT-CAT-003.)*

## Capabilities — o contrato que elimina regra de negócio do front

```
GET /api/v1/me/capabilities
```

```json
{
  "usuarioId": "...",
  "estabelecimento": { "id": "...", "nome": "...", "timezone": "America/Sao_Paulo", "moeda": "BRL" },
  "perfil": "PROFISSIONAL",
  "permissoes": ["agenda:read:own", "agenda:write:own", "financeiro:read:own"],
  "menus": [{ "id": "agenda", "label": "Agenda", "rota": "/agenda", "icone": "calendario" }],
  "flags": { "podeVerValorDeOutros": false, "exigeMfa": true },
  "limites": { "descontoMaximoPercentual": 10 }
}
```

O front **renderiza a partir disso**. O backend **valida de novo** em toda chamada. Esconder
botão é UX; não é segurança.

`limites` existe para o front poder avisar antes ("desconto acima de 10% precisa do gerente") sem
conhecer a regra — ele só compara com o número que o backend mandou.

## Tempo real — SSE, não WebSocket

```
GET /api/v1/eventos/agenda?data=2026-08-28
Accept: text/event-stream
```

O fluxo é só servidor → cliente. SSE reconecta sozinho, atravessa proxy sem cerimônia e é muito
mais simples de operar. Sirva atrás de **HTTP/2** — em HTTP/1.1 o navegador limita 6 conexões por
origem e o painel come todas.

Todo evento carrega `id` para o cliente retomar com `Last-Event-ID` depois de uma queda.

## Cabeçalhos padrão

| Cabeçalho | Uso |
|---|---|
| `traceparent` | Propagado do front até o SQL. Um clique = um trace |
| `Idempotency-Key` | Escrita idempotente |
| `If-Match` / `ETag` | Concorrência otimista e cache condicional |
| `X-Tempo-Servidor` | Instante do servidor, para o front não confiar no relógio do quiosque |
