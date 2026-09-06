# Módulo `atendimento`

- **Sigla:** ATD · **Fase de introdução:** 2 · **Status:** em construção

## O que ele responde

*O que foi executado, e quanto isso soma.*

## Fronteira

Expõe `ComandaResumo`, `AberturaDeComanda`, `StatusDaComanda` e `OrigemDaComanda`.

A comanda é o documento em que o serviço vira dinheiro. Ela é o **único** lugar onde o valor de um
atendimento é somado — e, a partir de RT-ATD-006, o único que congela esse valor.

## O que ele **não** sabe

**Quanto custa.** Preço é do `catalogo`, e o que a comanda guarda é o *snapshot* do preço no
momento da venda (RN-ATD-010) — não uma consulta que muda de resposta quando a tabela muda.

**Quando o atendimento aconteceu.** É da `agenda`. A comanda registra que aconteceu, não em que
horário estava marcado.

**Quem é a cliente.** `ComandaResumo` carrega o id e nada mais; nome vem da `ClienteApi` para quem
precisa exibir, e o que não é carregado não vaza em log.

## Depende de

- `cliente` — `ClienteApi` (existência e nomes em lote)
- `agenda` — `AgendaApi.porId` (de quem é o horário, e se ele ainda vale)
- `iam` — `Permissao`, `AuditoriaApi`, `UsuarioAtual`

Três, que é o teto que a `agenda` também segue. `catalogo`, `estoque` e `financeiro` entram nas
rotinas seguintes e vão estourá-lo — quando isso acontecer, vale a distinção que RT-NOT-002
produziu: **fonte** (aquilo de que o módulo depende para decidir) conta no teto; **destino** (a
quem ele conta o que decidiu) não.

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-ATD-001](RT-ATD-001-abrir-comanda.md) — abrir comanda, do agendamento ou avulsa | implementada |

## Rotas

| Rota | O que faz |
|---|---|
| `POST /api/v1/comandas` | abre — 201 se criou, **200 se já estava aberta** |
| `GET /api/v1/comandas/abertas` | a barra do balcão (R-UX-07), da mais antiga para a mais nova |

## Regras

Ver [regras.md](regras.md) — RN-ATD-001 a RN-ATD-004.
