# ADR-0007: Conciliação atrás de uma camada de anticorrupção, com plano B por extrato

- **Status:** proposta — depende da pergunta 12 · **Data:** 2026-08-28

## Contexto

A conciliação automática com a maquininha só é possível se o adquirente que o salão usa expuser
API pública ou webhook. Isso ainda não foi verificado.

## Decisão

1. **Não prometer automação antes de confirmar o adquirente.**
2. Modelar `TransacaoAdquirente` e `Conciliacao` de forma independente da origem, atrás de um port
   `ExtratoAdquirenteGateway`.
3. Implementar primeiro o adapter de **importação de arquivo** (CSV/OFX), que funciona com
   qualquer adquirente. O adapter de API entra depois, se existir.

## Consequências

**Positivas.** A Fase 5 entrega valor mesmo sem API: o matching por valor + janela de tempo +
NSU/autorização é o mesmo, e a divergência aparece na tela de qualquer jeito. Trocar a origem
custa um adapter.

**Negativas.** Sem API, a importação é manual e periódica. Isso precisa ser dito ao dono **antes**
da Fase 2, não na entrega.

**Revisitar** assim que a pergunta 12 for respondida.
