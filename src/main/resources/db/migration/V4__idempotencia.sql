-- RT-INF-005 — idempotência de escrita.

-- ---------------------------------------------------------------------------
-- Terceira role, com um trabalho só: manutenção.
--
-- Purga de idempotência, expurgo do outbox e retenção de auditoria são
-- operações legitimamente CROSS-TENANT. Sem uma role para elas, sobram três
-- saídas, todas ruins: dar poder cross-tenant a salao_app (que é o que a RLS
-- existe para impedir), rodar manutenção como owner (o que tornaria o FORCE
-- decorativo em toda tabela), ou controlar por um GUC de sessão como
-- app.manutencao — que qualquer conexão pode definir, inclusive a da aplicação.
--
-- Com a role, a permissão é de quem se conecta, não de quem lembra de setar
-- uma variável. salao_app nunca casa com a policy, então nunca a alcança.
-- ---------------------------------------------------------------------------
-- Sem senha aqui, pela mesma razão de V2: ela apareceria no log do servidor.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'salao_manutencao') then
    create role salao_manutencao login;
  end if;
end
$$;

grant usage on schema public to salao_manutencao;

create or replace function permitir_manutencao(nome_tabela text) returns void as $fn$
begin
  execute format($p$
      create policy manutencao on %I
        to salao_manutencao
        using (true)
  $p$, nome_tabela);
  execute format('grant select, delete on %I to salao_manutencao', nome_tabela);
end;
$fn$ language plpgsql;

comment on function permitir_manutencao(text) is
  'RT-INF-005: libera a tabela para a role de manutenção (purga e retenção).';

-- ---------------------------------------------------------------------------
create table idempotencia (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  -- Escopo é a operação. Sem ele, a mesma chave em endpoints diferentes
  -- colidiria, e o cliente receberia a resposta de outra operação.
  escopo             text not null,
  chave              text not null,
  -- SHA-256 do payload canônico. Mesma chave com payload diferente é erro do
  -- cliente, não repetição — e precisa ser detectado, não reexecutado.
  hash_payload       text not null,
  tipo_resposta      text,
  corpo_resposta     jsonb,
  status_http        integer,
  criado_em          timestamptz not null default now(),
  expira_em          timestamptz not null,
  constraint idempotencia_unica unique (estabelecimento_id, escopo, chave)
);

-- A unique acima é o árbitro da concorrência, não um detalhe de integridade.
-- Duas requisições simultâneas com a mesma chave: a segunda BLOQUEIA no índice
-- até a primeira commitar, e então enxerga a resposta gravada. Mesma filosofia
-- da exclusion constraint da agenda — a aplicação valida por UX, o banco garante.

create index idx_idempotencia_expiracao on idempotencia (expira_em);

select aplicar_rls_tenant('idempotencia');
select permitir_manutencao('idempotencia');

-- Auditoria também tem retenção prometida (5 anos / 1 ano, ver 04-modelo-de-dados).
select permitir_manutencao('auditoria');
