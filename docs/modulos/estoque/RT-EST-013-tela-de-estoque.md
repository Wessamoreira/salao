---
id: RT-EST-013
titulo: "Tela de estoque: produto, entrada de lote, saldo e inventário"
modulo: estoque
fase: 7
perfil: completo
status: implementado
depende_de: [RT-EST-001, RT-EST-002, RT-EST-003, RT-EST-005, RT-EST-006, RT-EST-008, RT-EST-009, RT-INF-010]
permissoes: [estoque:read, estoque:write, produto:price:write]
eventos: []
regras: [RN-EST-014, RN-EST-018, RN-EST-019, RN-EST-020, RN-EST-040]
atualizado_em: 2026-09-26
---

# RT-EST-013 — O depósito

## 1. Objetivo

Dar entrada na mercadoria que chegou, saber o que tem e quanto vale, lançar a perda do frasco que
quebrou e fazer a contagem do mês — sem `curl`.

## 2. Contexto de negócio

`/estoque` é uma das duas telas que ainda dizem "em breve". O módulo tem treze endpoints e o front
usa **um**: a lista de produtos vendáveis, para a comanda.

E aqui a falta de tela não escondeu um buraco: escondeu **oito casos de uso sem porta**.

| Caso de uso | Rotina | Rota HTTP |
|---|---|---|
| `RegistrarPerdaUseCase` | RT-EST-005 | nenhuma |
| `RegistrarAjusteUseCase` | RT-EST-005 | nenhuma |
| `DevolverAoFornecedorUseCase` | RT-EST-005 | nenhuma |
| `AbrirInventarioUseCase` | RT-EST-006 | nenhuma |
| `ContarLoteUseCase` | RT-EST-006 | nenhuma |
| `ConsultarInventarioUseCase` | RT-EST-006 | nenhuma |
| `AplicarInventarioUseCase` | RT-EST-006 | nenhuma |
| `CancelarInventarioUseCase` | RT-EST-006 | nenhuma |

Testados, com regra registrada e migration aplicada — e inalcançáveis por HTTP. As duas rotinas
já escreviam isso nas próprias pendências: *"não há rota nem tela"*.

**E o inventário tem um beco sem saída.** O banco garante **um aberto por salão** (índice único
parcial, V46), e a única forma de consultar um inventário é pelo `id` que `abrir` devolveu. Perder
esse `id` — fechar a aba, trocar de máquina, o celular descarregar no meio da contagem — deixa o
salão com um inventário aberto que **ninguém consegue consultar, aplicar nem cancelar**, e sem
poder abrir outro. Para sempre, até alguém mexer no banco. É RN-EST-040.

## 3. Atores e permissões

| Ator | Permissão | O que faz |
|---|---|---|
| Recepção | `estoque:read`, `estoque:write` | dá entrada, lança perda, conta |
| Gerente / dono | as duas, mais `produto:price:write` | tudo, e define preço de venda |
| Profissional | — | não entra |

O menu "Estoque" já aparece para quem tem `estoque:read` (RT-IAM-006). O campo de preço de venda
só aparece para quem tem `produto:price:write` — a mesma permissão que o servidor exige, e a tela
pergunta por ela, nunca por perfil.

## 4. Pré-condições

- [x] Produto cadastrável (RT-EST-001), lote (RT-EST-002), saldo (RT-EST-003)
- [x] Perda, ajuste e devolução (RT-EST-005) · inventário (RT-EST-006) · alertas (RT-EST-008)

## 5. Fluxo principal

1. Abre **Estoque**. Se há o que fazer — produto abaixo do mínimo, lote vencendo — está no alto,
   antes da lista.
2. **O que tem no depósito**: busca, saldo, custo médio e valor parado.
3. Abre um produto: a ficha, os lotes em ordem de vencimento, e o que dá para fazer com ele.
4. **Chegou mercadoria** → dar entrada: quantidade, custo, validade, código do lote.
5. **Quebrou, venceu, sumiu** → perda (o FEFO escolhe os lotes) ou ajuste (num lote nomeado).
6. **Contagem** → `/estoque/contagem`.

