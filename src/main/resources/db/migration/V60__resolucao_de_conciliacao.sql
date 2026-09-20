-- RT-FIN-011 — a decisão do dono sobre cada divergência, com motivo e trilha.

-- ---------------------------------------------------------------------------
-- O razão passa a aceitar CONCILIACAO como origem.
--
-- Não entra como 'MANUAL' pela mesma razão que o vale não entrou (V41): MANUAL é correção sem
-- documento por trás, e esta tem — a resolução, com motivo e quem decidiu. Com origem própria,
-- `pendentesDeEstorno('CONCILIACAO', id)` responde "esta resolução ainda vale?" sem ambiguidade, e a
-- reabertura reusa o estorno que já existe.
-- ---------------------------------------------------------------------------
alter table lancamento drop constraint lancamento_origem_tipo_check;
alter table lancamento add constraint lancamento_origem_tipo_check
  check (origem_tipo in ('COMANDA', 'PAGAMENTO', 'FECHAMENTO', 'MANUAL', 'VALE', 'CONCILIACAO'));

-- ===========================================================================
-- Uma resolução: um alvo, uma ação, um motivo escrito
--
-- O alvo é UM dos três lados da conferência: o par divergente, a venda que só a maquininha tem, ou o
-- pagamento que só o sistema tem. `um_alvo` garante que é exatamente um — uma linha com dois alvos não
-- teria como voltar para a conferência ao ser reaberta.
-- ===========================================================================
create table resolucao_de_conciliacao (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),

  -- O estorno da comanda apaga o pagamento (RT-FIN-001) e o par (V59); a resolução vai junto, pelo
  -- mesmo motivo: depois do estorno ela decide sobre algo que não existe mais.
  conciliacao_id      uuid references conciliacao_de_venda(id) on delete cascade,
  transacao_id        uuid references transacao_do_adquirente(id),
  pagamento_id        uuid references pagamento(id) on delete cascade,

  acao                text not null check (acao in ('CORRIGIR', 'ACEITAR', 'LANCAR')),
  motivo              text not null check (char_length(btrim(motivo)) between 3 and 500),

  -- O que a ação mudou: forma de/para, bandeira e parcelas de/para, valor lançado. É o que a reabertura
  -- lê para devolver o pagamento ao que era — sem isto, desfazer precisaria adivinhar.
  efeito              jsonb not null check (jsonb_typeof(efeito) = 'object'),
  -- A linha do razão que esta resolução escreveu, quando escreveu alguma.
  lancamento_id       uuid,

  resolvida_em        timestamptz not null,
  resolvida_por       uuid,

  -- Reabertura: a resolução continua, marcada. A linha volta para a conferência e uma nova resolução
  -- pode ser gravada.
  --
  -- `reaberta_por` nulo é a reabertura do SISTEMA: a importação seguinte trouxe a venda cancelada na
  -- adquirente, e a decisão foi tomada sem esse fato. A pessoa é perguntada de novo; nada do que a
  -- resolução fez é desfeito, porque corrigir a forma continuou certo. Reabertura de gente desfaz
  -- (RN-FIN-036), e tem quem.
  reaberta_em         timestamptz,
  reaberta_por        uuid,
  motivo_da_reabertura text check (char_length(btrim(motivo_da_reabertura)) between 3 and 500),

  constraint um_alvo
    check (num_nonnulls(conciliacao_id, transacao_id, pagamento_id) = 1),
  constraint reabertura_inteira
    check ((reaberta_em is null) = (motivo_da_reabertura is null))
);

-- Um alvo tem no máximo uma resolução VALENDO, e pode ter várias ao longo do tempo: resolver, reabrir,
-- resolver de novo. O parcial é o que permite as duas coisas ao mesmo tempo — e é ele que faz dois
-- cliques (ou duas abas) darem 409 em vez de duas linhas.
create unique index uq_resolucao_do_par
  on resolucao_de_conciliacao (conciliacao_id) where reaberta_em is null and conciliacao_id is not null;
create unique index uq_resolucao_da_venda
  on resolucao_de_conciliacao (transacao_id) where reaberta_em is null and transacao_id is not null;
create unique index uq_resolucao_do_pagamento
  on resolucao_de_conciliacao (pagamento_id) where reaberta_em is null and pagamento_id is not null;

-- A trilha do período, da mais recente para trás.
create index idx_resolucao_de_conciliacao_do_salao
  on resolucao_de_conciliacao (estabelecimento_id, resolvida_em desc, id desc);

select aplicar_rls_tenant('resolucao_de_conciliacao');

-- RN-FIN-036: a resolução não se reescreve. Nem a ação, nem o motivo, nem quem decidiu — só a
-- reabertura pode ser acrescentada, e ela também não se apaga. Quem garante é o banco, não a disciplina
-- de quem escreve o caso de uso.
revoke update, delete on resolucao_de_conciliacao from salao_app;
grant update (reaberta_em, reaberta_por, motivo_da_reabertura)
  on resolucao_de_conciliacao to salao_app;
