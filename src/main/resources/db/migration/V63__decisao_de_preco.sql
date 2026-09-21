-- RT-EST-011 — quem decidiu sobre a sugestão, o que decidiu, e com que informação.

-- ===========================================================================
-- A decisão, com os números congelados
--
-- Congelados de propósito: recalcular depois daria outro resultado, porque o custo já é outro. A
-- pergunta que a trilha responde é "com que informação esta decisão foi tomada", e não "o que seria
-- certo hoje".
--
-- Append-only. Uma decisão não se corrige: decide-se de novo na rodada seguinte.
-- ===========================================================================
create table decisao_de_preco (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),
  produto_id          uuid not null references produto(id) on delete cascade,

  decisao             text not null check (decisao in ('APLICADA', 'RECUSADA')),
  -- O motivo da SUGESTÃO, não da decisão. Mesma lista de sugestao_de_preco (V62).
  motivo              text not null check (motivo in
                        ('MARGEM_ABAIXO_DO_MINIMO', 'ENCALHADO', 'MARGEM_ALTA', 'PRECO_QUEBRADO')),

  preco_anterior      numeric(19, 4) check (preco_anterior >= 0),
  custo_medio         numeric(19, 4) not null check (custo_medio > 0),
  preco_sugerido      numeric(19, 4) not null check (preco_sugerido > 0),
  -- Pode diferir do sugerido: o dono ajusta antes de aplicar. Nulo quando recusada.
  preco_aplicado      numeric(19, 4) check (preco_aplicado > 0),

  racional            text not null check (char_length(racional) between 1 and 1000),
  -- O motivo escrito da recusa. Nulo quando aplicada.
  justificativa       text check (char_length(btrim(justificativa)) between 3 and 500),

  decidida_em         timestamptz not null,
  decidida_por        uuid,

  constraint aplicada_tem_preco
    check ((decisao = 'APLICADA') = (preco_aplicado is not null)),
  constraint recusada_tem_justificativa
    check ((decisao = 'RECUSADA') = (justificativa is not null))
);

-- A consulta do silêncio de 90 dias, feita a cada geração para cada produto (RN-EST-035).
create index idx_decisao_de_preco_do_produto
  on decisao_de_preco (estabelecimento_id, produto_id, decidida_em desc);

select aplicar_rls_tenant('decisao_de_preco');

-- RN-EST-036: a decisão não se reescreve nem se apaga. Mudou de ideia? Decide de novo na rodada
-- seguinte, e as duas ficam na trilha.
revoke update, delete on decisao_de_preco from salao_app;