## 6. Fluxos alternativos

**A1 — A contagem retomada.** Ao entrar em Estoque, a tela pergunta se há contagem aberta
(RN-EST-040). Se há, ela aparece — quem abriu, quando, quantos lotes já foram contados — e o botão
é "Continuar a contagem", não "Começar".

**A2 — A contagem não bate e não aplica.** O servidor recusa o inventário inteiro, **nomeando o
produto e o lote** (RT-EST-006 §5). A tela mostra a frase dele: é a linha que precisa ser
recontada.

**A3 — Recontar.** Contar de novo substitui a contagem anterior, com o esperado do novo instante
(RN-EST-019). A tela deixa reabrir a linha e trocar o número.

**A4 — Produto sem validade.** Campo em branco. No FEFO, o que não vence sai depois do que vence.

**A5 — Motivo curto.** Perda, ajuste e devolução exigem motivo (RN-EST-014). A tela mostra a
recusa do servidor com as palavras dele.

**A6 — Produto que sai do cardápio.** Desativa, nunca apaga: o histórico de movimento aponta para
ele.

## 7. Regras de negócio aplicadas

| ID | Resumo em uma linha | Garantida em |
|---|---|---|
| RN-EST-014 | Perda, ajuste e devolução exigem motivo escrito | `MovimentoComMotivo` + check `movimento_com_motivo` |
| RN-EST-018 | O esperado congela na hora de contar, não ao abrir | `ContarLoteUseCase` |
| RN-EST-019 | Recontar substitui, não soma | `unique (inventario_id, lote_id)` |
| RN-EST-020 | Aplicar é tudo ou nada, e a recusa nomeia o lote | `AplicarInventarioUseCase` |
| **RN-EST-040** | **A contagem aberta se reencontra sem guardar o id** | `ConsultarInventarioAbertoUseCase` |

## 8. O que este documento decide de desenho

**A contagem é cega.** Durante a contagem, a tela **não mostra o esperado**. Quem vê "10" conta
até dez e para — é o viés que RT-EST-006 já apontava como pendência, e é a razão de existir a
contagem. A divergência aparece depois, na folha de conferência, antes de aplicar.

Isto é decisão de tela, e a tela sozinha não garante: o esperado continua disponível em
`GET /produtos/{id}/lotes`. Fechar de verdade exigiria o servidor recusar-se a informá-lo enquanto
há contagem aberta, e isso está nas pendências.

**A folha de conferência mostra só o que diverge.** Vinte linhas que bateram não são notícia; as
três que não bateram são a decisão. As que bateram ficam num contador — "17 lotes conferidos,
bateram" — e não em dezessete linhas.

**Perda é por produto; ajuste é por lote.** Não é detalhe de API, é o que a pessoa sabe. Quem viu
o xampu vazar no chão sabe o produto e a quantidade, não o lote — e o FEFO escolhe. Quem tem o
frasco vencido na mão, com a etiqueta, sabe o lote. Obrigar a escolher lote na perda faria alguém
escolher o errado para poder continuar.

**O alerta vem antes da lista, e some quando não há alerta.** É a única banda da tela sobre a qual
se age hoje. Uma caixa "0 alertas" ocuparia o alto da tela todo dia para não dizer nada.

**Uma coluna de dinheiro só na linha.** O valor parado por produto, e nada mais. Custo médio e
valor lado a lado, sem rótulo, são dois números que se confundem — e o custo médio é detalhe do
produto, não da varredura: ele aparece por extenso quando o produto abre. O total da seção
("R$ 466,80 parados na prateleira") é a soma da coluna, e é o número que o dono quer.

**O custo médio nulo não vira zero.** Sem estoque não há média a tirar (RT-EST-003), e escrever
"R$ 0,00" diria "tenho unidades que não custaram nada" — que é o caso da amostra do fornecedor, e
é diferente.

