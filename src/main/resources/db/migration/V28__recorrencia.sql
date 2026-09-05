-- RT-AGD-009 — "a Marilda vem toda quinta às 14h".

-- ---------------------------------------------------------------------------
-- A recorrência é a REGRA; os agendamentos que ela gera são linhas de verdade
-- (`agendamento.recorrencia_id`, que já existe desde V25).
--
-- Materializar, e não calcular na hora, por três razões:
--
--   1. A exclusion constraint precisa de LINHAS. Uma ocorrência virtual não
--      impede ninguém de marcar por cima dela — e a cliente semanal seria
--      justamente a que perderia o horário.
--   2. Cada ocorrência é cancelável e movível sozinha. A Marilda vai viajar na
--      terceira quinta: isso é um cancelamento, não uma exceção na regra.
--   3. A grade lê linhas. Misturar agendamentos reais com ocorrências
--      calculadas faria toda consulta da agenda ter dois caminhos.
--
-- O preço é o horizonte: não dá para materializar até o infinito. Hoje a
-- recepção diz quantas, e está registrado como pendência o horizonte rolante.
-- ---------------------------------------------------------------------------
create table recorrencia (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- Sem FK, como em `agendamento`: os três são de outros módulos.
  cliente_id         uuid not null,
  profissional_id    uuid not null,
  servico_id         uuid not null,

  frequencia         text not null check (frequencia in ('SEMANAL', 'QUINZENAL')),

  -- O primeiro horário da série, em instante. As demais saem dele somando
  -- semanas — no FUSO do salão, não em UTC: somar 168 horas atravessa o
  -- horário de verão e a cliente chega uma hora errada.
  primeiro_inicio    timestamptz not null,

  ativa              boolean not null default true,
  encerrada_em       timestamptz,
  criado_em          timestamptz not null default now(),
  criado_por         uuid
);

create index idx_recorrencia_ativa
  on recorrencia (estabelecimento_id, cliente_id) where ativa;

-- "Quais agendamentos vieram desta série?" é a consulta de encerrar a série e
-- a de mostrar a tarja de recorrente na grade.
create index idx_agendamento_da_recorrencia
  on agendamento (recorrencia_id) where recorrencia_id is not null;

select aplicar_rls_tenant('recorrencia');
