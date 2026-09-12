-- RT-FIN-006 — fechamento de período e repasse.

-- ---------------------------------------------------------------------------
-- O documento do que foi pago a uma pessoa por um período. É o contracheque do
-- salão: "de 1 a 31 de março você ganhou X, tinha adiantado Y, e recebeu Z".
--
-- POR QUE O SALDO É CUMULATIVO, E NÃO SÓ DO PERÍODO
--
-- Quem adiantou mais do que ganhou termina o mês DEVENDO, e essa dívida precisa
-- atravessar para o mês seguinte. Um razão filtrado por período a perderia de
-- vista no dia 1º — e o salão perderia o dinheiro sem nada acusar.
--
-- A saída NÃO é uma linha de transporte de saldo nem uma coluna de "saldo
-- anterior" que alguém precise manter em dia. É a conta:
--
--     a repassar = max( -sum(valor) para competência <= fim , 0 )
--
-- O razão é cumulativo e se liquida sozinho: os REPASSE já feitos estão dentro
-- da mesma soma, zerando o que já foi pago. É RN-FIN-008 outra vez — saldo é
-- soma filtrada —, com o filtro sendo "até", e não "entre".
--
-- Um efeito colateral que vale nomear: uma linha que chegue atrasada, dentro de
-- um período já fechado, é recolhida sozinha pelo fechamento seguinte. O
-- travamento de período vira aviso, e não requisito de correção.
-- ---------------------------------------------------------------------------
create table fechamento (
  id                   uuid primary key default gen_random_uuid(),
  estabelecimento_id   uuid not null references estabelecimento(id),

  -- Sem FK: profissional é de outro módulo (RN-EQP-015).
  profissional_id      uuid not null,

  periodo_inicio       date not null,
  periodo_fim          date not null,
  constraint periodo_na_ordem check (periodo_inicio <= periodo_fim),

  -- O contracheque, congelado. Recalcular um fechamento de três meses atrás
  -- produz número diferente do repasse já pago (C4, 02-decisoes-estruturais), e
  -- aí não se explica a diferença a quem recebeu.
  --
  -- Positivo = o salão devia. Negativo = a pessoa devia.
  saldo_anterior       numeric(19,4) not null,
  movimento_do_periodo numeric(19,4) not null,

  -- Nunca negativo: quando a pessoa deve, não há repasse — a dívida atravessa.
  valor_repassado      numeric(19,4) not null check (valor_repassado >= 0),

  criado_em            timestamptz not null,
  criado_por           uuid
);

-- ---------------------------------------------------------------------------
-- FECHAR DUAS VEZES É PAGAR DUAS VEZES, e nenhuma trava de aplicação resiste a
-- duas instâncias concorrentes: as duas leem, as duas não veem fechamento, as
-- duas gravam. O EXCLUDE não tem esse buraco.
--
-- Mesmo mecanismo da jornada (V18) e da agenda (V25), agora sobre datas.
-- ---------------------------------------------------------------------------
alter table fechamento
  add constraint fechamento_sem_sobreposicao
  exclude using gist (estabelecimento_id with =, profissional_id with =,
                      daterange(periodo_inicio, periodo_fim, '[]') with &&);

create index idx_fechamento_do_periodo
  on fechamento (estabelecimento_id, periodo_fim desc, periodo_inicio desc);

-- Imutável, como o razão (RN-FIN-008) e como o vale (RN-FIN-013): desfazer um
-- fechamento é estornar o repasse, não apagar o documento de que ele existiu.
select aplicar_rls_tenant('fechamento');
revoke update, delete on fechamento from salao_app;

comment on table fechamento is
  'RT-FIN-006: o contracheque de um profissional num período. Imutável.';
