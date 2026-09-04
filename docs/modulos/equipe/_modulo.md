# Módulo `equipe`

## O que ele responde

*Quem trabalha aqui, sob qual vínculo, e quanto recebe.*

## Fronteira

Expõe `ProfissionalApi` (`porId`, `ativos`, `estaAtivo`, `jornadaDe`, `intervalosEm`,
`bloqueiosEntre`, `executa`, `habilidadesDe`, `queExecutam`), `ProfissionalResumo` e o vocabulário
de vínculo, de jornada e de habilidade (`Habilidades`).

`intervalosEm(profissional, data)` é o método que importa para a agenda: jornada daquele dia da
semana **menos** os bloqueios que alcançam a data. A jornada crua diz "terça das 9h às 19h"; no dia
14 a pessoa está em curso das 14h às 16h. `agenda` descobre por aqui quem pode atender; `financeiro`, qual a comissão. Nenhum deles
consulta a tabela.

## O que ele **não** guarda

**Agendamentos.** A equipe diz quando se pode atender; quem está marcado é `agenda`.

**O cardápio.** Que serviços existem é do `catalogo`; a equipe só guarda **quem executa quais**
(RT-EQP-004) — e por uuid solto, sem chave estrangeira, para não costurar os dois esquemas
(RN-EQP-015).

**Extrato de comissão.** O percentual mora aqui; o cálculo e o pagamento pertencem a `financeiro`.

## Depende de

- `iam` — `AuditoriaApi` e a permissão `usuario:manage`
- `catalogo` — `ServicoApi`, para conferir que um serviço declarado como habilidade existe neste
  salão. É a única barreira que existe: não há FK, e por RN-CAT-011 uma FK também não separaria os
  salões

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-EQP-001](RT-EQP-001-cadastro-de-profissional.md) — cadastro | implementada |
| [RT-EQP-002](RT-EQP-002-jornada-semanal.md) — jornada semanal | implementada |
| [RT-EQP-003](RT-EQP-003-bloqueio-de-jornada.md) — exceção de jornada | implementada |
| [RT-EQP-004](RT-EQP-004-habilidades.md) — habilidades | implementada |
| RT-CAT-004 — duração por profissional | próxima (acrescenta coluna à mesma tabela) |

## Regras

Ver [regras.md](regras.md) — RN-EQP-001 a RN-EQP-015.
