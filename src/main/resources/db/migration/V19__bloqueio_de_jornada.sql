-- RT-EQP-003 — exceções de jornada: feriado, férias, curso, atestado.

create table bloqueio_de_jornada (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- ---------------------------------------------------------------------
  -- Nulo significa O SALÃO INTEIRO.
  --
  -- Feriado não é exceção de uma pessoa: no dia 25 ninguém atende. Modelar
  -- por profissional obrigaria a criar uma linha para cada um, e a esquecer
  -- de criar para quem foi contratado depois — o salão apareceria aberto
  -- para a profissional nova num feriado.
  -- ---------------------------------------------------------------------
  profissional_id    uuid references profissional(id) on delete cascade,

  motivo             text not null check (motivo in
                       ('FERIADO','FERIAS','CURSO','ATESTADO','FOLGA','OUTRO')),
  observacao         text,

  data_inicio        date not null,
  data_fim           date not null,

  -- ---------------------------------------------------------------------
  -- Hora nula é dia inteiro.
  --
  -- Férias são dias inteiros; curso costuma ser um pedaço de um dia. Os dois
  -- casos cabem aqui, e o terceiro — "das 14h às 18h todos os dias de uma
  -- quinzena" — é recusado pela restrição abaixo, porque ninguém pede isso e
  -- aceitar obrigaria toda consulta a tratar um caso que não existe.
  -- ---------------------------------------------------------------------
  hora_inicio        time,
  hora_fim           time,

  criado_em          timestamptz not null default now(),
  criado_por         uuid,

  constraint bloqueio_periodo_coerente check (data_fim >= data_inicio),
  constraint bloqueio_horas_juntas check (
    (hora_inicio is null and hora_fim is null)
    or (hora_inicio is not null and hora_fim is not null and hora_fim > hora_inicio)
  ),
  -- Bloqueio por hora vale para um dia só. Ver a nota acima.
  constraint bloqueio_por_hora_em_um_dia check (
    hora_inicio is null or data_inicio = data_fim
  )
);

-- ---------------------------------------------------------------------------
-- SEM exclusão de sobreposição, ao contrário da jornada (RN-EQP-007).
--
-- Lá, dois intervalos cruzados são erro de dado: o profissional não pode estar
-- em dois turnos ao mesmo tempo. Aqui, dois bloqueios cruzados são banais e
-- inofensivos — férias que englobam um feriado, atestado dentro de uma folga.
-- Os dois removem disponibilidade, e a soma dá o mesmo resultado.
--
-- Proibir obrigaria a recepção a apagar o feriado para lançar as férias.
-- ---------------------------------------------------------------------------
create index idx_bloqueio_periodo
  on bloqueio_de_jornada (estabelecimento_id, data_inicio, data_fim);
create index idx_bloqueio_profissional
  on bloqueio_de_jornada (profissional_id, data_inicio)
  where profissional_id is not null;

select aplicar_rls_tenant('bloqueio_de_jornada');
