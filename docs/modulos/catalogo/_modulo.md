# Módulo `catalogo`

## O que ele responde

*O que o salão vende, quanto tempo leva, e quando o profissional fica livre no meio.*

## Fronteira

Expõe `ServicoApi` (`porId`, `ativos`, `precoEm`, `historicoDePrecos`), `ServicoResumo` e o
vocabulário de blocos (`SequenciaDeBlocos`, `BlocoDoServico`, `TipoDeBloco`) e de preço
(`PrecoVigente`).

`agenda` usa a sequência de blocos para saber quanto reservar e **quando o profissional fica
livre** — que é a informação que faz a agenda de um salão render.

## O que ele **não** guarda

**Quanto foi cobrado de fato.** O catálogo diz quanto o serviço custa numa data; o que a cliente
pagou é do `atendimento`, que precisa **copiar o preço para o item da comanda** (RN-CAT-006). Sem
essa cópia, alterar um preço mudaria o que um atendimento antigo custou.

**Quem executa o quê.** Habilidades são `RT-EQP-004`, no módulo `equipe`.

**Quanto tempo cada profissional leva.** O override por profissional é `RT-CAT-004`: a duração aqui
é a do serviço, não a da pessoa.

## Depende de

- `iam` — `AuditoriaApi` e as permissões `config:manage` e `agenda:read:*`

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-CAT-001](RT-CAT-001-servico-com-blocos.md) — serviço com blocos | implementada |
| [RT-CAT-002](RT-CAT-002-preco-com-vigencia.md) — preço com vigência | implementada |
| RT-CAT-003 — recurso (lavatório, cadeira, sala) | próxima |
| RT-CAT-004 — override de duração por profissional | planejada |

## Regras

Ver [regras.md](regras.md) — RN-CAT-001 a RN-CAT-008.
