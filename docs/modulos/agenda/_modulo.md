# Módulo `agenda`

- **Sigla:** AGD · **Fase de introdução:** 1 · **Status:** Fase 1 completa

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
| Consultar disponibilidade | `DisponibilidadeApi.consultar(data, servicoId, profissionalId)` — `profissionalId` nulo = qualquer um que faça | `conversacional`, front |
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
| [RT-AGD-001](RT-AGD-001-consultar-disponibilidade.md) | Consultar disponibilidade | 1 | **implementada** |
| [RT-AGD-002](RT-AGD-002-criar-agendamento.md) | Criar agendamento | 1 | **implementada** |
| [RT-AGD-003](RT-AGD-003-reagendar.md) | Reagendar | 1 | **implementada** |
| [RT-AGD-004](RT-AGD-004-confirmar.md) | Confirmar | 1 | **implementada** |
| [RT-AGD-005](RT-AGD-005-cancelar.md) | Cancelar | 1 | **implementada** |
| [RT-AGD-006](RT-AGD-006-no-show.md) | Registrar no-show | 1 | **implementada** |
| [RT-AGD-007](RT-AGD-007-bloqueio-de-grade.md) | Bloqueio de agenda | 1 | **implementada** |
| [RT-AGD-008](RT-AGD-008-empurrar-agenda.md) | Empurrar agenda em massa | 1 | **implementada** |
| [RT-AGD-009](RT-AGD-009-recorrencia.md) | Recorrência | 1 | **implementada** |
| [RT-AGD-010](RT-AGD-010-fila-de-espera.md) | Fila de espera | 1 | **parcial** (falta a chamada automática) |
| [RT-AGD-011](RT-AGD-011-grade.md) | Grade de agenda (front) | 1 | **implementada** |
| [RT-AGD-012](RT-AGD-012-painel.md) | Painel do balcão (SSE) | 1 | **implementada** |

## Dependências

**Fontes** — de onde a agenda lê para decidir:

`catalogo` (serviço, blocos, recursos) · `equipe` (jornada, exceções, habilidade, duração) ·
`cliente` (**só `existe`**, para validar o vínculo sem carregar dado pessoal).

Três é o teto, e ele foi atingido em RT-AGD-002. Uma quarta **fonte** exige revisar a fronteira:
cada uma é um lugar de onde a agenda precisa saber algo para tomar sua decisão, e mais de três
significa que a decisão não é dela.

**Destino** — para onde a agenda manda o que decidiu:

`notificacao` (`NotificacaoApi.enfileirar`), desde RT-NOT-002.

**A revisão que RT-NOT-002 exigiu.** À primeira vista é a quarta dependência e deveria disparar a
regra. Ela é de outra natureza, e a distinção vale ser escrita: uma fonte é algo de que a agenda
*depende para decidir*; um destino é algo a quem ela *conta o que decidiu*. O destino não participa
de nenhuma regra da agenda, não pode recusar nada, e trocá-lo por outro não muda uma linha de
lógica — o lembrete continua sendo devido no mesmo instante, mande quem mandar.

O teto de três continua valendo **para fontes**. Destinos são contados à parte, e a pergunta que os
limita é outra: quantos lugares precisam saber o que a agenda decidiu?

*(O sentido da seta importa: `notificacao` nunca chama `agenda`. Se um dia chamar, virou fonte e
conta no teto.)*

`iam` (`AuditoriaApi`, `EstabelecimentoApi`) não conta em nenhum dos dois: todo módulo depende dele.
