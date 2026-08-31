# Módulo `equipe`

## O que ele responde

*Quem trabalha aqui, sob qual vínculo, e quanto recebe.*

## Fronteira

Expõe `ProfissionalApi` (`porId`, `ativos`, `estaAtivo`, `jornadaDe`, `intervalosEm`,
`bloqueiosEntre`), `ProfissionalResumo` e o vocabulário de vínculo e de jornada.

`intervalosEm(profissional, data)` é o método que importa para a agenda: jornada daquele dia da
semana **menos** os bloqueios que alcançam a data. A jornada crua diz "terça das 9h às 19h"; no dia
14 a pessoa está em curso das 14h às 16h. `agenda` descobre por aqui quem pode atender; `financeiro`, qual a comissão. Nenhum deles
consulta a tabela.

## O que ele **não** guarda

**Agendamentos.** A equipe diz quando se pode atender; quem está marcado é `agenda`.

**Habilidades.** Quais serviços cada um executa depende do catálogo existir (`RT-EQP-004`).

**Extrato de comissão.** O percentual mora aqui; o cálculo e o pagamento pertencem a `financeiro`.

## Depende de

- `iam` — `AuditoriaApi` e a permissão `usuario:manage`

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-EQP-001](RT-EQP-001-cadastro-de-profissional.md) — cadastro | implementada |
| [RT-EQP-002](RT-EQP-002-jornada-semanal.md) — jornada semanal | implementada |
| [RT-EQP-003](RT-EQP-003-bloqueio-de-jornada.md) — exceção de jornada | implementada |
| RT-EQP-004 — habilidades | próxima |

## Regras

Ver [regras.md](regras.md) — RN-EQP-001 a RN-EQP-012.
