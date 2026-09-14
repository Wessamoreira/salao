-- RT-CNV-006 — falar com a equipe pelo WhatsApp, e o aceite de quem vai atender.

-- ===========================================================================
-- 1. Resposta a botão, na caixa de entrada.
--
-- O botão não tem texto: tem o id que nós mandamos. É coluna própria, e não `texto`, porque texto é o
-- que a pessoa escreveu — e o id de um botão é nosso, com um token dentro.
-- ===========================================================================
alter table mensagem_recebida add column botao_id text;

alter table mensagem_recebida drop constraint mensagem_recebida_tipo_check;
alter table mensagem_recebida add constraint mensagem_recebida_tipo_check
  check (tipo in ('TEXTO', 'AUDIO', 'IMAGEM', 'DOCUMENTO', 'BOTAO', 'NAO_SUPORTADO'));

alter table mensagem_recebida drop constraint mensagem_coerente_com_o_tipo;
alter table mensagem_recebida add constraint mensagem_coerente_com_o_tipo check (
  (tipo = 'TEXTO' and texto is not null and midia_id is null and botao_id is null)
  or (tipo in ('AUDIO', 'IMAGEM', 'DOCUMENTO') and midia_id is not null and texto is null
      and botao_id is null)
  or (tipo = 'BOTAO' and botao_id is not null and texto is null and midia_id is null)
  or (tipo = 'NAO_SUPORTADO' and texto is null and midia_id is null and botao_id is null));

-- ===========================================================================
-- 2. A execução sabe de onde veio.
--
-- O toque num botão entra na fila de execuções, mas não é conversa: não passa pelo modelo, não entra
-- no histórico (o id do botão carrega o token) e pode vir de quem não tem conta — a profissional. Por
-- isso a execução de BOTAO pode terminar sem usuário efetivo.
-- ===========================================================================
alter table execucao_do_agente
  add column tipo text not null default 'CONVERSA' check (tipo in ('CONVERSA', 'BOTAO'));

alter table execucao_do_agente drop constraint execucao_concluida_tem_resposta;
alter table execucao_do_agente add constraint execucao_concluida_tem_resposta
  check (status <> 'CONCLUIDA'
         or (resposta is not null and (usuario_efetivo_id is not null or tipo = 'BOTAO')));

-- ===========================================================================
-- 3. O aceite de quem vai atender.
--
-- Quem, por qual número, por quê e com qual token é decidido na SIMULAÇÃO e não muda (sem grant). O
-- prazo começa no SIM de quem pediu, e a decisão chega pelo botão — essas colunas a aplicação atualiza.
-- ===========================================================================
alter table intencao_pendente
  add column aceite_de_nome          text,
  add column aceite_de_telefone      text check (aceite_de_telefone ~ '^[0-9]{10,15}$'),
  add column aceite_motivo           text,
  add column aceite_token            text,
  add column aceite_expira_em        timestamptz,
  add column aceite_decidido_em      timestamptz,
  add column aceite_por_execucao_id  uuid references execucao_do_agente(id);

alter table intencao_pendente drop constraint intencao_pendente_status_check;
alter table intencao_pendente add constraint intencao_pendente_status_check
  check (status in ('PENDENTE', 'AGUARDANDO_ACEITE', 'CONFIRMADA', 'RECUSADA', 'SUBSTITUIDA',
                    'EXPIRADA'));

alter table intencao_pendente add constraint intencao_aceite_inteiro check (
  (aceite_token is null and aceite_de_telefone is null and aceite_de_nome is null
   and aceite_motivo is null)
  or (aceite_token is not null and aceite_de_telefone is not null and aceite_de_nome is not null
      and aceite_motivo is not null));

alter table intencao_pendente add constraint intencao_aguardando_aceite_tem_prazo check (
  status <> 'AGUARDANDO_ACEITE'
  or (aceite_token is not null and aceite_expira_em is not null
      and decidida_por_execucao_id is not null));

-- A intenção que exigia aceite só é CONFIRMADA com o aceite registrado. É a regra de RN-CNV-018 no
-- banco: nenhum caminho da aplicação consegue gravar "confirmada" pulando quem precisava aceitar.
alter table intencao_pendente add constraint intencao_com_aceite_so_confirma_aceita check (
  status <> 'CONFIRMADA' or aceite_token is null
  or (aceite_decidido_em is not null and aceite_por_execucao_id is not null));

create index idx_intencao_aguardando_aceite
  on intencao_pendente (aceite_expira_em)
  where status = 'AGUARDANDO_ACEITE';

grant update (aceite_expira_em, aceite_decidido_em, aceite_por_execucao_id)
  on intencao_pendente to salao_app;

-- O expirador passa por todos os salões, pela conexão de manutenção.
create policy manutencao on intencao_pendente to salao_manutencao using (true);
grant select on intencao_pendente to salao_manutencao;
grant update (status, aceite_decidido_em) on intencao_pendente to salao_manutencao;

-- ===========================================================================
-- 4. O que sai para a equipe.
--
-- Gravar antes de enviar (RT-NOT-001): a falha de rede vira linha para tentar de novo. Uma mensagem
-- por motivo, origem e destino — a conversa que volta à fila depois de uma queda não responde duas
-- vezes. `sequencia` dá a ordem por destino: a resposta antes do pedido de confirmação.
--
-- A aplicação só insere. Quem muda o estado é o despachante, pela conexão de manutenção — e o texto,
-- depois de enfileirado, não se reescreve.
-- ===========================================================================
create table mensagem_para_equipe (
  id                    uuid primary key default gen_random_uuid(),
  estabelecimento_id    uuid not null references estabelecimento(id),
  sequencia             bigint generated always as identity,
  destino               text not null check (destino ~ '^[0-9]{10,15}$'),
  motivo                text not null check (motivo in ('RESPOSTA', 'PEDIDO_DE_CONFIRMACAO',
                                                        'PEDIDO_DE_ACEITE', 'DESFECHO_DO_ACEITE')),
  -- A execução ou a intenção que gerou a mensagem.
  origem_id             uuid not null,
  texto                 text not null,
  botoes                jsonb,

  status                text not null default 'PENDENTE'
                          check (status in ('PENDENTE', 'ENVIADA', 'FALHOU')),
  tentativas            smallint not null default 0,
  proxima_tentativa_em  timestamptz not null,
  reservada_ate         timestamptz,
  ultimo_erro           text,
  provedor_id           text,
  criada_em             timestamptz not null,
  enviada_em            timestamptz,

  constraint uma_mensagem_por_origem unique (motivo, origem_id, destino),
  constraint botoes_so_no_pedido_de_aceite check ((botoes is not null) = (motivo = 'PEDIDO_DE_ACEITE')),
  constraint enviada_tem_data check (status <> 'ENVIADA' or enviada_em is not null)
);

create index idx_saida_a_enviar
  on mensagem_para_equipe (sequencia)
  where status = 'PENDENTE';

create index idx_saida_por_destino
  on mensagem_para_equipe (estabelecimento_id, destino, sequencia)
  where status = 'PENDENTE';

select aplicar_rls_tenant('mensagem_para_equipe');
revoke update, delete on mensagem_para_equipe from salao_app;

create policy manutencao on mensagem_para_equipe to salao_manutencao using (true);
grant select, insert on mensagem_para_equipe to salao_manutencao;
grant update (status, tentativas, proxima_tentativa_em, reservada_ate, ultimo_erro, provedor_id,
              enviada_em)
  on mensagem_para_equipe to salao_manutencao;
