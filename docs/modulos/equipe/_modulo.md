# Módulo `equipe`

## O que ele responde

*Quem trabalha aqui, sob qual vínculo, e quanto recebe.*

## Fronteira

Expõe `ProfissionalApi` (`porId`, `ativos`, `estaAtivo`), `ProfissionalResumo` e o vocabulário de
vínculo. `agenda` descobre por aqui quem pode atender; `financeiro`, qual a comissão. Nenhum deles
consulta a tabela.

## O que ele **não** guarda

**Jornada e exceções.** Quem trabalha quando é `RT-EQP-002` e `RT-EQP-003`, em tabelas próprias:
misturar horário com cadastro faria a ficha do profissional crescer sem limite.

**Habilidades.** Quais serviços cada um executa depende do catálogo existir (`RT-EQP-004`).

**Extrato de comissão.** O percentual mora aqui; o cálculo e o pagamento pertencem a `financeiro`.

## Depende de

- `iam` — `AuditoriaApi` e a permissão `usuario:manage`

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-EQP-001](RT-EQP-001-cadastro-de-profissional.md) — cadastro | implementada |
| RT-EQP-002 — jornada semanal | próxima |
| RT-EQP-003 — exceção de jornada | planejada |
| RT-EQP-004 — habilidades | planejada |

## Regras

Ver [regras.md](regras.md) — RN-EQP-001 a RN-EQP-005.
