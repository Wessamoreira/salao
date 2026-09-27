-- RT-CNV-009 — uma volta do modelo por rajada, e não por mensagem.

-- ===========================================================================
-- 1. A ORDEM EM QUE AS MENSAGENS CHEGARAM, como fato e não como inferência.
--
-- Juntar três mensagens numa entrada só exige saber qual veio antes. Nenhuma coluna de hoje
-- responde isso com certeza:
--
--   `recebida_em` empata — um lote da Meta traz várias mensagens e todas são gravadas com o
--                  mesmo `Relogio.agora()`;
--   `enviada_em`  tem resolução de segundo, e é relógio de terceiro;
--   a ordem de `execucao_do_agente` INVERTE o áudio — ele vira execução quando a transcrição
--                  termina (RT-CNV-003), portanto DEPOIS do texto que chegou depois dele. É
--                  exatamente a rajada que esta rotina existe para resolver.
--
-- Mesmo padrão de `mensagem_para_equipe.sequencia` (V55). As linhas que já existem recebem
-- número na ordem física da tabela: nenhuma delas está pendente, e ordenar o passado não muda
-- nada.
-- ===========================================================================
alter table mensagem_recebida add column sequencia bigint generated always as identity;

-- ===========================================================================
-- 2. A RESERVA PRECISA SABER QUE UMA MENSAGEM CHEGOU — NUNCA O QUE ELA DIZIA.
--
-- A janela é decidida na consulta que reserva, e essa consulta roda pela role de plataforma
-- (mesmo motivo de V52 e V53: ela atravessa salões, e `salao_app` não pode). Para medir o
-- silêncio ela precisa ler `mensagem_recebida` — e é a primeira vez que a role de manutenção
-- toca nesta tabela.
--
-- Por isso o grant é POR COLUNA, como `grant update (processada_em)` de V50: `texto` e `midia_id`
-- ficam de fora. Saber que às 14h02 chegou algo daquele número é o que a janela precisa; ler o
-- pedido da cliente, não. Grant largo aqui seria uma porta aberta num lugar onde ninguém
-- procuraria depois.
-- ===========================================================================
create policy manutencao on mensagem_recebida to salao_manutencao using (true);
grant select (id, estabelecimento_id, de_telefone, tipo, recebida_em, sequencia)
  on mensagem_recebida to salao_manutencao;

-- ===========================================================================
-- 3. JUNTADA — a mensagem que foi respondida junto com a seguinte.
--
-- Continua uma linha por mensagem. Concatenar o texto na pendente que já existia seria menos
-- código e faria a tabela mentir: a execução diria ser da mensagem X carregando o texto de X, Y
-- e Z, e a unicidade por `mensagem_id` — o que torna o reenvio da Meta inofensivo — iria junto.
--
-- Sumir com a linha absorvida também não serve: a trilha (RT-CNV-007) responde "o que o agente
-- fez com esta mensagem?", e "foi respondida junto com a seguinte" É uma resposta. `respondida_por`
-- é o caminho até quem respondeu.
-- ===========================================================================
alter table execucao_do_agente drop constraint execucao_do_agente_status_check;
alter table execucao_do_agente add constraint execucao_do_agente_status_check
  check (status in ('PENDENTE', 'CONCLUIDA', 'RECUSADA', 'FALHOU', 'JUNTADA'));

alter table execucao_do_agente
  add column respondida_por uuid references execucao_do_agente(id);

-- Nos dois sentidos: JUNTADA sem destino seria um beco, e destino sem JUNTADA seria uma execução
-- que respondeu e foi respondida ao mesmo tempo.
alter table execucao_do_agente add constraint execucao_juntada_tem_quem_respondeu
  check ((status = 'JUNTADA') = (respondida_por is not null));

alter table execucao_do_agente add constraint execucao_nao_responde_por_si
  check (respondida_por is null or respondida_por <> id);

-- A consulta da janela agrupa as pendentes POR TELEFONE — o índice de V53 é só por
-- `proxima_tentativa_em`, e servia quando a unidade era a linha.
create index idx_execucao_da_rajada
  on execucao_do_agente (estabelecimento_id, de_telefone, proxima_tentativa_em)
  where status = 'PENDENTE';

-- O caminho de volta: as mensagens que esta execução absorveu. É por ele que a trilha remonta o
-- que a pessoa disse por inteiro.
create index idx_execucao_respondida_por
  on execucao_do_agente (respondida_por)
  where respondida_por is not null;
