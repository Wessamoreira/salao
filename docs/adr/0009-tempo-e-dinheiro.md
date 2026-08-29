# ADR-0009: `timestamptz` em UTC com fuso por estabelecimento; dinheiro em `BigDecimal`

- **Status:** aceita · **Data:** 2026-08-28

## Contexto

Agenda e dinheiro são os dois lugares onde erro de representação vira bug que só aparece meses
depois — no horário de verão e no fechamento do mês.

O rascunho fixava `America/Sao_Paulo` na borda. Isso contradiz a decisão de ser multi-tenant desde
o dia 0: um estabelecimento em outro estado ou país teria a agenda inteira deslocada.

## Decisão

- **Tempo:** `timestamptz` armazenado em UTC. Conversão só na borda, com
  `estabelecimento.timezone` (IANA). Nunca uma constante no código. Nunca `LocalDateTime` para
  instante. Injetar `Relogio` — nunca `Instant.now()` espalhado.
- **Dinheiro:** `BigDecimal` mapeado para `numeric(19,4)`, encapsulado em `Money`. Nunca `double`
  ou `float`. Comparação por `compareTo`, nunca `equals`. Arredondamento `HALF_UP` explícito em
  toda divisão.

## Consequências

**Positivas.** Horário de verão, se voltar, não move nenhum agendamento. Vender para salão de
outro fuso é uma linha de configuração. O centavo fecha.

**Negativas.** Toda tela precisa saber o fuso do estabelecimento — ele vem em
`/me/capabilities`. Rateio de desconto precisa de regra explícita de arredondamento: o último
item absorve a diferença, para que a soma bata exatamente.

**Custo de reverter: altíssimo.** Toca agenda, financeiro e todo relatório.
