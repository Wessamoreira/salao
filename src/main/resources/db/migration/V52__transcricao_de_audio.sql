-- RT-CNV-003 — o áudio que chega pelo WhatsApp: guardado, transcrito, e nunca com conexão presa.

-- ===========================================================================
-- 1. `arquivo` passa a aceitar áudio — e só para dono MENSAGEM.
--
-- A lista branca de V31 era uma só porque todo dono era de anexo. Com áudio, uma lista só abriria o
-- upload de foto de cliente para OGG: o formato seria reconhecido e aceito no lugar errado. A regra
-- passou a ser "o tipo cabe NESTE dono", na aplicação (TiposPorDono) e aqui.
-- ===========================================================================
alter table arquivo drop constraint arquivo_dono_tipo_check;
alter table arquivo add constraint arquivo_dono_tipo_check
  check (dono_tipo in ('CLIENTE', 'PROFISSIONAL', 'MENSAGEM'));

alter table arquivo drop constraint arquivo_tipo_check;
alter table arquivo add constraint arquivo_tipo_check
  check (tipo in ('JPEG', 'PNG', 'HEIC', 'WEBP', 'PDF', 'OGG', 'MP3', 'M4A', 'AMR'));

alter table arquivo add constraint arquivo_tipo_coerente_com_o_dono check (
  tipo is null
  or (dono_tipo in ('CLIENTE', 'PROFISSIONAL') and tipo in ('JPEG', 'PNG', 'HEIC', 'WEBP', 'PDF'))
  or (dono_tipo = 'MENSAGEM' and tipo in ('OGG', 'MP3', 'M4A', 'AMR')));

-- ===========================================================================
-- 2. A fila de transcrição.
--
-- POR QUE UMA FILA, e não o trabalho dentro do listener do evento: `@ApplicationModuleListener` abre
-- transação antes do corpo do método, e com o TenantAwareTransactionManager isso pega uma conexão na
-- hora. Baixar o áudio e transcrever dentro dele seguraria uma conexão do pool por dezenas de
-- segundos a cada nota de voz — o gargalo de R-18, provocado pelo recurso mais usado do agente. O
-- listener só insere esta linha; o processador faz a rede FORA de transação.
--
-- Duas etapas com credenciais diferentes, e é por isso que o status tem duas paradas:
--   PENDENTE → precisa BAIXAR   (token da Meta)
--   BAIXADA  → precisa TRANSCREVER (provedor de STT)
-- Sem a credencial de uma etapa, as linhas dela esperam — sem gastar tentativa.
-- ===========================================================================
create table transcricao_de_audio (
  id                   uuid primary key default gen_random_uuid(),
  estabelecimento_id   uuid not null references estabelecimento(id),

  -- Uma transcrição por mensagem. É o que torna a entrega "ao menos uma vez" do outbox inofensiva:
  -- o evento repetido encontra a linha e não faz nada.
  mensagem_id          uuid not null unique references mensagem_recebida(id),

  status               text not null default 'PENDENTE'
                         check (status in ('PENDENTE', 'BAIXADA', 'CONCLUIDA', 'FALHOU')),

  -- A nossa cópia do áudio. Sem FK: `arquivo` é de outro módulo (RN-EQP-015). Depois de baixado,
  -- tudo parte daqui e não mais da Meta — a mídia de lá expira, e a nossa é a prova do que a
  -- cliente disse quando a transcrição errar "quinta" por "quarta".
  arquivo_id           uuid,

  texto                text,
  idioma               text,
  duracao_segundos     numeric(8, 2),
  provedor             text,

  -- ---------------------------------------------------------------------
  -- Tentativas com espera crescente (EsperaEntreTentativas) e RESERVA.
  --
  -- `reservada_ate` é o que impede duas instâncias de processarem a mesma linha. A reserva é feita
  -- num UPDATE ... RETURNING atômico, e não num SELECT ... FOR UPDATE SKIP LOCKED em autocommit —
  -- que solta o lock no fim do próprio SELECT e não protege nada. Se a instância morrer no meio, a
  -- reserva vence e outra retoma.
  --
  -- Sem `default now()` nas duas: a comparação é com o Relogio da aplicação (RN-INF-006).
  -- ---------------------------------------------------------------------
  tentativas           smallint not null default 0,
  proxima_tentativa_em timestamptz not null,
  reservada_ate        timestamptz,
  ultimo_erro          text,

  criado_em            timestamptz not null default now(),
  concluida_em         timestamptz,

  constraint transcricao_concluida_tem_texto
    check (status <> 'CONCLUIDA' or texto is not null),
  constraint transcricao_baixada_tem_arquivo
    check (status not in ('BAIXADA', 'CONCLUIDA') or arquivo_id is not null)
);

create index idx_transcricao_a_processar
  on transcricao_de_audio (proxima_tentativa_em)
  where status in ('PENDENTE', 'BAIXADA');

select aplicar_rls_tenant('transcricao_de_audio');

-- O que a cliente disse, e o que o sistema entendeu, não se apaga pela aplicação.
revoke delete on transcricao_de_audio from salao_app;

-- O processador reserva linhas de TODOS os salões: é o poder que salao_app não tem. Mesmo padrão do
-- despachante de mensagens (V30). Só a reserva usa esta role; o resultado é gravado dentro do
-- escopo do salão, pela conexão da aplicação.
create policy manutencao on transcricao_de_audio to salao_manutencao using (true);
grant select, update on transcricao_de_audio to salao_manutencao;
