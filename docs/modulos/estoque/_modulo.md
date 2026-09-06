# Módulo `estoque`

- **Sigla:** EST · **Fase de introdução:** 3 · **Status:** em construção

## O que ele responde

*O que o salão tem, quanto custou e quanto ainda há.*

## Fronteira

Expõe `ProdutoResumo`, `FinalidadeDoProduto`, `UnidadeDeMedida` e `ErrosDoEstoque`.

Ainda **não** expõe `ProdutoApi`: nenhum módulo consome produto, e o contrato nasce com RT-ATD-003,
que é quem vai precisar dele. Interface sem chamador é documentação que o compilador não confere.

## O que ele **não** sabe

**Quanto foi vendido.** O produto tem preço de venda; quem vendeu é a comanda, e o valor daquela
venda é snapshot dela (RN-ATD-010). Este módulo não guarda faturamento.

**Quem pode precificar.** Alçada é do `iam`: aqui só se declara a permissão que a rota exige.

## Depende de

- `iam` — `Permissao`, `AuditoriaApi`, `UsuarioAtual`

Uma só, e é o que se espera de um módulo de cadastro. Quando lote e movimento chegarem, o número
não deve mudar: quem consulta estoque é que vai depender daqui.

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-EST-001](RT-EST-001-cadastro-de-produto.md) — cadastro de produto | implementada |

## Rotas

| Rota | O que faz | Permissão |
|---|---|---|
| `POST /api/v1/produtos` | cadastra | `estoque:write` (+ `produto:price:write` se vier preço) |
| `PUT /api/v1/produtos/{id}?versao=N` | corrige o cadastro | `estoque:write` |
| `PUT /api/v1/produtos/{id}/preco` | define ou remove o preço | `produto:price:write` |
| `PATCH /api/v1/produtos/{id}/ativacao` | tira de circulação sem apagar | `estoque:write` |
| `GET /api/v1/produtos` | lista; `somenteVendaveis` é a do balcão | `estoque:read` |

## Regras

Ver [regras.md](regras.md) — RN-EST-001 a RN-EST-005.
