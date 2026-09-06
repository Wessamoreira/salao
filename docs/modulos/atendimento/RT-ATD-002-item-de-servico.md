---
id: RT-ATD-002
titulo: Adicionar item de serviço + executantes
modulo: atendimento
fase: 2
perfil: completo
status: implementado
depende_de: [RT-ATD-001, RT-EQP-001, RT-CAT-002]
permissoes: [comanda:open, comanda:close]
eventos: []
regras: [RN-ATD-005, RN-ATD-006, RN-ATD-007, RN-ATD-008, RN-ATD-009, RN-ATD-010]
atualizado_em: 2026-09-05
---

# RT-ATD-002 — Item de serviço

## 1. Objetivo

Lançar na comanda o que foi executado, e por quem. É a linha que vira faturamento e comissão.

## 2. Snapshot, não referência

Descrição e preço são **copiados** do catálogo no momento do lançamento. Uma linha que apontasse
para o serviço daria resposta diferente depois que o preço mudasse — e reprocessar um fechamento de
três meses atrás produziria número diferente do repasse já pago, sem que ninguém conseguisse
explicar a diferença ao profissional.

O mesmo vale para o percentual de comissão do executante, e por isso `regra_origem` é gravada
junto. A pergunta que chega depois não é *"quanto eu ganho"* — é **"por que a minha comissão foi
35% e não 40%"**, e ela só tem resposta se o nível da cascata que venceu ficou registrado.

Hoje há um nível só: o percentual da ficha do profissional. A cascata — padrão do salão, por
serviço, por profissional × serviço — é RT-FIN-002, e é lá que `OrigemDaComissao` ganha os outros
valores. Declarar agora os que ninguém escreve seria documentar no código uma regra que não existe.

## 3. Serviço sem preço é recusado

`ServicoApi.precoEm` devolve vazio quando não há preço definido, e o contrato dele já explica por
quê: *"zero é um preço, e um serviço sem preço num relatório financeiro precisa aparecer como
pendência de cadastro, não como brinde"*.

Lançar zero aqui transformaria uma falha de cadastro em faturamento perdido silencioso — o pior
tipo, porque a comanda fecha certinho e o dinheiro não aparece em lugar nenhum.

Serviço **fora do cardápio** também é recusado. Desativar é decisão do salão de não vender mais
aquilo; aceitar aqui faria a desativação valer só para quem marca horário, e não para quem cobra.

## 4. Quem executa precisa executar o serviço

Mesma exigência de RN-AGD-001, e pelo mesmo motivo do outro lado: deixar passar produziria comissão
para alguém cuja regra para aquele serviço nunca foi configurada. O número apareceria certo na tela
e errado no repasse.

Quando o salão põe alguém para substituir, a habilidade é o cadastro que precisava mesmo ser
corrigido — e a correção é uma tela, não um impedimento.

## 5. O horário de origem vive no item

`agendamento_id` é do item, não da comanda. Na comanda seria mentira já no segundo lançamento,
porque uma comanda recebe o horário das 9h e o das 14h (RN-ATD-004).

E ele é conferido contra a cliente da comanda: sem isso, o item da Maria poderia apontar para o
horário da Joana, e o relatório de *"quanto rende cada horário marcado"* ficaria errado sem nada
acusar.

## 6. Os totais são gravados, e somados no banco

`valor_bruto`, `valor_desconto` e `valor_liquido` são recalculados por **uma consulta**, no banco, a
cada mudança de item. Não são somados na aplicação e não são derivados na leitura.

Duas razões, e as duas doem:

- **Somar na leitura** daria resposta diferente depois que o preço do catálogo mudasse — é o mesmo
  problema do snapshot, uma camada acima.
- **Somar em dois lugares** — na tela e na gravação — produz divergência no dia em que alguém
  arredondar diferente. Por isso a resposta de lançar um item é a **comanda inteira**, e não só o
  item criado: o front não soma, ele mostra.

`versao` sobe junto. Quem estava com a comanda aberta na tela precisa saber que ela mudou.

## 7. Remover apaga, e a trilha fica na auditoria

Sem remoção, um item digitado errado seria permanente e a comanda não teria como ser corrigida
antes de fechar. O plano não tem rotina própria para isso; ela pertence a esta, que é a que lança.

**Apaga, não marca como removido.** Item lançado por engano não é fato do atendimento — é
digitação, e uma comanda cheia de linhas riscadas é pior de conferir do que uma comanda certa. O
que precisa sobreviver a correção é o *fechamento*, e comanda fechada não aceita remoção.

Quem quer a trilha tem a auditoria, que é append-only e registra o que saiu — e por isso o registro
é gravado **antes** do `delete`: depois dele não há mais o que registrar, e "o que saiu da comanda"
é justamente a pergunta que a trilha precisa responder.

É ação reversível no sentido de R-UX-05: executa na hora, e a tela oferece "Desfazer". Pedir
confirmação para tirar uma linha errada é atrito onde não há risco.

## 8. Testes

- [x] `AdicionarItemDeServicoIT` — 16: lança e a comanda soma · **o executante principal entra com
      percentual e comissão** · quantidade multiplica, e a comissão acompanha · dois itens somam ·
      **mudar o preço depois não mexe no item lançado** · **mudar a comissão depois também não** ·
      **serviço sem preço é recusado** · serviço fora do cardápio · **quem não executa não entra** ·
      profissional inativo · quantidade zero · **comanda que não está aberta não recebe item** ·
      o item guarda de qual horário veio · **horário de outra cliente é recusado** · remover devolve
      o total ao que era · remover item que não é desta comanda
- [x] `ComandasWebIT` — 11 (4 novos): **lançar devolve a comanda inteira com o total novo** ·
      o detalhe traz os itens e remover zera o total · item sem serviço nem profissional para na
      borda · comanda que não existe responde 404

## 9. Pendências

- [ ] **Produto não existe.** A tabela nasce inteira, com a metade de `PRODUTO` que só RT-ATD-003
      vai preencher — `produto_id` e `custo_medio_unitario` são colunas que ninguém escreve ainda
- [ ] **Desconto é sempre zero.** A coluna existe e o total já a subtrai; quem a preenche é
      RT-ATD-004, e é lá que `base_calculo` do executante precisa ser recalculada — hoje ela é
      sempre o valor cheio do item
- [ ] **Só há executante `PRINCIPAL`.** O assistente que lava é RT-ATD-005; o enum e o `check` já
      o preveem
- [ ] **A cascata de comissão tem um nível só.** `OrigemDaComissao.PROFISSIONAL` é o único valor, e
      profissional sem percentual configurado entra com 0% sem que nada avise — é falha de
      cadastro que só aparece no extrato. RT-FIN-002
- [ ] **Não há `comanda:write`.** Lançar e remover usam `comanda:open`, que é a permissão de abrir.
      Quem pode abrir pode lançar, o que é verdade hoje e não é a mesma frase
- [ ] **Comanda aberta não trava para edição concorrente.** `versao` sobe a cada item e ninguém a
      confere: duas recepcionistas lançando na mesma comanda ao mesmo tempo somam as duas, o que
      está certo — mas remover em cima de uma tela velha remove o item errado
- [ ] **Não há tela.** A comanda detalhada tem rota e não tem front (R-UX-08 é RT-ATD-008)
- [ ] **Nenhum evento.** Faturamento lançado não avisa ninguém; o razão só é escrito no fechamento
