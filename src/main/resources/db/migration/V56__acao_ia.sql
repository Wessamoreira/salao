-- RT-CNV-007 — a trilha do agente: o que cada conversa pediu, tentou, gravou e custou.

-- ===========================================================================
-- Por que uma tabela, e não uma consulta sobre as que já existem
--
-- Tudo o que está aqui já passou por `execucao_do_agente`, `intencao_pendente` e
-- `mensagem_recebida`. Mas cada uma tem o seu prazo: a caixa de entrada vale 90 dias, e a trilha
-- do agente vale 12 meses, com o conteúdo anonimizado aos 90 (docs/04). Uma view morreria junto
-- com a primeira tabela expurgada — e "quem cancelou o agendamento da cliente?" deixaria de ter
-- resposta no dia em que a mensagem saísse.
--
-- Então a linha é um RETRATO, gravado no mesmo commit em que a conversa se encerra, e que não
-- depende de nada continuar existindo depois: sem chave estrangeira para a execução, de propósito.
-- ===========================================================================
create table acao_ia (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),
  execucao_id         uuid not null,
  ocorrida_em         timestamptz not null,

  tipo                text not null check (tipo in ('CONVERSA', 'BOTAO')),
  desfecho            text not null check (desfecho in ('CONCLUIDA', 'RECUSADA', 'FALHOU')),
  motivo_recusa       text,

  -- Em nome de quem. Nulo quando a identidade recusou (número desconhecido) e no toque de quem não
  -- tem conta — e é justamente nesses casos que o telefone importa.
  usuario_efetivo_id  uuid,
  de_telefone         text,

  -- O que foi pedido: a mensagem, ou a transcrição do áudio. No toque de botão, só "Aceitar" ou
  -- "Recusar" — o id do botão carrega o token do aceite, e não entra aqui.
  veio_de_audio       boolean not null,
  pedido              text,
  resposta            text,

  -- Cada pedido de ferramenta, com os argumentos e o desfecho (RN-CNV-010). É o que mostra um
  -- ataque como RECUSADO, e não como silêncio.
  ferramentas         jsonb not null,
  -- As intenções que esta conversa propôs ou decidiu, com o resumo e o estado naquele instante.
  intencoes           jsonb not null,

  -- As duas perguntas do painel, calculadas uma vez: esta conversa gravou algo no domínio? Alguma
  -- tranca recusou alguma coisa?
  gravou              boolean not null,
  bloqueada           boolean not null,

  modelo              text,
  tokens_entrada      integer not null,
  tokens_saida        integer not null,
  custo_estimado_usd  numeric(19, 6) not null,

  anonimizada_em      timestamptz,

  constraint uma_acao_por_execucao unique (execucao_id),
  constraint acao_anonimizada_sem_conteudo check (
    anonimizada_em is null or (pedido is null and resposta is null and de_telefone is null))
);

-- O painel: as ações do salão, das mais recentes para trás, com desempate por id para o cursor.
create index idx_acao_ia_do_salao on acao_ia (estabelecimento_id, ocorrida_em desc, id desc);
create index idx_acao_ia_por_pessoa
  on acao_ia (estabelecimento_id, usuario_efetivo_id, ocorrida_em desc);
-- O expurgo varre por data, atravessando salões.
create index idx_acao_ia_por_data on acao_ia (ocorrida_em);

select aplicar_rls_tenant('acao_ia');

-- Trilha não se reescreve nem se apaga pela aplicação (a lição de RN-IAM-017).
revoke update, delete on acao_ia from salao_app;

-- Quem anonimiza e apaga por prazo é o expurgo, pela conexão de manutenção — e ele só pode tirar
-- conteúdo, não trocar o que aconteceu.
create policy manutencao on acao_ia to salao_manutencao using (true);
grant select, delete on acao_ia to salao_manutencao;
grant update (pedido, resposta, de_telefone, ferramentas, intencoes, anonimizada_em)
  on acao_ia to salao_manutencao;
