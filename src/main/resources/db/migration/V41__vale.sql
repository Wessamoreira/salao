-- RT-FIN-005 — o vale (adiantamento).

-- ---------------------------------------------------------------------------
-- Dinheiro entregue ao profissional ANTES do fechamento, descontado nele.
--
-- POR QUE UMA TABELA, SE O RAZÃO JÁ GUARDA O EFEITO
--
-- O razão guarda o efeito — "menos R$ 50 a receber". O vale guarda o FATO: de
-- quem, quanto, por qual meio o dinheiro saiu e por quê. Sem a tabela, "saiu da
-- gaveta ou caiu na conta?" não tem resposta — e é essa pergunta que faz o
-- caixa do dia fechar ou não fechar.
--
-- O QUE ELA NÃO TEM: COLUNA DE STATUS.
--
-- Vale cancelado é vale com um ESTORNO no razão apontando para ele. Uma coluna
-- `cancelado` seria uma segunda verdade sobre o mesmo fato, e as duas
-- divergiriam no primeiro caminho que esquecesse de escrever numa delas. É a
-- mesma forma da devolução de estoque (RN-ATD-022) e do estorno de comanda
-- (RN-FIN-010): quem já foi anulado tem uma linha apontando para si, e o
-- próprio razão responde.
-- ---------------------------------------------------------------------------
create table vale (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- Sem FK: profissional é de outro módulo (RN-EQP-015). Quem confere que ele
  -- existe NESTE salão é o caso de uso, pela API do dono do dado.
  profissional_id    uuid not null,

  -- Sempre positivo. Vale de zero não é vale, e vale negativo seria repasse
  -- entrando pela porta errada.
  valor              numeric(19,4) not null check (valor > 0),

  -- De onde o dinheiro saiu. É o que separa o que falta na gaveta do que saiu
  -- do banco — a conta que o dono faz no fim do dia, e que o razão sozinho não
  -- responde.
  forma              text not null check (forma in ('DINHEIRO', 'PIX', 'TRANSFERENCIA')),

  -- O dia do SALÃO, não o do servidor (RN-FIN-010). O vale entregue às 23h50 de
  -- 31 de março em Manaus é de março, e o servidor em UTC já está em abril.
  data_competencia   date not null,

  motivo             text,

  criado_em          timestamptz not null,
  criado_por         uuid
);

-- "Quanto a Ana já adiantou neste período?" — a consulta do extrato (RT-FIN-004)
-- e a que o fechamento (RT-FIN-006) vai descontar.
create index idx_vale_do_profissional
  on vale (estabelecimento_id, profissional_id, data_competencia);

-- Imutável pela mesma razão do razão (RN-FIN-008): não é disciplina de quem
-- escreve o código, é permissão do banco, para quem escrever o código errado
-- amanhã. Corrigir um vale é cancelá-lo e registrar outro.
select aplicar_rls_tenant('vale');
revoke update, delete on vale from salao_app;

-- ---------------------------------------------------------------------------
-- O razão passa a aceitar VALE como origem.
--
-- Poderia ter entrado como 'MANUAL', que já existia — e seria errado: MANUAL é
-- correção sem documento por trás, e este tem. Com origem própria,
-- `pendentesDeEstorno('VALE', id)` responde "este vale ainda vale?" sem
-- ambiguidade, e o cancelamento reusa o estorno que já existe.
-- ---------------------------------------------------------------------------
alter table lancamento drop constraint lancamento_origem_tipo_check;
alter table lancamento add constraint lancamento_origem_tipo_check
  check (origem_tipo in ('COMANDA', 'PAGAMENTO', 'FECHAMENTO', 'MANUAL', 'VALE'));

comment on table vale is
  'RT-FIN-005: adiantamento ao profissional. Imutável; cancelar é estornar no razão.';
