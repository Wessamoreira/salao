-- RT-FIN-001 — pagamento da comanda: 1:N, com split.

-- ---------------------------------------------------------------------------
-- UMA comanda, VÁRIOS pagamentos, deliberadamente.
--
-- "Metade no cartão e metade no pix" é o caso comum do balcão, não a exceção.
-- Um campo `forma_pagamento` na comanda obrigaria a escolher uma, e o salão
-- registraria a maior — perdendo a taxa da maquininha da outra metade, que é
-- justamente o número que o dono quer ver no fim do mês.
--
-- E a conciliação (ADR-0007) casa PAGAMENTO a pagamento, nunca comanda a
-- comanda: o extrato do adquirente traz uma linha por transação.
-- ---------------------------------------------------------------------------
create table pagamento (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),

  -- Sem FK: `comanda` é do módulo atendimento (RN-EQP-015). O modelo em
  -- 04-modelo-de-dados desenhou a FK antes de a regra de fronteira estar
  -- aplicada de ponta a ponta — e ela também não separaria tenant, porque as
  -- checagens de integridade referencial do PostgreSQL não passam pela RLS
  -- (RN-CAT-011).
  comanda_id          uuid not null,

  forma               text not null check (forma in
                        ('DINHEIRO', 'PIX', 'CREDITO', 'DEBITO', 'TRANSFERENCIA',
                         'CORTESIA', 'FIADO')),

  valor               numeric(19,4) not null check (valor > 0),

  -- Dados da maquininha. Nulos em dinheiro e pix, e é o que o matching da
  -- conciliação usa quando existirem.
  bandeira            text,
  parcelas            smallint not null default 1 check (parcelas >= 1),
  nsu                 text,
  autorizacao         text,

  -- ---------------------------------------------------------------------
  -- Quanto o adquirente vai reter e quando o dinheiro cai.
  --
  -- Nulos por enquanto: não há tabela de taxas nem contrato de adquirente
  -- cadastrado (Fase 5). As colunas existem desde já porque o fechamento do
  -- período (RT-FIN-006) precisa saber a diferença entre o que foi cobrado e
  -- o que entra na conta — e essa diferença é o que faz o caixa do dono não
  -- bater com o faturamento.
  -- ---------------------------------------------------------------------
  taxa_prevista       numeric(19,4) check (taxa_prevista >= 0),
  previsao_recebimento date,

  status_conciliacao  text not null default 'PENDENTE' check (status_conciliacao in
                        ('PENDENTE', 'CONCILIADO', 'DIVERGENTE', 'NAO_APLICAVEL')),

  criado_em           timestamptz not null,
  criado_por          uuid
);

-- "O que já foi pago nesta comanda?" é a pergunta da tela de fechamento, feita
-- a cada forma que a recepção acrescenta (R-UX-08).
create index idx_pagamento_da_comanda on pagamento (estabelecimento_id, comanda_id);

-- O que a conciliação vai varrer (Fase 5): pagamento de cartão ainda sem par.
create index idx_pagamento_a_conciliar
  on pagamento (estabelecimento_id, criado_em)
  where status_conciliacao = 'PENDENTE' and forma in ('CREDITO', 'DEBITO');

select aplicar_rls_tenant('pagamento');