## 9. Contrato de API

```http
GET    /api/v1/produtos?somenteAtivos=
POST   /api/v1/produtos
PUT    /api/v1/produtos/{id}?versao=
PATCH  /api/v1/produtos/{id}/ativacao
PUT    /api/v1/produtos/{id}/preco
PUT    /api/v1/produtos/{id}/codigo-barras
PUT    /api/v1/produtos/{id}/estoque-minimo
GET    /api/v1/produtos/por-codigo-barras?codigo=
GET    /api/v1/produtos/alertas[?dias=]
GET    /api/v1/produtos/saldos
GET    /api/v1/produtos/{id}/lotes
POST   /api/v1/produtos/{id}/lotes

POST   /api/v1/produtos/{id}/perdas            ← NOVO (FEFO escolhe os lotes)
POST   /api/v1/lotes/{loteId}/ajustes          ← NOVO
POST   /api/v1/lotes/{loteId}/devolucoes       ← NOVO

GET    /api/v1/inventarios/aberto              ← NOVO (RN-EST-040)
POST   /api/v1/inventarios                     ← NOVO
GET    /api/v1/inventarios/{id}                ← NOVO
PUT    /api/v1/inventarios/{id}/lotes/{loteId} ← NOVO (recontar substitui)
POST   /api/v1/inventarios/{id}/aplicar        ← NOVO
POST   /api/v1/inventarios/{id}/cancelar       ← NOVO
```

**`GET /v1/inventarios/aberto`** devolve `204` quando não há contagem aberta — e não um `404`, que
diria "esta rota não existe", nem um envelope `{ "aberto": null }`, que obrigaria a tela a
desembrulhar em toda leitura.

**Dinheiro sai como número.** `Money` serializa como `{"valor": …}`, e as rotas de saldo, lote e
alerta devolviam o registro de domínio cru — duas formas do mesmo dinheiro na mesma API. Passam a
ter DTO de tela, como `ProdutoResponse` já tinha, pela razão que ele já documentava. Nenhuma delas
tinha consumidor no front.

**Erros**

| Código | Quando |
|---|---|
| `ER-EST-MOTIVO_OBRIGATORIO` | 422 — perda, ajuste ou devolução sem motivo (RN-EST-014) |
| `ER-EST-SALDO_INSUFICIENTE` | 422 — a baixa não cabe; no inventário, nomeia produto e lote |
| `ER-EST-INVENTARIO_ABERTO` | 409 — já existe uma contagem aberta neste salão |
| `ER-EST-INVENTARIO_ENCERRADO` | 422 — contar ou aplicar em contagem já encerrada |

## 10. Dados

Nenhuma migration. A consulta nova — o inventário aberto do salão — usa o mesmo índice único
parcial que já garante que ele é único.

## 11. Testes

| O que prova | Onde |
|---|---|
| A contagem aberta se acha sem o id, e some depois de aplicar (RN-EST-040) | `TelaDeEstoqueIT` |
| Sem contagem aberta a rota devolve 204, não erro | `TelaDeEstoqueIT` |
| Perda pela rota baixa em FEFO e exige motivo | `TelaDeEstoqueIT` |
| Ajuste e devolução escrevem no lote nomeado | `TelaDeEstoqueIT` |
| Contar, conferir e aplicar pela rota | `TelaDeEstoqueIT` |
| Aplicar que não cabe recusa tudo e nomeia o lote | `TelaDeEstoqueIT` |
| Quem não escreve estoque não conta nem aplica | `TelaDeEstoqueIT` |
| Saldo, lote e alerta saem com dinheiro como número | `TelaDeEstoqueIT` |
| A tela lista, cadastra, dá entrada, lança perda e conta | `Estoque.test.tsx` |
| A contagem não mostra o esperado enquanto se conta | `Contagem.test.tsx` |
| A folha mostra só o que diverge, e conta o que bateu | `Contagem.test.tsx` |
| Sem violação estrutural de acessibilidade | `Estoque.test.tsx` |

