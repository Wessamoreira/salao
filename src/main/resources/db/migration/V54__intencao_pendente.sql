-- RT-CNV-005 — o agente propõe, a pessoa confirma: a intenção pendente, e a origem AGENTE na agenda.

-- ===========================================================================
-- 1. A intenção pendente.
--
-- O padrão inegociável do plano: toda escrita passa por simular → confirmação humana explícita →
-- confirmar, e o LLM nunca escreve direto no domínio. "Confirmação humana" não pode ser o modelo
-- concluindo que a pessoa disse sim — então ela precisa de ESTADO fora do modelo: o que foi
-- proposto, por quem, com qual código e até quando. É esta tabela.
--
-- `comando` é o que executa; `resumo` e `pedido_de_confirmacao` são o que a pessoa leu. Os três são
-- imutáveis para a aplicação (grant de coluna, abaixo): o que foi confirmado é o que foi proposto.
-- ===========================================================================
create table intencao_pendente (
  id                        uuid primary key default gen_random_uuid(),
  estabelecimento_id        uuid not null references estabelecimento(id),

  -- A conversa que propôs, e em nome de quem.
  execucao_id               uuid not null references execucao_do_agente(id),
  de_telefone               text not null,
  usuario_efetivo_id        uuid not null,

  ferramenta                text not null,
  -- O que o modelo pediu, como pediu: entrada não confiável, guardada para o rastro.
  argumentos                jsonb not null,
  -- O que a ferramenta resolveu executar — ids conferidos, instante exato.
  comando                   jsonb not null,
  resumo                    text not null,
  pedido_de_confirmacao     text not null,
  codigo                    text not null check (codigo ~ '^[0-9]{4}$'),

  status                    text not null default 'PENDENTE'
                              check (status in ('PENDENTE', 'CONFIRMADA', 'RECUSADA',
                                                'SUBSTITUIDA', 'EXPIRADA')),
  -- Por que a confirmação não gravou: o código do erro de domínio, ou SEM_PERMISSAO.
  motivo                    text,
  -- A resposta do servidor à pessoa, e o que ficou gravado.
  resposta                  text,
  resultado                 jsonb,

  criada_em                 timestamptz not null,
  expira_em                 timestamptz not null,
  decidida_em               timestamptz,
  -- A mensagem que decidiu: "SIM 4821" é uma execução como qualquer outra.
  decidida_por_execucao_id  uuid references execucao_do_agente(id),

  constraint intencao_expira_depois_de_nascer check (expira_em > criada_em),
  constraint intencao_decidida_tem_data check ((status = 'PENDENTE') = (decidida_em is null)),
  constraint intencao_confirmada_tem_rastro check (
    status <> 'CONFIRMADA'
    or (decidida_por_execucao_id is not null and resposta is not null and resultado is not null)),
  constraint intencao_recusada_tem_motivo check (
    status <> 'RECUSADA' or (motivo is not null and decidida_por_execucao_id is not null))
);

-- Uma pendente por telefone: a proposta nova substitui a anterior. Duas ao mesmo tempo fariam a
-- pessoa confirmar uma olhando para a outra.
create unique index uma_intencao_pendente_por_telefone
  on intencao_pendente (estabelecimento_id, de_telefone)
  where status = 'PENDENTE';

-- A confirmação procura pelo telefone e pelo código, a mais recente primeiro.
create index idx_intencao_por_codigo
  on intencao_pendente (estabelecimento_id, de_telefone, codigo, criada_em desc);

create index idx_intencao_da_execucao on intencao_pendente (execucao_id);

select aplicar_rls_tenant('intencao_pendente');

-- O que foi proposto não se reescreve e não se apaga: só a DECISÃO muda.
revoke update, delete on intencao_pendente from salao_app;
grant update (status, motivo, resposta, resultado, decidida_em, decidida_por_execucao_id)
  on intencao_pendente to salao_app;

-- ===========================================================================
-- 2. A origem AGENTE (RN-AGD-013).
--
-- Quem marca pelo agente é alguém da EQUIPE que confirmou o resumo com o código: como no balcão,
-- nasce CONFIRMADO. WHATSAPP diria o contrário — é a cliente sozinha, e nasce SOLICITADO. E PAINEL
-- apagaria da agenda que a marcação veio do agente.
-- ===========================================================================
alter table agendamento drop constraint agendamento_origem_check;
alter table agendamento add constraint agendamento_origem_check
  check (origem in ('WEB', 'WHATSAPP', 'RECORRENCIA', 'PAINEL', 'AGENTE'));
