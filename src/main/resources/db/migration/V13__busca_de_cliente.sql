-- RT-CLI-002 — busca de cliente por nome, apelido e dígitos do telefone.

create extension if not exists pg_trgm;
create extension if not exists unaccent;

-- ---------------------------------------------------------------------------
-- unaccent() de um argumento é STABLE, não IMMUTABLE: ela resolve o dicionário
-- pelo search_path, que pode mudar entre sessões. Coluna gerada e índice exigem
-- IMMUTABLE, então a forma de dois argumentos — com o dicionário nomeado — é
-- envolvida aqui.
--
-- O risco que isto assume, declarado: se o dicionário `unaccent` for alterado no
-- servidor, o índice fica desatualizado em silêncio. Mitigação em
-- docs/runbook/reindexar-busca.md — e o dicionário é estático na prática.
--
-- A alternativa seria normalizar em Java. Foi recusada: a MESMA normalização
-- precisa valer para o que está guardado e para o termo digitado, e mantê-la em
-- dois lugares garante que um dia divirjam — no dia em que divergissem, a busca
-- simplesmente pararia de achar gente, sem erro nenhum.
-- ---------------------------------------------------------------------------
create or replace function imutavel_unaccent(text)
  returns text
  language sql
  immutable
  strict
  parallel safe
as $$
  select unaccent('unaccent', $1)
$$;

-- Nome e apelido no mesmo campo: quem procura "ju" quer achar tanto "Juliana"
-- quanto a "Ju" do apelido, e não sabe (nem deveria saber) em qual coluna o
-- salão registrou isso.
alter table cliente
  add column busca text
    generated always as (
      imutavel_unaccent(lower(nome || ' ' || coalesce(apelido, '')))
    ) stored;

-- Parcial pelo mesmo predicado que a consulta usa. Sem isso o planejador pode
-- preferir um índice parcial qualquer só para resolver `anonimizado_em is null`
-- e aplicar o LIKE como filtro sobre tudo — foi o que ele fez, medido com
-- EXPLAIN em BuscaIndexadaIT.
create index idx_cliente_busca on cliente using gin (busca gin_trgm_ops)
  where anonimizado_em is null;

-- ---------------------------------------------------------------------------
-- Telefone: índice sobre o número INVERTIDO.
--
-- A busca do balcão é por SUFIXO ("os quatro últimos"), e um btree comum ordena
-- pelo começo — que no telefone é o país e o DDD, iguais para todo o salão.
-- Invertendo, o sufixo vira prefixo e o btree serve exatamente.
--
-- Trigram foi tentado aqui primeiro e é a ferramenta errada: trigrama de dígito
-- tem seletividade péssima, porque todo número do salão compartilha os mesmos
-- pedacinhos. Medido — o planejador preferia varrer a tabela.
--
-- `text_pattern_ops` é obrigatório: sem ele o índice usa a collation do banco e
-- não pode servir `LIKE 'prefixo%'`.
-- ---------------------------------------------------------------------------
create index idx_cliente_telefone_reverso
  on cliente (reverse(telefone) text_pattern_ops)
  where telefone is not null and anonimizado_em is null;
