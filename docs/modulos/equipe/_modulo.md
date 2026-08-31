# Módulo `equipe`

## O que ele responde

*Quem trabalha aqui, sob qual vínculo, e quanto recebe.*

## Fronteira

Expõe `ProfissionalApi` (`porId`, `ativos`, `estaAtivo`), `ProfissionalResumo` e o vocabulário de
vínculo. `agenda` descobre por aqui quem pode atender; `financeiro`, qual a comissão. Nenhum deles
consulta a tabela.

## O que ele **não** guarda

**Exceções de jornada.** Feriado, férias, curso e atestado são `RT-EQP-003`, em tabela própria:
misturá-las com a jornada semanal faria esta crescer com datas e deixar de responder à pergunta
simples que o salão faz — "que dias você trabalha?".

**Habilidades.** Quais serviços cada um executa depende do catálogo existir (`RT-EQP-004`).

**Extrato de comissão.** O percentual mora aqui; o cálculo e o pagamento pertencem a `financeiro`.

## Depende de

- `iam` — `AuditoriaApi` e a permissão `usuario:manage`

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-EQP-001](RT-EQP-001-cadastro-de-profissional.md) — cadastro | implementada |
| [RT-EQP-002](RT-EQP-002-jornada-semanal.md) — jornada semanal | implementada |
| RT-EQP-003 — exceção de jornada | próxima |
| RT-EQP-004 — habilidades | planejada |

## Regras

Ver [regras.md](regras.md) — RN-EQP-001 a RN-EQP-008.
