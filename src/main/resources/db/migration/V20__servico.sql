-- RT-CAT-001 — serviço e seus blocos.

create table servico (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  nome               text not null,
  descricao          text,
  ativo              boolean not null default true,
  versao             integer not null default 0,
  criado_em          timestamptz not null default now(),
  criado_por         uuid,
  constraint servico_nome_nao_vazio check (length(trim(nome)) > 0)
);

-- Nome único por salão: dois "Escova" no cardápio fazem a recepção escolher o errado
-- metade das vezes, e o extrato de comissão fica dividido entre os dois.
create unique index idx_servico_nome
  on servico (estabelecimento_id, lower(nome));

create index idx_servico_ativo on servico (estabelecimento_id, nome) where ativo;

-- ---------------------------------------------------------------------------
-- Um serviço é uma SEQUÊNCIA de blocos, e é isto que distingue um sistema de
-- salão de uma agenda genérica.
--
-- Coloração não é "100 minutos". É: aplicar (30 min, profissional ocupada),
-- a química agir (40 min, profissional LIVRE, cliente esperando), lavar e
-- finalizar (30 min, ocupada de novo).
--
-- Uma agenda que trate isso como um bloco de 100 minutos joga fora 40 minutos
-- de trabalho por coloração — que num sábado é um horário inteiro de outra
-- cliente. É a diferença entre o sistema render dinheiro e não render.
-- ---------------------------------------------------------------------------
create table bloco_do_servico (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  servico_id         uuid not null references servico(id) on delete cascade,
  ordem              smallint not null check (ordem >= 0),
  tipo               text not null check (tipo in ('ATIVO', 'PAUSA')),
  duracao_minutos    smallint not null check (duracao_minutos > 0 and duracao_minutos <= 600),

  constraint bloco_ordem_unica unique (servico_id, ordem)
);

create index idx_bloco_do_servico on bloco_do_servico (servico_id, ordem);

select aplicar_rls_tenant('servico');
select aplicar_rls_tenant('bloco_do_servico');
