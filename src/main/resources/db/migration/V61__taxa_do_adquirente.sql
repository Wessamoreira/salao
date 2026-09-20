-- RT-FIN-012 — o que a adquirente retém, e quando o dinheiro cai.

-- ---------------------------------------------------------------------------
-- O razão passa a aceitar ADQUIRENTE como origem.
--
-- Origem própria pela mesma razão do vale (V41) e da resolução (V60): é ela que faz
-- `pendentesDeEstorno('ADQUIRENTE', id)` responder "esta venda já teve a taxa lançada?" sem
-- ambiguidade, e é por ela que o cancelamento estorna.
-- ---------------------------------------------------------------------------
alter table lancamento drop constraint lancamento_origem_tipo_check;
alter table lancamento add constraint lancamento_origem_tipo_check
  check (origem_tipo in ('COMANDA', 'PAGAMENTO', 'FECHAMENTO', 'MANUAL', 'VALE', 'CONCILIACAO',
                         'ADQUIRENTE'));

-- ===========================================================================
-- A taxa contratada, por faixa
--
-- Por produto e faixa de parcela, e não por bandeira: é como o contrato do salão é negociado, e a
-- recepção quase nunca informa a bandeira — uma taxa por bandeira ficaria sem saber qual usar na
-- maioria das vendas (decisão do dono, 20/09/2026).
--
-- `dias_para_receber` é o prazo da PRIMEIRA parcela. Entre parcelas são 30 dias, que é calendário da
-- adquirente e não escolha do salão: fica como constante do domínio, com nome.
-- ===========================================================================
create table taxa_do_adquirente (
  estabelecimento_id  uuid not null references estabelecimento(id),
  adquirente          text not null check (adquirente in ('STONE')),
  faixa               text not null check (faixa in
                        ('DEBITO', 'CREDITO_A_VISTA', 'CREDITO_2_6', 'CREDITO_7_12')),

  -- Percentual em forma percentual mesmo: 3.4900 é 3,49%. Quatro casas porque a adquirente cobra
  -- com duas e negocia com três.
  percentual          numeric(6, 4) not null check (percentual >= 0 and percentual < 100),
  dias_para_receber   smallint not null check (dias_para_receber between 0 and 120),

  atualizada_em       timestamptz not null,
  atualizada_por      uuid,

  primary key (estabelecimento_id, adquirente, faixa)
);

select aplicar_rls_tenant('taxa_do_adquirente');

-- A taxa muda quando o salão renegocia — `update` é o caminho normal. Apagar, não: a previsão já
-- fotografada nos pagamentos continua valendo, e uma faixa sumindo deixaria venda nova sem previsão
-- em silêncio.
revoke delete on taxa_do_adquirente from salao_app;

-- ===========================================================================
-- O prazo, fotografado no pagamento
--
-- `taxa_prevista` e `previsao_recebimento` existem desde V36 e passam a ser preenchidas. Falta o
-- prazo: sem ele, a segunda parcela teria de sair da data da primeira — que já foi empurrada para o
-- próximo dia útil —, e o erro se acumularia a cada parcela. A adquirente conta todas a partir do dia
-- da venda.
--
-- Fotografado, e não lido do contrato na hora da consulta: renegociar a taxa em dezembro não pode
-- mudar o que o sistema prometeu em setembro. É a mesma decisão do snapshot da comissão (RN-ATD-010).
-- ===========================================================================
alter table pagamento
  add column dias_para_receber smallint check (dias_para_receber between 0 and 120);
