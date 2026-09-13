-- RT-CNV-004 — a orquestração do agente: fila de conversas e teto de custo por dia.

-- ===========================================================================
-- 1. A fila de execuções.
--
-- Uma chamada ao modelo leva segundos, e a conversa pode encadear várias. Mesmo motivo da fila de
-- áudio (RN-CNV-006): nada disso cabe dentro do listener do evento sem prender conexão.
--
-- `entrada` é o texto que o agente vai ler — a mensagem de texto ou a transcrição —, CONGELADO aqui.
-- Assim o histórico da conversa sai desta tabela sozinha, e o que o agente leu é exatamente o que
-- ficou registrado, mesmo que a transcrição fosse refeita depois.
-- ===========================================================================
create table execucao_do_agente (
  id                   uuid primary key default gen_random_uuid(),
  estabelecimento_id   uuid not null references estabelecimento(id),
  mensagem_id          uuid not null unique references mensagem_recebida(id),
  de_telefone          text not null,
  entrada              text not null,

  status               text not null default 'PENDENTE'
                         check (status in ('PENDENTE', 'CONCLUIDA', 'RECUSADA', 'FALHOU')),

  -- Por que não conversou: o motivo da identidade (RN-IAM-020), o limite por telefone ou o teto de
  -- custo. Diagnóstico, nunca resposta a quem escreveu.
  motivo_recusa        text,

  usuario_efetivo_id   uuid,
  resposta             text,

  -- O que o modelo pediu, e o que o sistema fez com cada pedido: executada, fora da lista, escrita
  -- recusada, sem permissão. É o rastro que R-10 exige — a ação de um modelo comprometido tem de
  -- aparecer aqui como RECUSADA, e não como ausência.
  ferramentas          jsonb not null default '[]'::jsonb,

  iteracoes            smallint not null default 0,
  modelo               text,
  tokens_entrada       integer not null default 0,
  tokens_saida         integer not null default 0,
  custo_estimado_usd   numeric(19, 6) not null default 0,

  tentativas           smallint not null default 0,
  proxima_tentativa_em timestamptz not null,
  reservada_ate        timestamptz,
  ultimo_erro          text,

  criado_em            timestamptz not null default now(),
  concluida_em         timestamptz,

  constraint execucao_concluida_tem_resposta
    check (status <> 'CONCLUIDA' or (resposta is not null and usuario_efetivo_id is not null)),
  constraint execucao_recusada_tem_motivo
    check (status <> 'RECUSADA' or motivo_recusa is not null)
);

create index idx_execucao_a_processar
  on execucao_do_agente (proxima_tentativa_em)
  where status = 'PENDENTE';

-- O histórico da conversa: as últimas trocas deste telefone.
create index idx_execucao_historico
  on execucao_do_agente (estabelecimento_id, de_telefone, concluida_em desc)
  where status = 'CONCLUIDA';

select aplicar_rls_tenant('execucao_do_agente');
revoke delete on execucao_do_agente from salao_app;

create policy manutencao on execucao_do_agente to salao_manutencao using (true);
grant select, update on execucao_do_agente to salao_manutencao;

-- ===========================================================================
-- 2. O teto de custo por salão por dia (R-17) — reservado ANTES, liquidado DEPOIS.
--
-- O custo de uma chamada só é conhecido quando ela volta. "Conferir antes e gravar depois" deixaria
-- chamadas concorrentes passarem juntas pelo mesmo saldo e estourarem o teto. Então cada chamada
-- RESERVA o seu pior caso num comando atômico que só passa se couber, e depois troca a reserva pelo
-- custo real. Se o processo morrer entre os dois, a reserva fica até o fim do dia: erra para o lado
-- de gastar menos, que é o lado certo de errar.
--
-- `dia` é o dia civil no fuso do SALÃO (ADR-0009): o teto vira à meia-noite de lá, não de Greenwich.
-- ===========================================================================
create table consumo_de_ia (
  estabelecimento_id    uuid not null references estabelecimento(id),
  dia                   date not null,
  custo_confirmado_usd  numeric(19, 6) not null default 0 check (custo_confirmado_usd >= 0),
  custo_reservado_usd   numeric(19, 6) not null default 0 check (custo_reservado_usd >= 0),
  tokens_entrada        bigint not null default 0,
  tokens_saida          bigint not null default 0,
  chamadas              integer not null default 0,
  primary key (estabelecimento_id, dia)
);

select aplicar_rls_tenant('consumo_de_ia');
revoke delete on consumo_de_ia from salao_app;
