# Módulo `cliente`

## O que ele responde

*Quem é esta pessoa, e como falo com ela.*

## Fronteira

Expõe `ClienteApi` (`porId`, `existe`, `podeReceber`, `consentimentos`), `ClienteResumo` e o
vocabulário de consentimento (`Finalidade`, `BaseLegal`, `CanalDeConsentimento`,
`EstadoDoConsentimento`). `agenda` e `atendimento` resolvem o
cliente por aqui, nunca por join — é essa fronteira que torna a extração futura barata.

`ClienteResumo` é deliberadamente magro: quem monta um agendamento precisa saber quem é a pessoa e
como falar com ela, não a ficha inteira.

## O que ele **não** guarda

**Ficha técnica de química.** Coloração, alergia e reação são dado de saúde sob a LGPD. Exigem
`cliente:ficha:read` e cifragem de campo (`CofreDeCampo`), e por isso ficam numa tabela própria com
rotina própria. O campo `observacao` desta rotina é texto livre e **não deve** receber informação
clínica — está em claro no banco.

**A decisão de mandar mensagem.** `notificacao` pergunta `podeReceber(cliente, finalidade)` e
obedece. Quem conhece base legal, consentimento e revogação é este módulo — um `if` sobre um
booleano do outro lado seria a regra da LGPD escrita duas vezes, e a segunda cópia é a que
continuaria mandando promoção depois da revogação.

**Histórico de atendimento.** Pertence a `atendimento`. Aqui ficaria como uma coleção que só cresce
e que ninguém saberia invalidar.

## Depende de

- `iam` — `EstabelecimentoApi` (DDD e país padrão para normalizar telefone) e `AuditoriaApi`

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-CLI-001](RT-CLI-001-cadastro-de-cliente.md) — cadastro | implementada |
| [RT-CLI-002](RT-CLI-002-busca-de-cliente.md) — busca por nome, apelido e 4 últimos dígitos | implementada |
| [RT-CLI-003](RT-CLI-003-duplicados-e-mesclagem.md) — detecção de duplicado e mesclagem | implementada |
| [RT-CLI-004](RT-CLI-004-consentimento.md) — consentimento LGPD | implementada |
| [RT-CLI-005](RT-CLI-005-anonimizacao.md) — anonimização | implementada |
| RT-CLI-006 — tela de clientes | próxima |

## Regras

Ver [regras.md](regras.md) — RN-CLI-001 a RN-CLI-025.
