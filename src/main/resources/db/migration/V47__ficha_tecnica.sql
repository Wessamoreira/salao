-- RT-EST-007 — ficha técnica: o que cada serviço consome.

-- ---------------------------------------------------------------------------
-- POR QUE A FICHA MORA NO ESTOQUE, E NÃO NO CATÁLOGO.
--
-- Ela liga serviço a produto, então alguém tem de depender de alguém. No
-- catálogo, a tabela obrigaria aquele módulo a conhecer unidade de medida,
-- finalidade do produto e a regra de que só se consome o que é consumível —
-- conceitos de estoque migrando para onde não moram.
--
-- Aqui, o custo é uma chamada a `ServicoApi.porId` para conferir que o serviço
-- existe NESTE salão: exatamente o que `financeiro` já faz com o mesmo módulo
-- para a regra de comissão (RN-FIN-007). A conta, o FEFO e a validação de
-- unidade já estão neste lado.
-- ---------------------------------------------------------------------------
create table ficha_tecnica (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- Sem FK: serviço é de outro módulo (mesma razão de RN-EQP-015). Produto é
  -- deste, mas FK no PostgreSQL não separa tenant (RN-CAT-011) — quem confere é
  -- o caso de uso, pelas APIs dos donos do dado.
  servico_id         uuid not null,
  produto_id         uuid not null,

  -- Quanto o serviço consome, na unidade do produto. Positiva sempre: a ficha
  -- diz o que SAI, e o sinal é decidido na baixa.
  quantidade         numeric(12,3) not null check (quantidade > 0),

  criado_em          timestamptz not null,
  criado_por         uuid,

  -- Uma linha por par. Duas linhas do mesmo produto no mesmo serviço fariam a
  -- baixa dobrar sem ninguém pedir.
  constraint um_produto_por_servico unique (estabelecimento_id, servico_id, produto_id)
);

create index idx_ficha_do_servico on ficha_tecnica (estabelecimento_id, servico_id);

select aplicar_rls_tenant('ficha_tecnica');

comment on table ficha_tecnica is
  'RT-EST-007: quanto de cada insumo um serviço consome. É PREVISÃO — a baixa real sai no '
  'fechamento da comanda, com origem ITEM_COMANDA, e é revertida pela reabertura.';
