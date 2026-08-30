-- RT-INF-002 — camada 2 do isolamento: role da aplicação + RLS.

-- ---------------------------------------------------------------------------
-- RN-INF-004: a aplicação NÃO conecta como dona das tabelas.
-- Sem isto, a RLS é ignorada em silêncio para o owner e o isolamento é ficção.
-- O Flyway conecta como owner (precisa criar tabela e política); a aplicação,
-- como salao_app.
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- A role é criada SEM SENHA aqui, de propósito (RT-INF-012).
--
-- `alter role ... password 'x'` grava a senha dentro do próprio comando SQL. Com
-- log_statement = 'ddl' ou 'all' no Postgres, ela vai para o log do servidor em
-- texto claro — e log de banco costuma ser copiado, arquivado e lido por mais
-- gente do que quem tem acesso ao segredo.
--
-- A senha é definida FORA da migration, uma vez por ambiente, por quem provisiona
-- o banco. Ver docs/runbook/provisionar-banco.md.
-- ---------------------------------------------------------------------------
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'salao_app') then
    create role salao_app login;
  end if;
end
$$;

grant usage on schema public to salao_app;

-- ---------------------------------------------------------------------------
-- Aplica a política padrão de tenant a uma tabela.
-- Existe para que toda migration futura chame uma linha em vez de repetir cinco
-- comandos — repetição é onde alguém esquece um, e o esquecimento é silencioso.
--
-- nullif(..., '') é deliberado: com app.tenant_id ausente OU vazio o resultado é
-- NULL, a comparação vira NULL e NENHUMA linha passa. Falha fechada.
-- Sem o nullif, ''::uuid lançaria erro de cast e o modo de falha seria confuso.
-- ---------------------------------------------------------------------------
create or replace function aplicar_rls_tenant(nome_tabela text) returns void as $fn$
begin
  execute format('alter table %I enable row level security', nome_tabela);
  execute format('alter table %I force row level security', nome_tabela);
  execute format($p$
      create policy tenant_isolado on %I
        using       (estabelecimento_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        with check  (estabelecimento_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  $p$, nome_tabela);
  execute format('grant select, insert, update, delete on %I to salao_app', nome_tabela);
end;
$fn$ language plpgsql;

comment on function aplicar_rls_tenant(text) is
  'RT-INF-002: habilita RLS + FORCE + política de tenant + grant para salao_app.';
