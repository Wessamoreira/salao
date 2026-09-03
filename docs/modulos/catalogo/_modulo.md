# Módulo `catalogo`

## O que ele responde

*O que o salão vende, quanto tempo leva, quando o profissional fica livre no meio — e o que
mais, além dele, o serviço disputa.*

## Fronteira

Expõe `ServicoApi` (`porId`, `ativos`, `precoEm`, `historicoDePrecos`), `RecursoApi` (`porId`,
`ativos`), `ServicoResumo`, `Recurso` e o vocabulário de blocos (`SequenciaDeBlocos`,
`BlocoDoServico`, `TipoDeBloco`) e de preço (`PrecoVigente`).

`agenda` usa a sequência de blocos para saber quanto reservar e **quando o profissional fica
livre** — que é a informação que faz a agenda de um salão render — e os recursos de cada bloco para
saber **o que mais precisa estar livre no mesmo instante**. Profissional livre com lavatório
ocupado não é horário disponível.

## O que ele **não** guarda

**Quanto foi cobrado de fato.** O catálogo diz quanto o serviço custa numa data; o que a cliente
pagou é do `atendimento`, que precisa **copiar o preço para o item da comanda** (RN-CAT-006). Sem
essa cópia, alterar um preço mudaria o que um atendimento antigo custou.

**Quem executa o quê.** Habilidades são `RT-EQP-004`, no módulo `equipe`.

**Quanto tempo cada profissional leva.** O override por profissional é `RT-CAT-004`: a duração aqui
é a do serviço, não a da pessoa.

**Se o lavatório está ocupado às 15h.** O catálogo diz que existem três e que a lavagem usa um; quem
conta os ocupados é a `agenda`. E a garantia de não estourar a capacidade é dela: o `EXCLUDE` do
banco protege "no máximo um", não "no máximo três" — ver a primeira pendência de
[RT-CAT-003](RT-CAT-003-recurso.md).

## Depende de

- `iam` — `AuditoriaApi` e as permissões `config:manage` e `agenda:read:*`

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-CAT-001](RT-CAT-001-servico-com-blocos.md) — serviço com blocos | implementada |
| [RT-CAT-002](RT-CAT-002-preco-com-vigencia.md) — preço com vigência | implementada |
| [RT-CAT-003](RT-CAT-003-recurso.md) — recurso (lavatório, cadeira, sala) | implementada |
| RT-CAT-004 — override de duração por profissional | próxima |

## Regras

Ver [regras.md](regras.md) — RN-CAT-001 a RN-CAT-012.
