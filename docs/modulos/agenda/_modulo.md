# Módulo `agenda`

- **Sigla:** AGD · **Fase de introdução:** 1 · **Status:** não iniciado

## Responsabilidade

Decidir **quando** um profissional e um recurso estão ocupados, e garantir que nunca estejam
ocupados duas vezes.

**Não é responsabilidade deste módulo:** preço, comissão, o que foi executado, cobrança. Isso é
`catalogo`, `atendimento` e `financeiro`. A agenda não sabe quanto custa um corte.

## Agregados

| Agregado | Invariantes que protege |
|---|---|
| `Agendamento` | Blocos consistentes com o serviço · transição de status válida · status dos blocos igual ao da cabeça · pertence a um único tenant |
| `Bloqueio` | Período não vazio · profissional ou recurso, nunca nenhum dos dois |

## API pública (`agenda/api`)

| Operação | Assinatura | Consumido por |
|---|---|---|
| Consultar disponibilidade | `DisponibilidadeApi.consultar(profissionalId, data, servicoId)` | `conversacional`, front |
| Buscar agendamento | `AgendaApi.porId(id)` → `AgendamentoResumo` | `atendimento` |
| Agenda do dia | `AgendaApi.doDia(data)` → `List<AgendamentoResumo>` | `atendimento`, painel |
| Marcar em atendimento | `AgendaApi.iniciarAtendimento(id)` | `atendimento` (ao abrir comanda) |

`AgendamentoResumo` **não** expõe entidade JPA nem dados de cliente além de id e nome.

## Eventos publicados

| ID | Evento | Quando | Consumidores | Tipo |
|---|---|---|---|---|
| EV-AGD-001 | `AgendamentoCriado` | Após gravar | `notificacao` (confirmação) | Externo (outbox) |
| EV-AGD-002 | `AgendamentoConfirmado` | Cliente ou profissional confirma | `notificacao` | Externo |
| EV-AGD-003 | `AgendamentoReagendado` | Mudança de horário | `notificacao` | Externo |
| EV-AGD-004 | `AgendamentoCancelado` | Cancelamento | `notificacao`, `agenda` (fila de espera) | Externo |
| EV-AGD-005 | `NoShowRegistrado` | Marcado no-show | `cliente` (histórico) | Interno |
| EV-AGD-006 | `VagaLiberada` | Cancelamento abre janela | `agenda` (chama a fila) | Interno |

## Eventos consumidos

| Evento | De onde | O que faz |
|---|---|---|
| `JornadaAlterada` | `equipe` | Invalida cache de disponibilidade; sinaliza agendamentos órfãos |
| `ProfissionalDesativado` | `equipe` | Bloqueia novos agendamentos; lista os futuros para realocação |
| `ServicoDesativado` | `catalogo` | Impede novos; mantém os existentes |
| `ComandaFechada` | `atendimento` | Move o agendamento para `CONCLUIDO` |

## Tabelas

| Tabela | Agregado | RLS |
|---|---|---|
| `agendamento` | Agendamento | sim |
| `agendamento_bloco` | Agendamento | sim |
| `recorrencia` | Agendamento | sim |
| `fila_espera` | — | sim |

## Permissões

| Permissão | Significado |
|---|---|
| `agenda:read:own` | Vê apenas a própria agenda |
| `agenda:read:all` | Vê a agenda de todos |
| `agenda:write:own` | Cria, move e cancela na própria agenda |
| `agenda:write:all` | Idem, na agenda de qualquer profissional |

Escopo `:own` é filtro de query, não de tela. Acesso a id de outro devolve **404**, não 403.

## Rotinas

| ID | Título | Fase | Status |
|---|---|---|---|
| RT-AGD-001 | Consultar disponibilidade | 1 | rascunho |
| RT-AGD-002 | Criar agendamento | 1 | especificado |
| RT-AGD-003 | Reagendar | 1 | rascunho |
| RT-AGD-004 | Confirmar | 1 | rascunho |
| RT-AGD-005 | Cancelar | 1 | rascunho |
| RT-AGD-006 | Registrar no-show | 1 | rascunho |
| RT-AGD-007 | Bloqueio de agenda | 1 | rascunho |
| RT-AGD-008 | Empurrar agenda em massa | 1 | rascunho |
| RT-AGD-009 | Recorrência | 1 | rascunho |
| RT-AGD-010 | Fila de espera | 1 | rascunho |
| RT-AGD-011 | Grade de agenda (front) | 1 | rascunho |
| RT-AGD-012 | Painel do balcão (SSE) | 1 | rascunho |

## Dependências

`catalogo` (serviço, blocos, recursos) · `equipe` (jornada, exceções) · `cliente` (nome para
exibição). Três é o teto aceitável; uma quarta dependência exige revisar a fronteira.
