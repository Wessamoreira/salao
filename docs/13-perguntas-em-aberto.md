# Perguntas em aberto

Cada pergunta tem **prazo** (a fase que ela bloqueia) e, quando cabível, **um default que destrava
o desenvolvimento** enquanto a resposta não vem.

## Já resolvidas por configuração — não bloqueiam mais

Estas quatro viraram coluna em `estabelecimento`. Modelar como configuração custou quase nada e a
resposta muda com o tempo de qualquer forma.

| # | Pergunta | Onde vive | Default |
|---|---|---|---|
| 1 | Comissão sobre bruto ou líquido da taxa? | `estabelecimento.base_comissao` | `BRUTO` |
| 2 | Desconto reduz a comissão ou o salão absorve? | `estabelecimento.desconto_afeta_comissao` | `false` (salão absorve) |
| 3 | Produto paga comissão? Qual percentual? | Cascata de comissão, nível serviço/produto | 0% em produto |
| 5 | Periodicidade do fechamento? | `estabelecimento.periodicidade_fechamento` | `MENSAL` |

**Confirme os defaults com o dono antes da Fase 2** — mas não pare o desenvolvimento por eles.

## Bloqueiam a Fase 1 (schema da agenda)

| # | Pergunta | Por que bloqueia | Default de trabalho |
|---|---|---|---|
| 7 | Durante a pausa da química, encaixe automático ou só manual? | Muda `RT-AGD-001` e a política de disponibilidade | **Manual.** Automático precisa de política para quando a química atrasa — e ela atrasa |
| 10 | Existem recursos escassos (lavatório, cadeira) ou o gargalo é só o profissional? | Se não existem, some metade da complexidade da agenda | Assumir que **existem**. Modelo suporta zero recursos sem custo; o contrário exige migration |
| — | Quantos blocos tem o serviço mais complexo do salão? | Valida o modelo de blocos (R-07) | Pegar 3 serviços reais e modelar junto com o dono |

## Bloqueiam a Fase 2 (financeiro)

| # | Pergunta | Por que bloqueia |
|---|---|---|
| 4 | Existe vale/adiantamento no meio do período? | `RT-FIN-005`; muda o cálculo do repasse |
| 6 | Assistente que lava entra na comanda com percentual próprio? | Resolvido pelo modelo `executante`; falta saber o percentual usual |
| — | Existe fiado? Como é cobrado depois? | `forma = FIADO` já existe; falta a rotina de cobrança |
| — | Cortesia sai de onde — do salão ou da comissão do profissional? | Afeta o razão e o extrato |

## Bloqueiam a Fase 4 (conversacional)

| # | Pergunta | Recomendação |
|---|---|---|
| 8 | Cliente agenda sozinho pelo WhatsApp na v1? | **Não.** Muda o modelo de identidade inteiro — hoje cliente não tem login e passaria a ter identidade por telefone — e abre superfície de abuso que a Fase 4 não prevê. v2 |
| 9 | Política de no-show: cobra, bloqueia ou só registra? | Começar por **só registrar**. Cobrar exige política escrita, aviso prévio ao cliente e forma de cobrança |

## Bloqueiam a Fase 5

| # | Pergunta | Impacto |
|---|---|---|
| 12 | Qual adquirente o salão usa hoje? | Define D3. Sem API pública, a Fase 5 é importação de extrato, e isso precisa ser dito antes de prometer |

## Comerciais e de dimensionamento

| # | Pergunta | Impacto |
|---|---|---|
| 11 | Quantos salões na v1 — só o seu ou já vender? | Não muda o schema (multi-tenant é dia 0), mas muda onboarding, cobrança e suporte |
| 13 | Volume: agendamentos/dia e profissionais simultâneos? | Dimensiona pool, VM e densidade da grade |
| 14 | Orçamento mensal: infra + WhatsApp por conversa + LLM + **STT** | STT de áudio ficou fora da conta original e não é barato |

---

## Como usar este arquivo

Toda resposta obtida sai daqui e vira: (a) linha em `regras.md` do módulo, com `RN-`, ou
(b) coluna de configuração, ou (c) ADR se for estrutural. **Resposta que fica só aqui se perde.**

Revisão semanal. Pergunta sem resposta e sem prazo é uma decisão sendo adiada em silêncio.
