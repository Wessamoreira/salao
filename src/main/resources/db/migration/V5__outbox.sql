-- RT-INF-006 — outbox transacional (registro de publicação do Spring Modulith).
--
-- O DDL é o schema v2 do spring-modulith-events-jdbc 2.1.x, copiado para cá de
-- propósito: schema deste projeto nasce em migration versionada, nunca de um
-- inicializador automático em runtime (spring.modulith.events.jdbc
-- .schema-initialization.enabled=false). Ao subir a versão do Modulith, conferir
-- se o schema mudou — ele já mudou uma vez, de v1 para v2, e usar o antigo
-- quebra em runtime, não no build.

create table if not exists event_publication (
  id                     uuid not null,
  listener_id            text not null,
  event_type             text not null,
  serialized_event       text not null,
  publication_date       timestamptz not null,
  completion_date        timestamptz,
  status                 text,
  completion_attempts    int,
  last_resubmission_date timestamptz,
  primary key (id)
);
create index if not exists event_publication_serialized_event_hash_idx
  on event_publication using hash (serialized_event);
create index if not exists event_publication_by_completion_date_idx
  on event_publication (completion_date);

create table if not exists event_publication_archive (
  id                     uuid not null,
  listener_id            text not null,
  event_type             text not null,
  serialized_event       text not null,
  publication_date       timestamptz not null,
  completion_date        timestamptz,
  status                 text,
  completion_attempts    int,
  last_resubmission_date timestamptz,
  primary key (id)
);
create index if not exists event_publication_archive_serialized_event_hash_idx
  on event_publication_archive using hash (serialized_event);
create index if not exists event_publication_archive_by_completion_date_idx
  on event_publication_archive (completion_date);

-- ---------------------------------------------------------------------------
-- SEM estabelecimento_id e SEM RLS — decisão consciente, não esquecimento.
--
-- O outbox é infraestrutura, como flyway_schema_history. Duas consequências
-- concretas de ligar RLS aqui:
--   1. o reenvio de publicações pendentes precisa enxergar mais de um tenant,
--      e sob RLS ele enxergaria zero;
--   2. a estrutura é do Modulith, que não conhece nem nunca vai preencher
--      uma coluna de tenant.
--
-- O preço: serialized_event guarda o payload do evento, e ele atravessa
-- estabelecimentos nesta tabela. Daí a RN-INF-009 — evento carrega ID, nunca
-- PII. Nome de cliente, telefone ou ficha aqui seria dado sensível fora do
-- perímetro da RLS.
--
-- O SchemaIT lista as duas tabelas como infraestrutura, com essa justificativa.
-- ---------------------------------------------------------------------------
grant select, insert, update, delete on event_publication to salao_app;
grant select, insert, update, delete on event_publication_archive to salao_app;

-- Manutenção lê para métrica e apaga arquivo vencido. Sem policy: não há RLS
-- nestas tabelas, então permitir_manutencao() criaria uma policy inerte e
-- enganosa — quem lesse acharia que há isolamento aqui.
grant select, delete on event_publication to salao_manutencao;
grant select, delete on event_publication_archive to salao_manutencao;

-- O reenviador percorre estabelecimento por estabelecimento e precisa listá-los.
-- Policy explícita em vez de permitir_manutencao(): aquela concede delete, e
-- manutenção não tem o que apagar na raiz do tenant.
create policy manutencao on estabelecimento to salao_manutencao using (true);
grant select on estabelecimento to salao_manutencao;
