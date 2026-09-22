-- Correção descoberta no teste de restore de RT-INF-009 (22/09/2026).
--
-- ---------------------------------------------------------------------------
-- O dump restaurava sem a tabela `cliente` — e ninguém saberia
--
-- `imutavel_unaccent` (V13) chamava `unaccent('unaccent', $1)` sem qualificar o
-- schema. Funciona no dia a dia, porque `public` está no search_path da sessão.
-- Não funciona no `pg_restore`, que roda com `search_path` vazio de propósito
-- (proteção contra sequestro de nome durante a restauração).
--
-- Como a função é inlineada dentro da coluna gerada `cliente.busca`, o
-- `CREATE TABLE cliente` falhava inteiro:
--
--   ERROR: function unaccent(unknown, text) does not exist
--   CONTEXT: SQL function "imutavel_unaccent" during inlining
--
-- E o pg_restore segue em frente: a restauração terminava com "errors ignored"
-- e um banco SEM a tabela de clientes, sem agendamento nenhum apontando para
-- ela, e sem nada parecer quebrado até alguém abrir a tela de clientes.
--
-- O `regdictionary` também é qualificado: 'unaccent' sozinho é resolvido pelo
-- search_path na hora da chamada, e cairia no mesmo buraco.
-- ---------------------------------------------------------------------------
create or replace function imutavel_unaccent(text)
  returns text
  language sql
  immutable
  strict
  parallel safe
as $$
  select public.unaccent('public.unaccent'::regdictionary, $1)
$$;

-- Sem `set search_path` na função de propósito: função com SET não é inlineada,
-- e é o inlining que faz a coluna gerada e o índice trigram continuarem baratos.
-- Qualificar o nome resolve o mesmo problema sem esse preço.
