# Documentação

## Ordem de leitura para quem chega agora

1. `00-visao-e-escopo.md` — o que o sistema é e o que ele deliberadamente não é
2. `01-glossario.md` — a linguagem que o código usa
3. `02-decisoes-estruturais.md` — as 8 decisões que definem o resto
4. `03-arquitetura.md` — módulos, camadas, fronteiras
5. `08-padrao-de-documentacao.md` — como documentar antes de implementar
6. `09-plano-de-implementacao.md` — qual é a próxima rotina
7. `14-checklist-de-execucao.md` — como saber, sem confiar na memória, o que falta

## Mapa

| Arquivo | Conteúdo | Muda com que frequência |
|---|---|---|
| `00-visao-e-escopo.md` | Produto, personas, escopo, não-escopo | Raro |
| `01-glossario.md` | Linguagem ubíqua | A cada termo novo |
| `02-decisoes-estruturais.md` | D1–D8, com custo de reversão | Raro; mudança exige ADR |
| `03-arquitetura.md` | Módulos, camadas, eventos, outbox | Por fase |
| `04-modelo-de-dados.md` | DDL comentado, índices, invariantes | A cada migration |
| `05-seguranca-multitenancy-lgpd.md` | Tenant, RLS, auth, dado sensível, backup | Por fase |
| `06-padroes-de-codigo.md` | Nome, pacote, erro, teste, commit | Raro |
| `07-contratos-de-api.md` | Erro padrão, paginação, idempotência, capabilities | A cada contrato novo |
| `08-padrao-de-documentacao.md` | Templates e IDs | Raro |
| `09-plano-de-implementacao.md` | Backlog de rotinas por fase | Toda semana |
| `10-usabilidade.md` | Requisitos de UX tratados como requisito | Por fase |
| `11-fragilidades-e-riscos.md` | Riscos técnicos, de produto e de operação | Mensal |
| `12-observabilidade-e-operacao.md` | Métrica, log, alerta, runbook | Por fase |
| `13-perguntas-em-aberto.md` | O que trava decisão, com prazo | Toda semana |
| `14-checklist-de-execucao.md` | Como o plano é conferido contra a realidade | Raro |
| `15-checklist-de-seguranca.md` | Auditoria de segurança, com onde verificar cada item | Por fase |
| `16-design-system.md` | O padrão visual e o porquê; os valores estão em `design/tokens.css` | Raro |
| `adr/` | Decisões com contexto e consequência | Por decisão |
| `modulos/<mod>/_modulo.md` | Responsabilidade, agregados, API pública | Por fase |
| `modulos/<mod>/regras.md` | **Catálogo de regras de negócio (RN)** | A cada regra |
| `modulos/<mod>/RT-*.md` | Especificação de rotina | A cada rotina |

## Convenção de identificadores

| Prefixo | O quê | Exemplo |
|---|---|---|
| `RT-` | Rotina / caso de uso | `RT-AGD-002` |
| `RN-` | Regra de negócio | `RN-AGD-004` |
| `ER-` | Erro de domínio (código estável para o front) | `ER-AGD-CONFLITO_HORARIO` |
| `EV-` | Evento de domínio | `EV-AGD-AGENDAMENTO_CRIADO` |
| `PM-` | Permissão | `agenda:write:all` |
| `ADR-` | Decisão de arquitetura | `ADR-0003` |

Siglas de módulo: `INF` (infra/transversal), `IAM`, `EQP` (equipe), `CLI` (cliente), `CAT`
(catálogo), `AGD` (agenda), `ATD` (atendimento), `FIN` (financeiro), `EST` (estoque),
`CNV` (conversacional), `NOT` (notificação), `ARQ` (arquivos).
