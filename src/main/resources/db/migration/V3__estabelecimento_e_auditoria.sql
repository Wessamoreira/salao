-- RT-INF-002 — a raiz do tenant e a trilha de auditoria.
-- Ambas são fundacionais: a RLS precisa de uma raiz, e todo módulo audita.
-- RT-IAM-001 estende estabelecimento; RT-IAM-008 estende auditoria.

create table estabelecimento (
  id                       uuid primary key default gen_random_uuid(),
  nome                     text not null,
  documento                text,
  -- ADR-0009: fuso POR ESTABELECIMENTO. Constante no código contradiz o multi-tenant.
  timezone                 text not null default 'America/Sao_Paulo',
  moeda                    char(3) not null default 'BRL',
  -- Perguntas 1, 2 e 5 viraram configuração, e por isso deixaram de bloquear a Fase 2.
  base_comissao            text not null default 'BRUTO'
                             check (base_comissao in ('BRUTO', 'LIQUIDO')),
  desconto_afeta_comissao  boolean not null default false,
  periodicidade_fechamento text not null default 'MENSAL'
                             check (periodicidade_fechamento in ('SEMANAL','QUINZENAL','MENSAL')),
  ativo                    boolean not null default true,
  criado_em                timestamptz not null default now()
);

-- A raiz não tem estabelecimento_id: ela É o tenant. A política compara o próprio id.
alter table estabelecimento enable row level security;
alter table estabelecimento force row level security;
create policy tenant_isolado on estabelecimento
  using      (id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (id = nullif(current_setting('app.tenant_id', true), '')::uuid);
grant select, insert, update on estabelecimento to salao_app;

-- Provisionar um estabelecimento novo é a única operação legitimamente cross-tenant
-- do sistema. Fica com o owner (ou função SECURITY DEFINER) em RT-IAM-001 — não se
-- resolve afrouxando esta política.

create table auditoria (
  id                 bigserial primary key,
  estabelecimento_id uuid not null references estabelecimento(id),
  ocorrido_em        timestamptz not null default now(),
  usuario_id         uuid,
  ator               text not null check (ator in ('USUARIO','BOT','SISTEMA')),
  acao               text not null,
  entidade           text not null,
  entidade_id        uuid,
  antes              jsonb,
  depois             jsonb,
  ip                 inet,
  user_agent         text,
  trace_id           text
);

create index idx_auditoria_tenant_data on auditoria (estabelecimento_id, ocorrido_em desc);
create index idx_auditoria_entidade on auditoria (estabelecimento_id, entidade, entidade_id);

select aplicar_rls_tenant('auditoria');

-- Append-only: nada de UPDATE ou DELETE em trilha de auditoria. A permissão é a garantia,
-- não a convenção — aplicar_rls_tenant concede os quatro, então revogamos dois.
revoke update, delete on auditoria from salao_app;

grant usage, select on all sequences in schema public to salao_app;
