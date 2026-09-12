---
id: RT-EST-007
titulo: "Consumo interno por serviço (ficha técnica: tinta, ox)"
modulo: estoque
fase: 3
perfil: completo
status: implementado
depende_de: [RT-EST-004, RT-CAT-001, RT-ATD-006]
permissoes: [estoque:read, estoque:write]
eventos: []
regras: [RN-EST-021, RN-EST-022, RN-EST-023]
atualizado_em: 2026-09-12
---

# RT-EST-007 — Ficha técnica e consumo interno

## 1. Objetivo

Fazer a tinta e a ox saírem do estoque quando a coloração acontece — sem parar o balcão quando a
conta não fecha.

É o que faz `CONSUMO_INTERNO` deixar de ser um tipo de movimento sem ninguém que o escreva.

## 2. Por que a ficha mora no estoque, e não no catálogo

Ela liga serviço a produto, então um dos dois módulos precisa depender do outro. Nenhum dependia.

| Onde | Custo |
|---|---|
| catálogo | o módulo passaria a conhecer unidade de medida, finalidade do produto e a regra de que só se consome o que é consumível — conceitos de estoque migrando para onde não moram |
| **estoque** | uma chamada a `ServicoApi.porId` para conferir que o serviço existe neste salão |

A segunda, e com precedente: é exatamente o que `financeiro` já faz com o mesmo módulo para a regra
de comissão (RN-FIN-007). A conta, o FEFO e a validação de unidade já estão neste lado.

## 3. A ficha é previsão, não medição

Uma coloração em cabelo curto e uma em cabelo longo não gastam a mesma tinta. A ficha diz o
**padrão**, e é ele que sai no fechamento.

A diferença acumulada é o que o inventário (RT-EST-006) existe para pegar — e é por isso que ele
veio antes desta rotina. Não é um buraco escondido: é a razão de o inventário existir.

## 4. A decisão mais importante: falta de insumo não trava o fechamento

O serviço **já foi executado**. Se o sistema diz que não há tinta, ou o saldo está errado ou a
profissional substituiu o produto — nos dois casos o atendimento aconteceu. Recusar o fechamento
pararia o balcão com a cliente na cadeira, por um motivo contábil.

É diferente da venda de produto, onde alguém **lançou** o item: vender o que não existe é erro de
operação, e ali a recusa está certa. Aqui ninguém lançou nada — a ficha inferiu.

Então `baixarAteODisponivel` tira o que há, e `ConsumoDeServico.incompletas()` alimenta um `WARN`
com o produto e quanto faltou. Zero em estoque não impede o fechamento: baixa zero e segue.

## 5. O defeito que esta rotina revelou em código antigo

Eu escrevi no comentário do fechamento que a reabertura reverteria o consumo "de graça", porque a
origem é a mesma (`ITEM_COMANDA`). **Meio certo, e o teste pegou.**

`estoque.devolver` reverteria — mas `ReabrirComandaUseCase` e `EstornarComandaUseCase` tinham um
`if (tipo == PRODUTO)` antes de chamar. A tinta baixada ficava para trás, e o estoque ficaria
permanentemente menor do que a realidade sem ninguém ver até o inventário.

O conserto foi **remover o filtro**, não acrescentar um segundo ramo. O filtro era um vazamento:
saber se aquela origem gerou venda ou consumo é assunto do estoque, e `devolver` já é idempotente e
devolve zero quando não há nada. Perguntar é mais barato e mais correto do que decidir do lado de
fora (RN-EST-023).

## 6. Contrato

Casos de uso: `DefinirItemDaFichaUseCase` (definir é substituir), `RemoverItemDaFichaUseCase`,
`ConsultarFichaUseCase`. E `EstoqueApi.consumirPorServico`, chamado pelo fechamento da comanda.

Sem rota HTTP ainda — a tela da ficha é da rotina de frontend de estoque.

## 7. Testes obrigatórios

- [x] `FichaTecnicaIT` — 14 cenários: definir duas vezes substitui · produto só de venda recusado,
  com a recusa dizendo `AMBOS` · produto `AMBOS` entra · serviço de outro salão não existe ·
  quantidade não positiva recusada · remover o que não existe não reclama · **fechar a comanda
  baixa o que a ficha manda** · serviço sem ficha não consome · **reabrir devolve o consumo** ·
  **insumo em falta não trava o fechamento** · sem estoque nenhum ainda passa · dois insumos baixam
  os dois · **mudar a ficha não recalcula o passado** · sem `estoque:write` não define.

## 8. Pendências

- [ ] **Não há como ajustar o consumo no lançamento.** É a pendência mais séria: a coloração de
      cabelo curto e a de cabelo longo baixam a mesma tinta. O caminho natural é a tela de
      lançamento do item aceitar a quantidade real, guardando-a por item de comanda.
- [ ] **Não há rota nem tela** para a ficha.
- [ ] **A ficha não tem variação por tamanho/tom.** Um serviço, uma quantidade.
- [ ] **O consumo não entra no custo do serviço.** O item de produto grava `custo_unitario` no
      fechamento; o item de serviço não recebe o custo do insumo que gastou, então a margem por
      serviço ainda não existe.
- [ ] **Falta de insumo só vai para o log.** Deveria virar alerta na tela de estoque (RT-EST-008).
- [ ] **Não há ficha por profissional.** Quem tem a mão mais pesada gasta mais, e o sistema não
      sabe.
