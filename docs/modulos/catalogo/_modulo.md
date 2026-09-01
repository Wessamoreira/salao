# Módulo `catalogo`

## O que ele responde

*O que o salão vende, quanto tempo leva, e quando o profissional fica livre no meio.*

## Fronteira

Expõe `ServicoApi` (`porId`, `ativos`), `ServicoResumo` e o vocabulário de blocos
(`SequenciaDeBlocos`, `BlocoDoServico`, `TipoDeBloco`).

`agenda` usa a sequência de blocos para saber quanto reservar e **quando o profissional fica
livre** — que é a informação que faz a agenda de um salão render.

## O que ele **não** guarda

**Preço.** Tem vigência e histórico próprios (`RT-CAT-002`). Embutir o preço no serviço faria esta
consulta significar coisas diferentes conforme a data.

**Quem executa o quê.** Habilidades são `RT-EQP-004`, no módulo `equipe`.

**Quanto tempo cada profissional leva.** O override por profissional é `RT-CAT-004`: a duração aqui
é a do serviço, não a da pessoa.

## Depende de

- `iam` — `AuditoriaApi` e as permissões `config:manage` e `agenda:read:*`

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-CAT-001](RT-CAT-001-servico-com-blocos.md) — serviço com blocos | implementada |
| RT-CAT-002 — preço com vigência | próxima |
| RT-CAT-003 — recurso (lavatório, cadeira, sala) | planejada |
| RT-CAT-004 — override de duração por profissional | planejada |

## Regras

Ver [regras.md](regras.md) — RN-CAT-001 a RN-CAT-004.
