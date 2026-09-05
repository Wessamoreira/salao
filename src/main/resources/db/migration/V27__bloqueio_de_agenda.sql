-- RT-AGD-007 — bloquear um pedaço da grade.

-- ---------------------------------------------------------------------------
-- Já existe `bloqueio_de_jornada` (RT-EQP-003), e este NÃO é o mesmo.
--
--   `bloqueio_de_jornada` (equipe)  molda QUANDO a pessoa trabalha: férias,
--                                   feriado, curso. É por data e hora de
--                                   parede, entra no cálculo de
--                                   `intervalosEm`, e é FORÇÁVEL — um admin
--                                   marca por cima com justificativa.
--
--   `bloqueio` (agenda)             OCUPA um pedaço da grade, exatamente como
--                                   um atendimento. É um instante a outro,
--                                   vale também para RECURSO, e NÃO é
--                                   forçável: o banco recusa.
--
-- A diferença que importa: "a Ana está em curso" é uma informação sobre a
-- agenda dela, e o dono pode decidir marcar assim mesmo. "O lavatório 2 está
-- quebrado" não é opinião — marcar por cima produziria uma cliente com a
-- cabeça ensaboada e nenhum lugar para lavar.
-- ---------------------------------------------------------------------------
create table bloqueio (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- Sem FK, como em `agendamento`: profissional é de `equipe`, recurso é de
  -- `catalogo`. Ver RN-EQP-015 e RN-CAT-011.
  profissional_id    uuid,
  recurso_id         uuid,
  unidade            smallint,

  motivo             text not null check (motivo in
                       ('ALMOCO','FERIAS','CURSO','FERIADO','PESSOAL','MANUTENCAO')),
  descricao          text,
  criado_em          timestamptz not null default now(),
  criado_por         uuid,

  -- Bloqueio que não bloqueia ninguém nem nada é linha órfã com aparência de
  -- regra: some da grade e ninguém entende por que o horário sumiu.
  constraint bloqueio_alveja_alguem check (profissional_id is not null or recurso_id is not null),
  constraint bloqueio_unidade_com_recurso check (
    (recurso_id is null and unidade is null) or (recurso_id is not null and unidade is not null))
);

-- ---------------------------------------------------------------------------
-- O bloqueio vira linha em `agendamento_bloco`, e é aqui que a rotina se paga.
--
-- Uma tabela própria com constraint espelhada teria DUAS exclusões para manter
-- coerentes, e a segunda seria a que ficasse para trás. Como linha do mesmo
-- bloco, o bloqueio disputa o profissional e o recurso pela MESMA constraint
-- que já protege os atendimentos: marcar por cima é recusado pelo banco, sem
-- uma linha de código a mais em RT-AGD-002 nem em RT-AGD-003.
-- ---------------------------------------------------------------------------
alter table agendamento_bloco
  alter column agendamento_id drop not null;

alter table agendamento_bloco
  add column bloqueio_id uuid references bloqueio(id) on delete cascade;

alter table agendamento_bloco
  drop constraint agendamento_bloco_tipo_check;

alter table agendamento_bloco
  add constraint agendamento_bloco_tipo_check
    check (tipo in ('ATIVO', 'PAUSA', 'BLOQUEIO'));

-- Uma linha pertence a um atendimento ou a um bloqueio — nunca aos dois, nunca
-- a nenhum. Sem isto, uma linha solta ocuparia a agenda sem que nada na tela
-- explicasse por quê.
alter table agendamento_bloco
  add constraint bloco_pertence_a_um_dono check (
    (agendamento_id is not null and bloqueio_id is null)
    or (agendamento_id is null and bloqueio_id is not null));

create index idx_bloco_do_bloqueio on agendamento_bloco (bloqueio_id)
  where bloqueio_id is not null;

-- ---------------------------------------------------------------------------
-- O bloqueio nasce com status 'CONFIRMADO' porque é o que o coloca dentro do
-- filtro das exclusion constraints — e porque é verdade: não existe bloqueio
-- "solicitado" à espera de aprovação. Quem desiste do bloqueio o APAGA, e o
-- `on delete cascade` leva a linha junto.
--
-- Apagar é certo aqui, ao contrário do agendamento: um bloqueio não sustenta
-- histórico nenhum. É a mesma decisão de RT-EQP-003 — "apaga-se o que não
-- sustenta nada".
-- ---------------------------------------------------------------------------
select aplicar_rls_tenant('bloqueio');
