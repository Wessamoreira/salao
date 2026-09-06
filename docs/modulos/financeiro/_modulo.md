# Módulo `financeiro`

- **Sigla:** FIN · **Fase de introdução:** 2 · **Status:** em construção

## O que ele responde

*Quanto entrou, de quem, por qual meio — e quanto disso é de cada um.*

## Fronteira

Expõe `PagamentoApi`, `PagamentoResumo`, `DadosDoPagamento`, `FormaDePagamento`,
`StatusDeConciliacao` e `ErrosDoFinanceiro`.

## O que ele **não** sabe

**O que é uma comanda.** `comandaId` é um id de **origem**, opaco. O financeiro guarda pagamentos
atrelados a ele e sabe somá-los; não sabe se aquela comanda existe, se está aberta, nem quanto deve.

Isso não é omissão — é a direção da dependência, e ela precisa ser esta:

> Fechar comanda (RT-ATD-006) é transacional em estoque, pagamento e comissão, e quem orquestra é o
> `atendimento`. Ele **tem** de poder perguntar "quanto já foi pago?". Se o financeiro também
> dependesse dele, o Spring Modulith reprovaria o build por ciclo — e a descoberta viria com o
> fechamento meio escrito.

**Quem pode cobrar.** Alçada é do `iam`, e a checagem fica em quem tem a pergunta de negócio: o
`atendimento` exige `comanda:close`. Repetir aqui espalharia a regra por dois módulos.

## Depende de

- `shared` — `Money`, `Relogio`, `TenantContext`, `UsuarioAtual`

Nenhum módulo de negócio, e é de propósito. Um módulo que registra dinheiro e depende de meia dúzia
de outros é um módulo que vai virar o centro de um ciclo.

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-FIN-001](RT-FIN-001-registrar-pagamento.md) — registrar pagamento, 1:N com split | implementada |

## Rotas

Nenhuma própria. O pagamento é registrado pela rota da comanda
(`POST /api/v1/comandas/{id}/pagamentos`), porque é lá que a regra de "quanto falta" existe.

## Regras

Ver [regras.md](regras.md) — RN-FIN-001 a RN-FIN-004.
