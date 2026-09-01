-- RT-CAT-002 — preço com vigência e histórico.

-- ---------------------------------------------------------------------------
-- Preço NÃO é coluna de `servico`, e essa é a rotina inteira.
--
-- Com o preço na linha do serviço, aumentar a escova de 60 para 70 reescreve
-- o passado: o relatório de fevereiro passa a dizer 70, e a comissão já paga
-- deixa de bater com o extrato. Ninguém percebe, porque nada quebra.
--
-- Aqui cada preço é uma linha com a data em que passa a valer.
-- ---------------------------------------------------------------------------
create table preco_do_servico (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  servico_id         uuid not null references servico(id) on delete cascade,

  valor              numeric(19,4) not null check (valor >= 0),

  -- ---------------------------------------------------------------------
  -- Só a data de INÍCIO, nunca a de fim.
  --
  -- O modelo com `vigente_ate` obriga a manter duas linhas coerentes: ao
  -- cadastrar o preço novo, é preciso fechar o anterior. Uma falha no meio
  -- disso deixa dois preços vigentes ao mesmo tempo, ou nenhum — e a consulta
  -- passa a depender de qual linha o banco devolve primeiro.
  --
  -- Sem `vigente_ate`, o preço numa data é simplesmente o de maior
  -- `vigente_de` que já começou. A coerência é consequência da consulta, não
  -- de disciplina de escrita.
  --
  -- É `date` e não `timestamptz` porque o salão diz "a partir de 1º de março",
  -- não "a partir das 14h32". O dia é no fuso do estabelecimento (ADR-0009).
  -- ---------------------------------------------------------------------
  vigente_de         date not null,

  criado_em          timestamptz not null default now(),
  criado_por         uuid,

  -- Dois preços começando no mesmo dia seria ambiguidade pura: nenhuma regra
  -- diria qual vale.
  constraint preco_vigencia_unica unique (servico_id, vigente_de)
);

-- A consulta quente é "o preço deste serviço nesta data": desce pelo índice e
-- para na primeira linha.
create index idx_preco_vigencia
  on preco_do_servico (servico_id, vigente_de desc);

select aplicar_rls_tenant('preco_do_servico');