## 12. O que foi construído

| Peça | Onde |
|---|---|
| A contagem aberta, achada sem o id | `InventariosJdbc.aberto` · `ConsultarInventarioAbertoUseCase` |
| A porta do inventário (5 rotas) | `InventariosController` |
| Ajuste e devolução no lote | `LotesController` |
| Perda pelo produto, com FEFO | `POST /v1/produtos/{id}/perdas` |
| Saldo, lote e alerta com dinheiro em número | `SaldoResponse`, `LoteResponse`, `AlertasResponse` |
| A unidade no alerta de vencimento | `AlertasDeEstoque.LoteVencendo` · `LotesJdbc.vencendoEm` |
| A tela | `frontend/src/telas/estoque/` · rotas `/estoque` e `/estoque/contagem` |
| Prévia sem backend, com as duas rotas | `npm run previa:estoque` |

**Testes.** 14 de integração (`TelaDeEstoqueIT`), 5 de unidade (`RespostasDaTelaDeEstoqueTest`) e
36 de tela (`Estoque.test.tsx`, `Contagem.test.tsx`), incluindo acessibilidade com axe.

**Rodada de mutação: 15 mutações, 15 mortas.**

**Quatro defeitos achados fora da rodada**, todos na conferência visual da prévia — que é por isso
que ela existe:

1. **A ausência de contagem quebrava a consulta inteira.** `204` vira `undefined` no cliente
   HTTP, e o TanStack Query recusa `undefined` como dado: a tela iria para o estado de erro
   justamente no caso normal, que é o de quase todo dia.
2. **A unidade do lote vencendo estava fixa em "un".** Quinhentos mililitros de tinta apareceriam
   como "500 un". A unidade passou a vir do servidor, que é quem a sabe.
3. **O campo da contagem era invisível** — sem borda e sem fundo, porque a regra de campo não o
   alcançava. É o controle mais importante da tela, e agora tem alvo de 44px: conta-se andando,
   com o celular na mão.
4. **Produto desativado sumia junto com o botão que o traz de volta**, porque o servidor lista só
   os ativos por padrão. Virou um filtro na busca.

E uma lição de processo: ao escrever `estoque.css` eu **sobrescrevi** o arquivo que já continha o
estilo do leitor de código de barras (RT-EST-009), e a contagem apareceu sem campo nenhum. Os dois
convivem agora no mesmo arquivo, com um cabeçalho dizendo por quê.

## 13. Pendências

- [ ] **A contagem cega é decisão de tela, não garantia.** O esperado continua disponível em
      `GET /produtos/{id}/lotes`, e quem abrir o console o vê. Fechar de verdade exige o servidor
      recusar-se a informá-lo enquanto há contagem aberta.
- [ ] **Não há folha de contagem para imprimir** (pendência de RT-EST-006): quem conta sem celular
      precisa de papel com os lotes e um espaço em branco.
- [ ] **Não há inventário por recorte.** "Só o depósito" existe como observação escrita, não como
      filtro — o modelo suporta, porque lote não contado não entra na folha.
- [ ] **Ficha técnica não tem tela** (RT-EST-007). Ela é serviço × produto, e o lugar dela é a
      receita do serviço, no catálogo — não aqui.
- [ ] **Não há catálogo de motivos** (pendência de RT-EST-005): texto livre vira "quebrou",
      "quebrado" e "caiu" para o mesmo fato, e o mínimo de três caracteres aceita "asd".
- [ ] **A devolução não registra o fornecedor**, e a perda não tem efeito no razão. As duas são
      pendências de RT-EST-005 que a tela não fecha.
- [ ] **O histórico de movimentos não tem tela**: dá para escrever perda e ajuste, e não dá para
      ler o que já foi escrito. É a próxima coisa que alguém vai pedir.
