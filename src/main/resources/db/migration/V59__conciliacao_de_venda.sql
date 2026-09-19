-- RT-FIN-010 — casar as vendas da Stone com os pagamentos do salão.

-- ===========================================================================
-- O período que cada importação cobre
--
-- "Pagamento no cartão sem venda na maquininha" só é divergência onde há extrato. Um mês ainda não
-- importado não é problema do salão — é falta de arquivo. Primeira e última venda lida, no dia do salão.
-- Nulo nas importações anteriores a esta migration e nas que não leram venda nenhuma.
-- ===========================================================================
alter table importacao_de_extrato
  add column periodo_de  date,
  add column periodo_ate date,
  add constraint periodo_inteiro check ((periodo_de is null) = (periodo_ate is null)),
  add constraint periodo_em_ordem check (periodo_de is null or periodo_de <= periodo_ate);

-- ===========================================================================
-- O par: um pagamento do salão e uma venda da adquirente
-- ===========================================================================
create table conciliacao_de_venda (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),

  -- O estorno da comanda apaga o pagamento (RT-FIN-001); o par vai junto, e a venda volta a "só na
  -- maquininha" — que é a verdade depois do estorno.
  pagamento_id        uuid not null references pagamento(id) on delete cascade,
  transacao_id        uuid not null references transacao_do_adquirente(id),

  criterio            text not null check (criterio in ('NSU', 'AUTORIZACAO', 'VALOR_E_HORA')),
  -- Vazio: bate. Senão, o que difere: VALOR, FORMA, BANDEIRA, PARCELAS, CANCELADA_NA_ADQUIRENTE.
  divergencias        jsonb not null check (jsonb_typeof(divergencias) = 'array'),
  casada_em           timestamptz not null,

  -- No máximo um par de cada lado: rodar de novo, ou duas rodadas juntas, não casam nada duas vezes.
  constraint um_par_por_pagamento unique (pagamento_id),
  constraint um_par_por_venda unique (transacao_id)
);

create index idx_conciliacao_de_venda_do_salao on conciliacao_de_venda (estabelecimento_id);

select aplicar_rls_tenant('conciliacao_de_venda');

-- O par não se reescreve nem se desfaz pela aplicação — desfazer com motivo é RT-FIN-011. Só as
-- divergências crescem: a venda cancelada na adquirente depois de casada.
revoke update, delete on conciliacao_de_venda from salao_app;
grant update (divergencias) on conciliacao_de_venda to salao_app;
