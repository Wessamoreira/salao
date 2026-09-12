-- RT-FIN-007 — estorno de fechamento e liberação do período.

-- ---------------------------------------------------------------------------
-- O PROBLEMA: o EXCLUDE que impede pagar duas vezes mora no DOCUMENTO.
--
-- `fechamento` é imutável por permissão (RN-FIN-020): a role recebeu INSERT e
-- SELECT, e há teste provando que `update` é recusado pelo banco. Só que a
-- constraint de sobreposição também mora nele — então, enquanto a linha existe,
-- o período continua ocupado, e um fechamento errado não pode ser desfeito.
--
-- As três saídas ruins:
--
--   1. Apagar a linha. Perde o documento de que o pagamento existiu, que é
--      justamente o que se precisa para explicar o dinheiro que saiu.
--   2. Tirar o EXCLUDE e checar em Java. Trava de aplicação não resiste a duas
--      instâncias — foi por isso que a constraint existe.
--   3. Dar UPDATE na tabela. Quebra RN-FIN-020 e o teste dela, e abre a porta
--      para alguém "corrigir" um valor_repassado já pago.
--
-- A SAÍDA: GRANT UPDATE POR COLUNA.
--
-- O PostgreSQL concede UPDATE coluna a coluna. Então UMA coluna passa a ser
-- escrevível — a que registra a anulação — e todas as que dizem QUANTO FOI PAGO
-- continuam recusando `update` pelo banco, para a role e para quem escrever o
-- código errado amanhã.
--
-- Não é relaxamento da imutabilidade: é a precisão dela. O teste de RN-FIN-020
-- (`update fechamento set valor_repassado = 0` falha) continua passando.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- Por que uma COLUNA, quando o razão já diz se o repasse foi estornado.
--
-- Porque a constraint não consulta o razão: `EXCLUDE` só enxerga a própria
-- tabela. A coluna existe para o banco poder expressar a regra, e não para
-- guardar um status que já existe em outro lugar.
--
-- E ela responde uma pergunta DIFERENTE da do razão. Um fechamento de
-- valor_repassado = 0 não tem linha de REPASSE: estorná-lo não anula lançamento
-- nenhum, mas libera o período. O razão responde "o dinheiro voltou?"; esta
-- coluna responde "este documento ainda ocupa o período?".
-- ---------------------------------------------------------------------------
alter table fechamento add column estornado_em timestamptz;

comment on column fechamento.estornado_em is
  'RT-FIN-007: quando o fechamento foi anulado. Nulo = ainda ocupa o período. '
  'O motivo mora na linha de ESTORNO do razão, não aqui.';

-- O período volta a ficar livre porque a constraint passa a ignorar os
-- estornados. Os valores da linha continuam intocáveis.
alter table fechamento drop constraint fechamento_sem_sobreposicao;
alter table fechamento
  add constraint fechamento_sem_sobreposicao
  exclude using gist (estabelecimento_id with =, profissional_id with =,
                      daterange(periodo_inicio, periodo_fim, '[]') with &&)
  where (estornado_em is null);

grant update (estornado_em) on fechamento to salao_app;

-- "Quais documentos ainda valem?" — a consulta do fechamento e da liberação.
create index idx_fechamento_vigente
  on fechamento (estabelecimento_id, periodo_fim desc)
  where estornado_em is null;
