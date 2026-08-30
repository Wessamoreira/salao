---
id: RT-IAM-004
titulo: Logout e revogação de sessão
modulo: iam
fase: 0
perfil: completo
status: implementado
depende_de: [RT-IAM-003]
permissoes: []
eventos: []
regras: [RN-IAM-009]
atualizado_em: 2026-08-29
---

# RT-IAM-004 — Logout e revogação de sessão

## 1. Objetivo

Encerrar uma sessão — ou todas — sem esperar os trinta dias do refresh.

## 2. Dois endpoints, e a diferença entre eles importa

| | `/logout` | `/logout-all` |
|---|---|---|
| Autentica com | Cookie | **Access token** |
| Alcance | A família daquele dispositivo | Todas as famílias do usuário |
| Aberto? | Sim | Não |

`/logout` é aberto porque sair não pode depender de um access token que talvez já tenha expirado —
e sem cookie válido ele é inofensivo.

`/logout-all` **exige** access token. É a ação de quem suspeita que alguém tem acesso, e por isso
não pode depender do cookie do dispositivo atual — que pode ser exatamente o que foi perdido. Quem
chama já provou ser o dono da conta.

## 3. Logout nunca falha (RN-IAM-009)

Refresh desconhecido, já revogado ou ausente: todos terminam em 204.

Dois motivos. O primeiro é de produto: não há nada que o usuário possa fazer a respeito, e a tela
diria "não foi possível sair" com o cookie já apagado. Do ponto de vista de quem clicou, sair de
uma sessão que já não existe **é** o resultado desejado.

O segundo é de segurança: um logout que respondesse diferente para token válido e inválido viraria
um **oráculo para testar tokens**.

## 4. O access token sobrevive ao logout — e isso é decisão, não descuido

Revogar a família encerra a capacidade de **renovar**. O access token já emitido continua válido
até expirar: no máximo 15 minutos. Um JWT não é revogável sem consultar estado a cada requisição,
que é justamente o que ele existe para evitar.

**Por que aceitar essa janela hoje:**

- O front guarda o access token em memória, e o logout a limpa. O risco só existe se o token já
  tiver sido exfiltrado — ou seja, o atacante já tinha 15 minutos de qualquer forma.
- Fechar a janela exige uma lista de revogação consultada por requisição, ou um
  `tokens_validos_apos` por usuário. Ambos introduzem estado no caminho de toda chamada.

**O gatilho para mudar de ideia:** quando entrar MFA para `ADMIN` (RT-IAM-005) ou quando houver um
incidente real. Aí a solução desenhada é `tokens_validos_apos` no usuário, cacheado em Caffeine e
invalidado por `LISTEN/NOTIFY` — a infraestrutura de RT-INF-007 já serve, e o custo por requisição
vira um acerto de cache.

Está escrito assim para ser uma decisão revisitável, e não uma lacuna que alguém descobre depois.

## 5. Expurgo

`PurgadorDeRefreshTokens`, diário às 3h20. A tabela cresce a cada login **e a cada renovação** — um
usuário ativo gera dezenas de linhas por mês.

Guarda os vencidos por 30 dias **além** do vencimento, de propósito: é o que permite responder
"quando esta sessão foi encerrada, e de qual IP?" numa investigação. Fecha a pendência declarada
em RT-IAM-003.

## 6. Alerta

`ReusoDeRefreshDetectado` em `ops/prometheus/alertas.yml`, apontando para
`docs/runbook/reuso-de-refresh.md`. Fecha a segunda pendência de RT-IAM-003.

O runbook foi escrito **antes** de o problema acontecer, contrariando a regra do projeto — porque
resposta a incidente de segurança é a exceção: no meio dele ninguém pensa com clareza, e a decisão
de quanto revogar não pode ser improvisada.

## 7. Testes

- [x] `EncerramentoIT` — 7: logout encerra · não derruba outros dispositivos · nunca falha
      (desconhecido, vazio, nulo) · idempotente · logout-all derruba todas · logout-all respeita o
      tenant · purga respeita a retenção
- [x] `AutenticacaoWebIT` — 3 novos: cookie apagado com `Max-Age=0` e refresh invalidado ·
      logout sem cookie devolve 204 · logout-all exige autenticação

## 8. O que a implementação revelou

**`Ordered.HIGHEST_PRECEDENCE + 90` é `Integer.MIN_VALUE + 90`.**

O `TenantFilter` deveria rodar logo depois da cadeia do Spring Security (que fica em `-100`), para
que o `SecurityContext` já estivesse populado quando o resolvedor por JWT fosse consultado. Eu
escrevi `HIGHEST_PRECEDENCE + 90` pretendendo "um pouco depois do início" — mas isso é um número
ordens de grandeza **antes** de `-100`, exatamente o contrário.

O erro ficou invisível enquanto não havia endpoint autenticado: nenhum teste passava pelo caminho.
Apareceu no primeiro deles, como `TenantNaoDefinidoException` e HTTP 500 — um sintoma que não
aponta para ordenação de filtro.

Trocado por um valor absoluto com a razão escrita ao lado. **Aritmética sobre `HIGHEST_PRECEDENCE`
não expressa "depois de X"** — só um valor relativo a `X` faz isso.

## 9. Pendências

- [ ] Listar sessões ativas ("onde estou conectado"), com dispositivo, IP e último uso. Revogar
      sem poder ver o que se revoga é meia funcionalidade — mas exige decisão de UI
- [ ] Revogação imediata do access token: ver seção 4, com a solução desenhada e o gatilho
- [ ] Limite de famílias simultâneas por usuário
