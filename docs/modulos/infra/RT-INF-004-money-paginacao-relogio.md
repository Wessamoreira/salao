---
id: RT-INF-004
titulo: Money, paginação keyset e relógio
modulo: infra
fase: 0
perfil: leve
status: implementado
depende_de: [RT-INF-001]
permissoes: []
eventos: []
regras: [RN-INF-005, RN-INF-006]
atualizado_em: 2026-08-29
---

# RT-INF-004 — Money, paginação keyset e relógio

## 1. Objetivo

Os três tipos primitivos do projeto que, se errados, produzem bug que só aparece meses depois:
dinheiro, tempo e listagem.

## 2. Contexto

Nenhum dos três dá erro visível quando está errado. `double` em dinheiro fecha o mês com centavos
a menos; `Instant.now()` espalhado torna regra de tempo intestável; `OFFSET` funciona bem com
mil linhas e mata o banco com cinquenta mil. Por isso os três nascem na Fase 0, junto com os
testes de arquitetura que impedem o retorno de cada um.

## 3. Regras aplicadas

| ID | Resumo | Garantida em |
|---|---|---|
| RN-INF-005 | Dinheiro nunca é `double`/`float` | `ArquiteturaTest.dinheiro_nunca_e_double_ou_float` |
| RN-INF-006 | Instante vem do port `Relogio` | `ArquiteturaTest.instante_nunca_vem_de_now` |

## 4. `Money`

Record sobre `BigDecimal`, escala canônica 4 e `HALF_UP`, espelhando `numeric(19,4)`.

**A escala é normalizada no construtor.** Sem isso, `BigDecimal.equals` considera `10.00`
diferente de `10.0000` e a igualdade de dinheiro passa a depender de como o número foi escrito —
uma classe de bug que aparece em asserção de teste e em chave de mapa.

**`ratearProporcionalmente` é a operação que mais importa.** Um desconto de R$ 10,00 entre três
itens iguais dá 3,3333… e a soma ingênua devolve R$ 9,9999. A última parte absorve a sobra, de
modo que a soma bata exatamente com o valor original. É o que evita o risco R-06 — o fechamento
do mês não bater com a conta que o dono fez à mão.

Peso total zero concentra tudo na primeira parte: não há proporção a aplicar, e perder o valor em
silêncio seria pior que concentrá-lo.

## 5. Paginação

`Cursor` opaco (base64 URL-safe, separador US) + `Pagina<T>`.

- **Não existe `totalDeItens`.** Em listagem grande o `count(*)` custa mais que a própria página.
  Onde o total for de fato necessário, vira endpoint separado e cacheado.
- **`Pagina.de(lidos, limite, extrairCursor)` recebe `limite + 1` itens.** Ler um a mais é como se
  descobre que existe próxima página sem pagar um `count`.
- O cursor **não é assinado nem criptografado**. Nunca coloque nele nada que o usuário não possa
  ver, e nunca confie nele para autorização — o backend refiltra por tenant e escopo de qualquer
  forma.

## 6. `Relogio`

Port com `agora()` e `hojeEm(ZoneId)`. `RelogioDoSistema` é a única classe autorizada a ler o
relógio real, e o teste de arquitetura garante isso.

`hojeEm` exige o fuso **do estabelecimento** (ADR-0009). Nunca o fuso padrão da JVM: o servidor
roda em UTC e o salão pode estar em qualquer fuso.

## 7. Testes

- [x] `MoneyTest` — 8 testes: escala, HALF_UP, rateio exato, rateio proporcional, peso zero,
      percentual, comparações
- [x] `PaginacaoTest` — 6 testes: round-trip do cursor, segurança para URL, cursor inválido,
      detecção de próxima página, última página, página vazia

## 8. Pendências

- [ ] `RelogioFixo` para teste chega junto da primeira regra de tempo (RN-AGD-008)
- [ ] Serialização de `Money` em JSON (string, nunca número — ponto flutuante no cliente é o
      mesmo problema do outro lado) entra com o primeiro endpoint que devolver valor
