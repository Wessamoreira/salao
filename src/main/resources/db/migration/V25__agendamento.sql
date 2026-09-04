-- RT-AGD-001 — o esqueleto da agenda: onde os blocos ocupados são registrados.

-- ---------------------------------------------------------------------------
-- Estas tabelas nascem aqui, na rotina que só as LÊ.
--
-- "Disponibilidade" é jornada menos bloqueios menos BLOCOS OCUPADOS, e a
-- terceira parcela não existe sem elas. Entregar a consulta sobre uma agenda
-- que não pode ter nada marcado seria entregar metade e chamar de rotina.
-- Quem escreve é RT-AGD-002.
-- ---------------------------------------------------------------------------
create table agendamento (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),

  -- ---------------------------------------------------------------------
  -- Sem chave estrangeira para cliente, profissional e serviço: os três são
  -- de outros módulos. É a mesma decisão de RN-EQP-015, pelo mesmo motivo —
  -- uma FK costuraria os esquemas e deixaria um `on delete` de um módulo
  -- mexer em dado do outro. E, por RN-CAT-011, ela também não separaria os
  -- salões: as checagens de integridade referencial do PostgreSQL não passam
  -- pela RLS.
  --
  -- Quem confere é o caso de uso, via as APIs dos módulos.
  -- ---------------------------------------------------------------------
  cliente_id          uuid not null,
  profissional_id     uuid not null,
  servico_id          uuid not null,

  status              text not null check (status in
                        ('SOLICITADO','CONFIRMADO','EM_ATENDIMENTO','CONCLUIDO',
                         'CANCELADO','NO_SHOW')),

  -- O span total, para listagem e índice. A verdade sobre ocupação está nos
  -- blocos: aqui a coloração são 100 minutos, lá são 30 ocupados, 40 livres e
  -- 30 ocupados.
  inicio              timestamptz not null,
  fim                 timestamptz not null,

  origem              text not null check (origem in
                        ('WEB','WHATSAPP','RECORRENCIA','PAINEL')),
  recorrencia_id      uuid,
  observacao          text,
  motivo_cancelamento text,
  versao              integer not null default 0,
  criado_em           timestamptz not null default now(),
  criado_por          uuid,

  constraint agendamento_periodo_valido check (fim > inicio)
);

create index idx_agendamento_do_dia
  on agendamento (estabelecimento_id, inicio);

create index idx_agendamento_do_profissional
  on agendamento (profissional_id, inicio);

-- ---------------------------------------------------------------------------
-- O bloco é onde a ocupação mora, e é o que faz a agenda de um salão render.
--
-- `profissional_id` nulo = o bloco NÃO ocupa o profissional. É a pausa da
-- química: a cliente está no salão, a profissional está livre para um encaixe.
-- `recurso_id` nulo = não disputa equipamento.
--
-- Os dois anuláveis, e é essa combinação que permite dizer "a cadeira está
-- ocupada e a profissional não". Uma janela única por agendamento obrigaria a
-- escolher qual dos dois estaria errado.
-- ---------------------------------------------------------------------------
create table agendamento_bloco (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),
  agendamento_id      uuid not null references agendamento(id) on delete cascade,
  ordem               smallint not null check (ordem >= 0),
  tipo                text not null check (tipo in ('ATIVO','PAUSA')),

  profissional_id     uuid,
  recurso_id          uuid,

  -- ---------------------------------------------------------------------
  -- Qual das `quantidade` unidades do recurso (RT-CAT-003).
  --
  -- É o que faz a CAPACIDADE ser garantida pelo banco. `recurso` é contável
  -- ("Lavatório, quantidade 3"), e EXCLUDE garante "no máximo um", não "no
  -- máximo três": sem a unidade, a capacidade dependeria de uma contagem da
  -- aplicação — verificação sob concorrência sem trava, que este projeto não
  -- aceita em lugar nenhum.
  -- ---------------------------------------------------------------------
  unidade             smallint check (unidade is null or unidade >= 1),

  periodo             tstzrange not null,

  -- Desnormalizado da cabeça, e é dívida conhecida: a constraint parcial
  -- precisa do status na PRÓPRIA linha, porque o Postgres não aceita subquery
  -- em EXCLUDE. Consequência: toda transição de status precisa atualizar os
  -- blocos na mesma transação (RN-AGD-006).
  status              text not null,

  constraint bloco_periodo_nao_vazio check (not isempty(periodo)),
  constraint bloco_unidade_com_recurso check (
    (recurso_id is null and unidade is null) or (recurso_id is not null and unidade is not null))
);

create index idx_bloco_do_agendamento on agendamento_bloco (agendamento_id, ordem);

-- A consulta quente de RT-AGD-001: "o que ocupa este profissional neste dia?"
create index idx_bloco_por_profissional
  on agendamento_bloco using gist (profissional_id, periodo)
  where profissional_id is not null;

create index idx_bloco_por_recurso
  on agendamento_bloco using gist (recurso_id, periodo)
  where recurso_id is not null;

-- ---------------------------------------------------------------------------
-- O banco é a fonte da verdade sobre sobreposição. A aplicação valida por
-- educação — para dar erro amigável antes de gravar —, mas trava de aplicação
-- não resiste a duas instâncias concorrentes.
--
-- O filtro por status é o que faz um agendamento cancelado devolver o horário.
-- ---------------------------------------------------------------------------
alter table agendamento_bloco
  add constraint bloco_sem_sobreposicao_profissional
  exclude using gist (estabelecimento_id with =, profissional_id with =, periodo with &&)
  where (profissional_id is not null
         and status in ('SOLICITADO','CONFIRMADO','EM_ATENDIMENTO'));

alter table agendamento_bloco
  add constraint bloco_sem_sobreposicao_recurso
  exclude using gist (estabelecimento_id with =, recurso_id with =, unidade with =,
                      periodo with &&)
  where (recurso_id is not null
         and status in ('SOLICITADO','CONFIRMADO','EM_ATENDIMENTO'));

select aplicar_rls_tenant('agendamento');
select aplicar_rls_tenant('agendamento_bloco');

-- ---------------------------------------------------------------------------
-- A grade em que os horários são oferecidos.
--
-- Precisa ser do salão: uns marcam de 15 em 15 minutos, outros de 30, e salão
-- de barbearia costuma trabalhar de 10. Fixar um número no código estaria
-- errado para metade deles — e o efeito não seria um erro, seria a agenda
-- oferecendo horários que aquele salão não usa.
-- ---------------------------------------------------------------------------
alter table estabelecimento
  add column granularidade_agenda_minutos smallint not null default 15
    check (granularidade_agenda_minutos between 5 and 60);

comment on column estabelecimento.granularidade_agenda_minutos is
  'RT-AGD-001: de quantos em quantos minutos a disponibilidade oferece horário.';
