---
id: RT-ATD-008
titulo: Tela de fechamento em um único passo, com split de pagamento
modulo: atendimento
fase: 2
perfil: completo
status: implementado
depende_de: [RT-ATD-006, RT-INF-010]
permissoes: [comanda:open, comanda:close, comanda:discount]
eventos: []
regras: [RN-ATD-025]
atualizado_em: 2026-09-05
---

# RT-ATD-008 — Tela de fechamento

## 1. Objetivo

Pôr numa tela só o que o balcão faz com a cliente na frente: lançar, descontar, receber e fechar.

## 2. Uma tela, sem navegação

R-UX-08 pede serviços, produtos, desconto e pagamento juntos. Não é preferência estética: cada passo
de navegação é uma chance de perder o fio no meio de uma conta que alguém está esperando.

Por isso o desconto é um campo **na própria linha** do item, e não um diálogo; e as formas de
pagamento já registradas ficam ao lado do campo, não atrás de um "adicionar outra forma".

## 3. Nenhuma conta acontece aqui

Lançar item, descontar e pagar devolvem a comanda ou a conta **inteira**, e a tela mostra o que
chegou. `restante` vem pronto do servidor.

É RN-ATD-008 vista do outro lado: subtrair no front seria a mesma conta em dois lugares, e a
divergência apareceria como um centavo que impede o fechamento — com a cliente esperando. A única
conta que a tela faz é *"há quantas horas esta comanda está aberta"*, e ela não decide dinheiro
nenhum.

## 4. A seleção é derivada, não sincronizada

Enquanto ninguém escolheu, vale a primeira comanda aberta — a mais antiga, que é a que R-UX-07 diz
interessar.

Isso é calculado durante a renderização, não guardado por efeito. Um `useEffect` que definisse a
seleção faria a tela renderizar uma vez sem nada e se corrigir depois, piscando a cada chegada da
lista. O lint apontou, e a correção deixou o código mais simples do que estava.

## 5. O alerta de comanda esquecida é cor **e** texto

*"Comanda aberta esquecida é dinheiro que sai pela porta"* (R-UX-07). Passadas três horas, a linha
ganha uma borda vermelha **e** a frase "aberta há 5h".

Só a cor deixaria de fora quem não a distingue — e este aviso é sobre dinheiro ficando para trás,
que é justamente o que ninguém pode perder.

## 6. A mensagem que aparece é a do servidor

*"Há 3 em estoque e a saída é de 5."* *"Faltam R$ 20,00."* *"Desconto de 50% pedido; seu limite é
10%."*

Cada uma dessas frases foi escrita numa rotina do backend para ser acionável. Trocá-las por "não foi
possível concluir a operação" apagaria exatamente a parte que resolve (R-UX-20). A tela mostra o que
veio.

## 7. A serifa estava no lugar errado

O design system reserva a Instrument Serif a **dois** lugares: identidade do estabelecimento e
**totais** — *"o negócio é beleza; um gesto editorial se justifica. Em texto operacional, nunca."*

A primeira versão desta tela fez o inverso: serifa em "Comanda #128" e em "Nenhuma comanda
selecionada", que são texto operacional, e sans no total e no restante — que são exatamente os dois
números que a regra existe para destacar.

Não foi o navegador que pegou: foi reler a regra. É o mesmo tipo de defeito que RT-CLI-006
encontrou, e ele não aparece em teste nenhum.

## 8. Testes

- [x] `Comandas.test.tsx` — 9: abre já com a comanda mais antiga selecionada · **mostra o que falta
      sem calcular** · não deixa fechar enquanto falta · libera quando zera · registra pagamento
      pela rota certa · **mostra a mensagem do servidor, não uma genérica** · **avisa por texto, não
      só por cor** · comanda fechada não mostra campos de edição · **todo botão tem ação** (o achado
      de RT-AGD-011)

> **Uma armadilha do próprio teste**, encontrada aqui: `beforeEach(() => chamar.mockReset())`
> devolve o mock, e o Vitest trata função devolvida de um hook como *teardown* — ele chamava
> `chamar()` **sem argumentos** no fim de cada teste, e o servidor falso quebrava num ponto que não
> tinha nada a ver com o que o teste fazia. Chaves resolvem. É a mesma família de "a ferramenta
> estava lá e não fazia o que parecia".

## 9. Pendências

- [ ] **A revisão visual no navegador não foi feita.** O servidor de prévia sobe o processo e nunca
      abre a porta neste ambiente. A página `preview/comandas.html` está pronta
      (`npm run previa:comandas`), e a auditoria que se pôde fazer foi estática — foi ela que achou
      a inversão da serifa. Contraste medido, densidade e hierarquia real continuam por verificar
- [ ] **Não dá para abrir comanda pela tela.** Ela lista as abertas e opera a selecionada; abrir
      exige escolher cliente, e a busca de cliente é outra tela
- [ ] **Sem "Desfazer".** R-UX-05 pede toast com desfazer para ação reversível — remover item e
      remover pagamento executam na hora e não oferecem volta
- [ ] **Sem atalhos de teclado.** R-UX-02 pede o fluxo inteiro sem mouse; hoje só o `Tab` natural
- [ ] **Assistente e desconto em valor não têm campo.** O executante adicional (RT-ATD-005) e o
      desconto em reais existem na API e não na tela — só o desconto percentual está lá
- [ ] **Reabrir e estornar não têm botão.** São de `comanda:reopen`, e a tela não mostra nada para
      quem tem a permissão
- [ ] **Nada é responsivo abaixo de ~900px.** O balcão usa monitor; o celular do dono (R-UX-19) é
      outra tela
