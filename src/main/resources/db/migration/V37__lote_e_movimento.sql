-- RT-EST-002 — entrada de lote com custo e validade.

-- ---------------------------------------------------------------------------
-- O lote é a CAIXA que chegou: um custo, uma validade, um código impresso.
--
-- Duas caixas do mesmo shampoo compradas com dois meses de diferença custam
-- diferente e vencem diferente. Guardar só "quantidade" e "custo" no produto
-- perderia as duas coisas — e é justamente delas que dependem o FEFO (sai
-- primeiro o que vence primeiro) e a margem de cada venda.
--
-- FK para produto porque os dois são do MESMO módulo. Cross-module é que não
-- tem FK (RN-EQP-015); dentro do módulo ela é a garantia mais barata que
-- existe.
-- ---------------------------------------------------------------------------
create table lote (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  produto_id         uuid not null references produto(id),

  -- O código impresso na caixa. É por ele que um recall do fabricante
  -- encontra o que precisa sair de circulação.
  codigo             text,

  custo_unitario     numeric(19,4) not null check (custo_unitario >= 0),

  -- Nula quando o produto não tem validade impressa. O índice do FEFO usa
  -- `nulls last`: o que não vence sai depois do que vence.
  validade           date,

  recebido_em        date not null,
  criado_em          timestamptz not null,
  criado_por         uuid
);

-- ---------------------------------------------------------------------------
-- O índice do FEFO. É ele que faz "sai primeiro o que vence primeiro" ser uma
-- consulta, e não um laço na aplicação carregando todos os lotes.
-- ---------------------------------------------------------------------------
create index idx_lote_fefo
  on lote (estabelecimento_id, produto_id, validade nulls last, recebido_em);

-- ---------------------------------------------------------------------------
-- MOVIMENTO É IMUTÁVEL; SALDO É PROJEÇÃO.
--
-- Mesma disciplina do livro-razão: não existe coluna `saldo` para alguém
-- atualizar. O saldo de um lote é `sum(quantidade)` dos movimentos dele — e
-- por isso ele nunca "desanda": não há gravação concorrente que possa perder
-- uma baixa, nem valor errado que precise ser recalculado com um script.
--
-- O SINAL é o que distingue entrada de saída. Uma coluna `tipo` sozinha
-- exigiria que toda soma soubesse quais tipos somam e quais subtraem — e a
-- primeira consulta que esquecesse disso daria um número plausível e errado.
--
-- A tabela nasce inteira, com os tipos que só RT-EST-004 e RT-EST-005 vão
-- escrever: é uma entidade só, e partir o `check` em duas migrations deixaria
-- a lista de tipos incompleta no meio do caminho.
-- ---------------------------------------------------------------------------
create table movimento_estoque (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  produto_id         uuid not null references produto(id),
  lote_id            uuid not null references lote(id),

  tipo               text not null check (tipo in
                       ('ENTRADA', 'VENDA', 'CONSUMO_INTERNO', 'PERDA', 'AJUSTE',
                        'DEVOLUCAO')),

  -- Positiva entra, negativa sai. Nunca zero: movimento de zero não move nada
  -- e só polui a projeção.
  quantidade         numeric(12,3) not null check (quantidade <> 0),

  -- De onde veio: comanda, inventário, nota. Sem FK — origem atravessa módulo.
  origem_tipo        text,
  origem_id          uuid,

  -- SNAPSHOT do custo com que a unidade se moveu. Na saída, é o custo do lote
  -- de onde ela saiu — e é ele que faz a margem de uma venda antiga não mudar
  -- quando a próxima caixa chegar mais cara.
  custo_unitario     numeric(19,4) not null check (custo_unitario >= 0),

  observacao         text,
  criado_em          timestamptz not null,
  criado_por         uuid
);

-- "Quanto ainda há neste lote?" — a projeção do saldo (RT-EST-003) e o FEFO.
create index idx_movimento_do_lote
  on movimento_estoque (estabelecimento_id, lote_id);

-- "O que aconteceu com este produto?" — o extrato de estoque.
create index idx_movimento_do_produto
  on movimento_estoque (estabelecimento_id, produto_id, criado_em desc);

select aplicar_rls_tenant('lote');
select aplicar_rls_tenant('movimento_estoque');
