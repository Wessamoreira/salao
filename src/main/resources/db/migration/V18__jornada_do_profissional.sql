-- RT-EQP-002 — jornada semanal do profissional.

-- Necessária para combinar `=` de uuid e smallint com `&&` de faixa no mesmo índice GiST.
create extension if not exists btree_gist;

-- ---------------------------------------------------------------------------
-- Um tipo de faixa para hora do dia.
--
-- O Postgres traz int4range, tsrange e daterange prontos, mas não um para
-- `time`. Sem ele, a exclusão de sobreposição teria de ser escrita sobre
-- minutos desde a meia-noite — números que ninguém lê num dump nem numa
-- consulta de investigação.
-- ---------------------------------------------------------------------------
create type faixa_de_hora as range (subtype = time);

-- ---------------------------------------------------------------------------
-- Uma linha por INTERVALO de trabalho, não uma linha por dia.
--
-- O modelo ingênuo é `inicio, fim, almoco_inicio, almoco_fim` na linha do dia.
-- Ele quebra na primeira profissional que faz três blocos — manhã, tarde depois
-- de um curso, noite —, na que tem dois intervalos, e na que trabalha só de
-- manhã às quartas. Aí alguém acrescenta `almoco2_inicio` e o modelo vira
-- folclore.
--
-- Almoço não é uma coisa: é a AUSÊNCIA de um intervalo de trabalho. Modelado
-- assim, "9h-13h e 14h-19h" é o caso normal, e não uma exceção com nome.
-- ---------------------------------------------------------------------------
create table jornada_profissional (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  profissional_id    uuid not null references profissional(id) on delete cascade,
  -- ISO 8601: 1 = segunda ... 7 = domingo. É o mesmo do java.time.DayOfWeek e o
  -- mesmo do `extract(isodow)` do Postgres — escolher outro esquema obrigaria a
  -- converter em toda fronteira, e é onde erro de um dia aparece.
  dia_semana         smallint not null check (dia_semana between 1 and 7),
  inicio             time not null,
  fim                time not null,

  -- Sem atravessar a meia-noite. Salão não abre às 22h para fechar às 2h, e
  -- permitir isso obrigaria toda consulta de disponibilidade a tratar o caso.
  constraint jornada_fim_depois_do_inicio check (fim > inicio),

  -- ---------------------------------------------------------------------
  -- Sobreposição é impossível, garantida pelo BANCO (inviolável nº 8).
  --
  -- Duas instâncias salvando a jornada ao mesmo tempo passariam por qualquer
  -- verificação em Java: as duas leem, as duas não veem conflito, as duas
  -- gravam. O EXCLUDE não tem esse buraco.
  --
  -- Primeiro uso de EXCLUDE no projeto; a agenda (RT-AGD-002) usa o mesmo
  -- mecanismo sobre timestamptz.
  -- ---------------------------------------------------------------------
  constraint jornada_sem_sobreposicao exclude using gist (
    profissional_id with =,
    dia_semana with =,
    faixa_de_hora(inicio, fim) with &&
  )
);

create index idx_jornada_profissional on jornada_profissional (profissional_id, dia_semana);

select aplicar_rls_tenant('jornada_profissional');
