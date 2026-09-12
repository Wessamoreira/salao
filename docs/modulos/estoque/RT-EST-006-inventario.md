---
id: RT-EST-006
titulo: "Inventário: contagem, divergência, ajuste em lote"
modulo: estoque
fase: 3
perfil: completo
status: implementado
depende_de: [RT-EST-005]
permissoes: [estoque:read, estoque:write]
eventos: []
regras: [RN-EST-018, RN-EST-019, RN-EST-020]
atualizado_em: 2026-09-12
---

# RT-EST-006 — Inventário

## 1. Objetivo

Fazer o saldo do sistema bater com a prateleira, e deixar registrado o que não batia.

## 2. A decisão que sustenta a rotina: quando congelar o esperado

Um inventário de salão leva horas e **não se para de vender** durante ele. Três opções, e as duas
primeiras estragam o resultado:

| Quando congelar | O que acontece |
|---|---|
| ao **abrir** o inventário | venda durante a contagem vira divergência falsa: esperava 10, venderam 2, contou 7 → ajuste −3, quando a real é −1 |
| ao **aplicar** | o número confirmado não é o número visto; a confirmação deixa de confirmar |
| **por linha**, no instante da contagem | ✅ |

A terceira funciona porque o ajuste do inventário é um **delta** — `contada − esperada` —, não um
"passe a valer o contado". Duas unidades vendidas depois da contagem continuam valendo em cima da
correção: `10 − 2 − 3 = 5`, que é exatamente `7 − 2`.

A janela de erro fica do tamanho de uma prateleira, não do inventário inteiro.

## 3. Fluxo

`abrir` → `contar` (uma vez por lote, recontar substitui) → `consultar` a folha → `aplicar` ou
`cancelar`.

| Operação | Permissão | Efeito no estoque |
|---|---|---|
| abrir / contar | `estoque:write` | nenhum |
| consultar | `estoque:read` | nenhum |
| aplicar | `estoque:write` | um `AJUSTE` por linha divergente |
| cancelar | `estoque:write` | nenhum |

## 4. O que o banco garante

- **Um inventário aberto por salão** — índice único parcial. Dois abertos contariam o mesmo lote
  com esperados diferentes, e aplicar os dois somaria duas correções para uma divergência.
- **Uma linha por lote** — `unique (inventario_id, lote_id)` com `on conflict do update`: recontar
  substitui, não soma.
- **Encerrado tem data** — `check ((status = 'ABERTO') = (encerrado_em is null))`.
- **Contagem não negativa**, e **sem `DELETE`** nas duas tabelas.
- E o `AJUSTE` produzido cai na constraint `movimento_com_motivo` de RT-EST-005 — que é
  exatamente onde ela prova que valeu estar no banco: este é o segundo caminho de escrita, e ele
  não poderia ter gravado ajuste sem explicação.

## 5. Tudo ou nada

Uma transação. Se um lote não aceitar o ajuste — porque vendas depois da contagem consumiram o que
faltava —, o inventário inteiro é recusado, nada é escrito e ele **continua aberto** para ser
recontado. A recusa **nomeia o produto e o lote**: com dez lotes conferidos, "saldo insuficiente"
sozinho não diz o que recontar.

## 6. Lote não contado não é zero

Ele simplesmente não aparece na folha. Um zero implícito faria um inventário parcial — "só o
depósito hoje" — zerar o estoque que ninguém olhou.

## 7. Testes obrigatórios

- [x] `InventarioIT` — 17 cenários: **venda durante a contagem não vira divergência falsa** ·
  linha que bateu não vira movimento · divergência positiva usa o custo do lote · **recontar
  substitui, com o esperado do novo instante** · contar zero é uma contagem · contagem negativa
  recusada · **ajuste que não cabe recusa o inventário inteiro, nomeando o produto** · **um
  aberto por salão, garantido pelo banco** · aplicar libera o próximo · cancelar libera e a
  contagem fica · aplicar duas vezes não ajusta duas vezes · contar em encerrado recusado ·
  aplicado não se cancela · cancelar exige motivo · o ajuste carrega o motivo de RN-EST-014 ·
  sem `estoque:write` não abre nem aplica · isolamento entre salões.

## 8. Um defeito de runtime que só o teste pegou

A leitura dos itens era `query((ResultSet rs) -> itens.add(...))`, e o Java resolveu aquilo como
`ResultSetExtractor` — chamado **uma vez**, com o cursor antes da primeira linha. O erro foi
*"ResultSet não está posicionado corretamente, talvez você precise chamar next"*, em runtime, em
onze dos dezessete cenários.

A causa é sutil e vale guardar: `List.add` devolve `boolean`, então o lambda de corpo-expressão não
é void-compatível e deixa de casar com `RowCallbackHandler`. Um bloco `{ itens.add(...); }`
funcionaria — e é exatamente o tipo de diferença que ninguém vê lendo. Trocado por `RowMapper` +
`list()`, que não tem essa ambiguidade.

## 9. Pendências

- [ ] **Não há rota nem tela.** É a rotina que mais precisa: contagem se faz andando, com o
      celular na mão, e o fluxo natural é ler código de barras (RT-EST-009) e digitar a
      quantidade.
- [ ] **Não há folha de contagem para imprimir.** Quem conta sem celular precisa de papel com os
      lotes e um espaço em branco.
- [ ] **Não há contagem cega.** Hoje nada impede a tela mostrar o esperado, e mostrar o esperado
      enviesa a contagem — quem vê "10" conta até 10 e para.
- [ ] **Não há inventário por recorte.** É o salão inteiro ou nada; "só o depósito" ou "só
      coloração" não existe, embora o modelo suporte (basta contar um subconjunto).
- [ ] **O valor da divergência não vai ao razão.** Quebra de inventário é custo, e o livro-razão
      não sabe.
- [ ] **Não há alçada.** Uma divergência de mil unidades aplica igual a uma de uma.
