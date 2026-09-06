-- RT-ATD-007 — por que esta comanda foi estornada.

-- ---------------------------------------------------------------------------
-- Só para o estorno, e não para a reabertura, de propósito.
--
-- `ESTORNADA` é estado terminal: a linha vai ficar ali para sempre dizendo que
-- um atendimento pago foi desfeito, e "por quê" faz parte do que ela afirma.
-- Sem a coluna, a resposta estaria só na auditoria — que é o lugar certo para
-- a trilha e o lugar errado para um dado que a própria tela da comanda precisa
-- mostrar.
--
-- Reabrir é diferente: é transitório. A comanda volta à vida normal e fecha de
-- novo, e o que sobra do episódio é justamente trilha — que é da auditoria.
-- ---------------------------------------------------------------------------
alter table comanda add column motivo_estorno text;

comment on column comanda.motivo_estorno is
  'RT-ATD-007: obrigatório ao estornar. Reabertura não usa esta coluna — o rastro dela é a auditoria.';
