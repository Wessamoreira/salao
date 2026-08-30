# Módulo `cliente`

## O que ele responde

*Quem é esta pessoa, e como falo com ela.*

## Fronteira

Expõe `ClienteApi` (`porId`, `existe`) e `ClienteResumo`. `agenda` e `atendimento` resolvem o
cliente por aqui, nunca por join — é essa fronteira que torna a extração futura barata.

`ClienteResumo` é deliberadamente magro: quem monta um agendamento precisa saber quem é a pessoa e
como falar com ela, não a ficha inteira.

## O que ele **não** guarda

**Ficha técnica de química.** Coloração, alergia e reação são dado de saúde sob a LGPD. Exigem
`cliente:ficha:read` e cifragem de campo (`CofreDeCampo`), e por isso ficam numa tabela própria com
rotina própria. O campo `observacao` desta rotina é texto livre e **não deve** receber informação
clínica — está em claro no banco.

**Histórico de atendimento.** Pertence a `atendimento`. Aqui ficaria como uma coleção que só cresce
e que ninguém saberia invalidar.

## Depende de

- `iam` — `EstabelecimentoApi` (DDD e país padrão para normalizar telefone) e `AuditoriaApi`

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-CLI-001](RT-CLI-001-cadastro-de-cliente.md) — cadastro | implementada |
| [RT-CLI-002](RT-CLI-002-busca-de-cliente.md) — busca por nome, apelido e 4 últimos dígitos | implementada |
| RT-CLI-003 — detecção de duplicado e mesclagem | próxima |
| RT-CLI-004 — consentimento LGPD | planejada |
| RT-CLI-005 — anonimização | planejada |
| RT-CLI-006 — tela de clientes | planejada |

## Regras

Ver [regras.md](regras.md) — RN-CLI-001 a RN-CLI-012.
