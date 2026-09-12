# Módulo `estoque`

- **Sigla:** EST · **Fase de introdução:** 3 · **Status:** em construção

## O que ele responde

*O que o salão tem, quanto custou e quanto ainda há.*

## Fronteira

Expõe `ProdutoApi`, `EstoqueApi`, `ProdutoResumo`, `LoteResumo`, `SaldoDoProduto`,
`BaixaDeEstoque`, `FinalidadeDoProduto`, `UnidadeDeMedida`, `TipoDeMovimento` e
`ErrosDoEstoque`.

`EstoqueApi.baixar` **não tem rota HTTP**, de propósito: baixa é consequência de fechar
comanda ou de executar serviço, não ação de usuário. Uma rota direta seria um caminho
para o saldo diminuir sem que nada explicasse por quê — e é esse caminho que RT-EST-005
fecha, exigindo motivo.

`ProdutoApi` nasceu em RT-ATD-003, quando o primeiro módulo passou a consumir produto — antes
disso teria sido interface sem chamador, documentação que o compilador não confere.

## O que ele **não** sabe

**Quanto foi vendido.** O produto tem preço de venda; quem vendeu é a comanda, e o valor daquela
venda é snapshot dela (RN-ATD-010). Este módulo não guarda faturamento — guarda **custo**, que é a
outra metade da margem.

**Quem pode precificar.** Alçada é do `iam`: aqui só se declara a permissão que a rota exige.

## Depende de

- `catalogo` — `ServicoApi` (a ficha técnica confere que o serviço existe neste salão)

- `iam` — `Permissao`, `AuditoriaApi`, `UsuarioAtual`

Uma só, e é o que se espera de um módulo de cadastro. Quando lote e movimento chegarem, o número
não deve mudar: quem consulta estoque é que vai depender daqui.

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-EST-001](RT-EST-001-cadastro-de-produto.md) — cadastro de produto | implementada |
| [RT-EST-002](RT-EST-002-entrada-de-lote.md) — entrada de lote com custo e validade | implementada |
| [RT-EST-003](RT-EST-003-saldo-e-custo-medio.md) — saldo e custo médio ponderado | implementada |
| [RT-EST-004](RT-EST-004-baixa-fefo.md) — baixa FEFO, venda e consumo interno | implementada |
| [RT-EST-005](RT-EST-005-perda-ajuste-devolucao.md) — perda, ajuste e devolução com motivo | implementada |
| [RT-EST-006](RT-EST-006-inventario.md) — inventário: contagem, divergência, ajuste | implementada |
| [RT-EST-007](RT-EST-007-ficha-tecnica.md) — ficha técnica e consumo interno | implementada |
| [RT-EST-008](RT-EST-008-alertas.md) — alertas de mínimo e de validade | implementada |
| [RT-EST-009](RT-EST-009-codigo-de-barras.md) — leitor de código de barras (HID) | implementada |

## Rotas

| Rota | O que faz | Permissão |
|---|---|---|
| `POST /api/v1/produtos` | cadastra | `estoque:write` (+ `produto:price:write` se vier preço) |
| `PUT /api/v1/produtos/{id}?versao=N` | corrige o cadastro | `estoque:write` |
| `PUT /api/v1/produtos/{id}/preco` | define ou remove o preço | `produto:price:write` |
| `PATCH /api/v1/produtos/{id}/ativacao` | tira de circulação sem apagar | `estoque:write` |
| `GET /api/v1/produtos` | lista; `somenteVendaveis` é a do balcão | `estoque:read` |
| `GET /api/v1/produtos/por-codigo-barras?codigo=` | o produto que o leitor bipou; **404** se não há | `estoque:read` |
| `PUT /api/v1/produtos/{id}/codigo-barras` | define o código; corpo vazio remove | `estoque:write` |
| `PUT /api/v1/produtos/{id}/estoque-minimo` | define o mínimo; nulo desliga o alerta | `estoque:write` |
| `GET /api/v1/produtos/alertas` | o que está acabando e o que vai vencer | `estoque:read` |
| `POST /api/v1/produtos/{id}/lotes` | a caixa chegou: lote + movimento de entrada | `estoque:write` |
| `GET /api/v1/produtos/{id}/lotes` | os lotes, **na ordem do FEFO** | `estoque:read` |
| `GET /api/v1/produtos/saldos` | o inventário; padrão traz também o que zerou | `estoque:read` |
| `GET /api/v1/produtos/{id}/saldo` | quanto há e a que custo médio | `estoque:read` |

## Regras

Ver [regras.md](regras.md) — RN-EST-001 a RN-EST-028.
