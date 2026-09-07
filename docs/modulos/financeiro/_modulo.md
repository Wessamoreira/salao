# Módulo `financeiro`

- **Sigla:** FIN · **Fase de introdução:** 2 · **Status:** em construção

## O que ele responde

*Quanto entrou, de quem, por qual meio — e quanto disso é de cada um.*

## Fronteira

Expõe `PagamentoApi`, `ComissaoApi`, `PagamentoResumo`, `DadosDoPagamento`, `FormaDePagamento`,
`StatusDeConciliacao`, `EscopoDaRegra`, `RegraDeComissao`, `ComissaoResolvida`,
`OrigemDaComissao` e `ErrosDoFinanceiro`.

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

- `equipe` — `ProfissionalApi` (o percentual da ficha é um nível da cascata)
- `catalogo` — `ServicoApi` (conferir que o serviço da regra existe neste salão)
- `iam` — `Permissao`, `AuditoriaApi`, `UsuarioAtual`

**RT-FIN-001 dizia "nenhum módulo de negócio, e é de propósito".** RT-FIN-002 desfez isso, e a frase
é que estava larga demais: o que precisa valer é que **ninguém depende do financeiro para decidir**
— `atendimento` depende dele para perguntar quanto pagar, e é uma seta só. Depender de `equipe` e
`catalogo`, que são folhas, não aproxima o módulo de virar centro de ciclo.

O que continua valendo, e é o que importa: o financeiro **não conhece a comanda**. `comandaId` é id
de origem opaco (RN-FIN-002), e é isso que impede o ciclo com `atendimento`.

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-FIN-001](RT-FIN-001-registrar-pagamento.md) — registrar pagamento, 1:N com split | implementada |
| [RT-FIN-002](RT-FIN-002-cascata-de-comissao.md) — cascata de comissão + snapshot | implementada |

## Rotas

| Rota | O que faz | Permissão |
|---|---|---|
| `PUT /api/v1/regras-de-comissao` | define um nível da cascata (substitui se já existir) | `config:manage` |
| `GET /api/v1/regras-de-comissao` | as regras, na ordem da cascata | `financeiro:read:all` |
| `DELETE /api/v1/regras-de-comissao/{id}` | tira um nível | `config:manage` |
| `GET /api/v1/regras-de-comissao/simulacao` | quanto esta pessoa ganharia neste serviço, e por quê | `financeiro:read:all` |

O **pagamento** não tem rota própria: é registrado pela rota da comanda
(`POST /api/v1/comandas/{id}/pagamentos`), porque é lá que a regra de "quanto falta" existe.

## Regras

Ver [regras.md](regras.md) — RN-FIN-001 a RN-FIN-007.
