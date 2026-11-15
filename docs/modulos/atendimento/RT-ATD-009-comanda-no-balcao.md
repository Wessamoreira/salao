---
id: RT-ATD-009
titulo: A comanda no balcão — três colunas, o dinheiro sempre à vista
modulo: atendimento
fase: 7
perfil: leve
status: implementado
depende_de: [RT-ATD-008, RT-INF-016]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-11-15
---

# RT-ATD-009 — A comanda no balcão

## 1. Objetivo

Redesenhar a tela de comandas — a mais usada no balcão e a menos acabada (O4 e M5 de
`19-auditoria-de-telas.md`) — **sem mudar nenhuma regra**: quem soma continua sendo o servidor,
e todo comportamento de `RT-ATD-008` (trava contra clique duplo, bipe que só escolhe, metade
esmaecida enquanto relê) fica como está, com os mesmos testes.

## 2. Contexto — o que estava errado, medido

| Defeito | Consequência |
|---|---|
| **"Falta receber" no pé da página**, abaixo da tabela e dos pagamentos | Viola R-UX-08 ("o restante fica **sempre visível**"): com cinco itens, é preciso rolar para saber quanto falta e para achar o botão de fechar |
| Lançar em dois blocos altos lado a lado (seis linhas de formulário) | A tabela de itens começa abaixo da dobra no notebook do balcão |
| Forma de pagamento num `<select>` | Dois toques e uma lista para a escolha mais repetida do dia |
| Status em texto colorido (selo "aberta" azul, "quitada" verde, aviso de esquecida vermelho, "remover" vermelho no hover) | Cor de status como texto: o sistema já mediu que nenhuma passa AA |
| "REMOVER" em caixa alta sublinhado em toda linha | O elemento mais repetido da tabela gritava mais que o item |
| No celular, as comandas abertas numa faixa cortada sem indicar que rola | M5 |

## 3. A decisão — três colunas, como um caixa

```
┌ abertas ─────┬ trabalho ──────────────────────────┬ receber ────────────┐
│ #128 Marilda │ Comanda #128 · Marilda Souza Aberta│ Total     R$ 193,50 │
│ #129 Josefa  │ [Serviço ▾][Quem ▾][Lançar serviço]│ Pago      R$ 100,00 │
│ #130 Cleide  │ [Bipar][Produto ▾][Qtd][Lançar]    │ Crédito 2x   100,00 │
│              │ Item         Qtd  Desc   Total   × │ (Din)(Pix)(Déb)(Cré)│
│              │ Coloração      1  10%  153,00    × │ Valor [93,50][Regis]│
│              │ Bruto · Desconto · Total           │ Falta receber       │
│              │                                    │ R$ 93,50            │
│              │                                    │ [ Fechar comanda  ] │
└──────────────┴────────────────────────────────────┴─────────────────────┘
```

- **A coluna do dinheiro tem rolagem própria e o pé fixo**: "Falta receber" e "Fechar comanda"
  ficam visíveis com qualquer número de itens — R-UX-08 cumprida de fato.
- **Total e pago vêm do servidor** (`conta.comanda.valorLiquido`, `conta.totalPago`): nenhuma
  soma no front (RN-ATD-008).
- **O botão de fechar diz por que está desarmado**: "Falta receber R$ 93,50 para fechar", ligado
  ao botão por `aria-describedby`. Botão cinza sem motivo é a pergunta que a recepção faz ao
  gerente.
- **Forma de pagamento em botões de rádio** (Dinheiro, Pix, Débito, Crédito, Transferência,
  Fiado, Cortesia): um toque. Rádio nativo, então teclado e leitor de tela funcionam sem nada a mais.
- **Lançar em duas linhas** (serviço, produto) em vez de dois blocos de seis.
- **Os `<select>` de serviço, profissional e produto continuam nativos**: no celular abrem o
  seletor do sistema, que é o melhor que existe para o polegar.
- **Status como marca**, nunca como cor de letra: o selo é fundo claro com borda; o aviso de
  esquecida é texto em tinta com a barra da falta.
- **Remover é um ícone discreto** com nome acessível ("Remover Shampoo") e dica ao passar o mouse.

**Celular:** uma coluna; as abertas viram uma faixa horizontal com rolagem por encaixe e borda
esmaecida indicando que há mais; e o pé "Falta receber + Fechar comanda" fica **fixo na base da
tela**, acima das abas — a mesma regra do desktop, no lugar do polegar. Fixo, e não `sticky`:
grudado, ele só grudaria dentro do bloco do dinheiro, que fica no fim da página — apareceria
quando ninguém mais precisasse dele (erro cometido e corrigido na primeira versão).

**Detalhes que vieram junto:** a célula do nome na tabela tinha `display: flex` e saía da tabela
(a régua de baixo ficava 2px fora); a sugestão de valor aparecia como "93.50" e agora é "93,50";
o campo de desconto cortava "R$ 4,5" e agora cabe o valor inteiro.

## 4. Atores e permissões

Nenhuma mudança.

## 11. Casos de borda

| Situação | Comportamento |
|---|---|
| Comanda fechada ou estornada | Sem lançar, sem descontar, sem pagar, sem fechar — como antes; a coluna do dinheiro mostra o que foi pago |
| Conta quitada | "Falta receber R$ 0,00" com a marca de confirmado; o botão arma |
| Pagamento a mais (troco) | O servidor decide; a tela mostra o restante que vier |

## 15. Testes obrigatórios

- [x] Os 15 testes de `Comandas.test.tsx` passam sem mudar o que verificam (+2 novos: 17)
- [x] O botão de fechar desarmado diz quanto falta
- [x] A forma de pagamento é escolhida por rádio, e Dinheiro continua o padrão
- [x] Suíte inteira, typecheck, lint e build
