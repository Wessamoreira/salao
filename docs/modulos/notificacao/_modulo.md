# Módulo `notificacao`

- **Sigla:** NOT · **Fase de introdução:** 1 · **Status:** em construção

## O que ele responde

*Falar com a cliente — se, quando e como for permitido.*

## Fronteira

Expõe `NotificacaoApi.enfileirar` e o port `Mensageiro`.

Quem pede um lembrete **não decide** se pode mandá-lo: a pergunta do consentimento é feita aqui,
uma vez, contra o `ClienteApi`. É o próprio contrato daquele módulo que diz isso — *"a pergunta que
o módulo `notificacao` faz antes de qualquer envio, e a única que ele deve fazer"*.

## O que ele **não** guarda

**Consentimento.** Quem conhece base legal, decisão e revogação é `cliente` (RT-CLI-004). Um
booleano copiado para cá seria a regra da LGPD escrita duas vezes, e a segunda cópia é a que
continuaria mandando depois da revogação.

**Quem deve receber o quê.** `agenda` decide que existe um lembrete a mandar; este módulo só sabe
mandar.

## Depende de

- `cliente` — `ClienteApi` (consentimento e destino)
- `iam` — nada ainda; o módulo não registra em auditoria porque o próprio estado da mensagem é o
  registro

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-NOT-001](RT-NOT-001-envio.md) — envio com template, retry e opt-out | implementada |
| RT-NOT-002 — lembrete 24h antes | próxima |

## Regras

Ver [regras.md](regras.md) — RN-NOT-001 a RN-NOT-003.
