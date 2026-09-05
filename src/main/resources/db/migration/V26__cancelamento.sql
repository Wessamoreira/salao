-- RT-AGD-005 — cancelar, com motivo.

-- ---------------------------------------------------------------------------
-- `motivo_cancelamento` já existe desde V25. Falta QUEM cancelou.
--
-- Parece detalhe e é a informação que o salão mais usa: "a Marilda desmarcou
-- três vezes este mês" é uma conversa que o dono precisa ter, e "eu remarquei
-- a Marilda três vezes porque a Ana faltou" é outra conversa completamente
-- diferente. Sem esta coluna, as duas viram a mesma linha no relatório.
--
-- Fica na tabela e não só na trilha porque é dado de negócio, consultado por
-- cliente e por período. A trilha é append-only e não indexada para isso — e a
-- anonimização (RN-CLI-007) não a alcança, o que a torna o lugar errado para
-- qualquer coisa que se queira consultar de propósito.
-- ---------------------------------------------------------------------------
alter table agendamento
  add column cancelado_pela_cliente boolean;

comment on column agendamento.cancelado_pela_cliente is
  'RT-AGD-005: true = a cliente desmarcou; false = o salão desmarcou; null = não cancelado.';

-- "Quantos cancelamentos desta cliente no período" é a consulta que sustenta a
-- conversa sobre taxa e sobre fila de espera.
create index idx_agendamento_cancelado
  on agendamento (estabelecimento_id, cliente_id, inicio)
  where status = 'CANCELADO';
