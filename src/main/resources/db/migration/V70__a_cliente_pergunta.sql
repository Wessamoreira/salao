-- RT-CNV-010 — a cliente passa a ser respondida: horário, serviço e preço. Marcar continua da equipe.

-- ===========================================================================
-- 1. A execução sabe dizer que foi uma VISITANTE.
--
-- Até aqui, toda conversa encerrada tinha um `usuario_efetivo_id` — e o constraint exigia isso, com
-- razão: uma conversa concluída precisava dizer em nome de quem agiu. A visitante não age em nome de
-- ninguém (ADR-0011), e não tem conta para apontar.
--
-- Uma coluna, e não "usuario_efetivo_id nulo significa visitante": nulo já significa outra coisa
-- nesta tabela — o toque de botão de quem não tem conta (RT-CNV-006). Dois significados na mesma
-- ausência é o que faz a trilha responder errado sem parecer errada.
-- ===========================================================================
alter table execucao_do_agente add column de_visitante boolean not null default false;

alter table execucao_do_agente drop constraint execucao_concluida_tem_resposta;
alter table execucao_do_agente add constraint execucao_concluida_tem_resposta
  check (status <> 'CONCLUIDA'
         or (resposta is not null and (usuario_efetivo_id is not null or de_visitante)));

-- A visitante nunca é um toque de botão: botão é resposta a mensagem que o salão mandou, e o salão
-- não manda botão para quem não é da equipe.
alter table execucao_do_agente add constraint visitante_nao_e_botao
  check (not de_visitante or tipo = 'CONVERSA');

-- ===========================================================================
-- 2. A trilha diz de quem era a conversa.
--
-- Sem isto, o painel de revisão (RT-CNV-007) mostraria a conversa da cliente exatamente como mostra
-- a de alguém da equipe sem nome: só os quatro últimos dígitos do telefone. "Quem era esta pessoa?"
-- é a pergunta que a trilha existe para responder, e ela ficaria sem resposta justamente no caso
-- novo.
-- ===========================================================================
alter table acao_ia add column de_visitante boolean not null default false;

-- ===========================================================================
-- 3. O pote menor: quanto do teto do dia o desconhecido pode gastar.
--
-- Decisão do dono (27/09/2026): metade. O motivo é a ORDEM DE DEGRADAR — estourado o pote pequeno, a
-- cliente deixa de ser respondida e a equipe continua trabalhando. Sem ele, cem números errados de
-- madrugada deixariam a recepção sem agente no dia seguinte inteiro.
--
-- Duas colunas, e não uma: o custo de uma chamada só se conhece quando ela volta, então o visitante
-- reserva o pior caso e liquida depois — o mesmo mecanismo do teto do salão (V53), pelo mesmo
-- motivo. Conferir antes e gravar depois deixaria chamadas concorrentes passarem juntas pelo mesmo
-- saldo.
-- ===========================================================================
alter table consumo_de_ia
  add column custo_visitante_confirmado_usd numeric(19, 6) not null default 0
    check (custo_visitante_confirmado_usd >= 0),
  add column custo_visitante_reservado_usd numeric(19, 6) not null default 0
    check (custo_visitante_reservado_usd >= 0);
