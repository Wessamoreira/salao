# Runbook — reconstruir os índices de busca de cliente

## Quando

Depois de qualquer alteração no dicionário `unaccent` do servidor, e depois de uma restauração de
backup em servidor com versão diferente do PostgreSQL ou do `pg_trgm`.

## Por quê

`imutavel_unaccent` (V13) declara como `IMMUTABLE` uma função que o PostgreSQL considera `STABLE`.
É o contorno padrão e necessário: coluna gerada e índice exigem `IMMUTABLE`.

O risco que isso assume é real e vale enunciar: **se o dicionário mudar, a coluna `busca` e o
índice guardam o resultado antigo**. Nada quebra, nada avisa — a busca simplesmente deixa de achar
alguns clientes. Por isso a reconstrução é manual e está escrita aqui, em vez de ser confiada à
memória de quem operar.

Na prática o dicionário `unaccent` é estático: isto é uma apólice, não uma rotina.

## Como

```sql
-- 1. Recalcular a coluna gerada. O update vazio força a reavaliação da expressão.
alter table cliente alter column busca drop expression;
alter table cliente
  alter column busca type text
  using imutavel_unaccent(lower(nome || ' ' || coalesce(apelido, '')));
alter table cliente
  alter column busca
  set generated always as (imutavel_unaccent(lower(nome || ' ' || coalesce(apelido, '')))) stored;

-- 2. Reconstruir os índices sem travar o salão.
reindex index concurrently idx_cliente_busca;
reindex index concurrently idx_cliente_telefone_reverso;
```

`concurrently` é obrigatório em horário de funcionamento: sem ele o `REINDEX` toma lock de escrita
e a recepção para de conseguir cadastrar.

## Como verificar

```sql
-- Deve devolver a cliente, com e sem acento.
select nome from cliente where busca like '%goncalves%';
```

E `BuscaIndexadaIT` confirma, no build, que as consultas continuam usando os índices.
