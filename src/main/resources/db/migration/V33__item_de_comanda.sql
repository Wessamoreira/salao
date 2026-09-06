-- RT-ATD-002 — item de serviço na comanda, com quem executou.

-- ---------------------------------------------------------------------------
-- A linha da comanda. É aqui que o serviço vira dinheiro.
--
-- `descricao` e `preco_unitario` são SNAPSHOT (RN-ATD-010): o nome e o preço
-- do momento da venda, não uma consulta ao catálogo. Sem isso, reprocessar um
-- fechamento de três meses atrás dá número diferente do repasse já pago, e
-- ninguém consegue explicar a diferença ao profissional.
--
-- A tabela nasce inteira, com a metade de PRODUTO que só RT-ATD-003 vai
-- preencher: é uma entidade só, e partir o DDL em duas migrations para a mesma
-- tabela deixaria o `check` de referência incompleto no meio do caminho.
-- ---------------------------------------------------------------------------
create table item_comanda (
  id                   uuid primary key default gen_random_uuid(),
  estabelecimento_id   uuid not null references estabelecimento(id),
  comanda_id           uuid not null references comanda(id),

  tipo                 text not null check (tipo in ('SERVICO', 'PRODUTO')),

  -- Sem FK: serviço, produto e agendamento são de outros módulos.
  servico_id           uuid,
  produto_id           uuid,

  -- De qual horário marcado veio este item. É aqui que a relação com o
  -- agendamento vive — na comanda ela seria mentira já no segundo item
  -- (RN-ATD-004).
  agendamento_id       uuid,

  descricao            text not null,
  quantidade           numeric(12,3) not null default 1 check (quantidade > 0),
  preco_unitario       numeric(19,4) not null check (preco_unitario >= 0),
  desconto             numeric(19,4) not null default 0 check (desconto >= 0),
  valor_total          numeric(19,4) not null check (valor_total >= 0),

  -- Só para produto, e só em RT-ATD-003: é o custo do lote que saiu, guardado
  -- para a margem não depender do custo de hoje.
  custo_medio_unitario numeric(19,4),

  criado_em            timestamptz not null,

  constraint item_tem_referencia check (
    (tipo = 'SERVICO' and servico_id is not null)
    or (tipo = 'PRODUTO' and produto_id is not null))
);

create index idx_item_da_comanda on item_comanda (estabelecimento_id, comanda_id);

-- ---------------------------------------------------------------------------
-- Quem executou, e quanto disso é dele.
--
-- Uma linha por pessoa no item: é o que resolve o assistente que lava o cabelo
-- (pergunta em aberto 6) sem inventar um segundo item.
--
-- `percentual_comissao` e `regra_origem` são SNAPSHOT. `regra_origem` existe
-- para responder "por que a minha comissão foi 35% e não 40%" — a pergunta que
-- o profissional faz três meses depois, quando a regra já mudou.
-- ---------------------------------------------------------------------------
create table executante (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),
  item_comanda_id     uuid not null references item_comanda(id) on delete cascade,

  profissional_id     uuid not null,
  papel               text not null check (papel in ('PRINCIPAL', 'ASSISTENTE')),

  percentual_comissao numeric(7,4) not null
                        check (percentual_comissao >= 0 and percentual_comissao <= 100),
  regra_origem        text not null,

  -- Derivados enquanto a comanda está aberta, congelados no fechamento: o
  -- desconto (RT-ATD-004) muda a base, e um valor gravado antes dele seria um
  -- número que a própria rotina seguinte invalida.
  base_calculo        numeric(19,4) not null,
  valor_comissao      numeric(19,4) not null,

  -- A mesma pessoa não entra duas vezes no mesmo item: seriam duas comissões
  -- pelo mesmo trabalho, e a segunda ninguém percebe.
  unique (item_comanda_id, profissional_id)
);

-- "Quanto a Ana fez neste período?" é a consulta do extrato (RT-FIN-004).
create index idx_executante_do_profissional
  on executante (estabelecimento_id, profissional_id);

select aplicar_rls_tenant('item_comanda');
select aplicar_rls_tenant('executante');
