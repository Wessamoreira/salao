-- RT-AGD-010, segunda metade — a vaga que abre, e quem a recepção chamou para ela.

-- ---------------------------------------------------------------------------
-- A vaga aberta (RN-AGD-009)
--
-- Gravada por quem cancela e por quem reagenda, na mesma transação: um
-- cancelamento sem a vaga não chega a existir.
--
-- Não há "fechada_em". A vaga deixa de aparecer quando o horário passa ou
-- quando ninguém da fila cabe mais, e as duas coisas são calculadas na leitura
-- — como a fila, que expira pela janela e não por limpeza (RN-AGD-023). Uma
-- coloração de duas horas preenchida por um corte de meia hora continua
-- aberta para a hora e meia que sobrou.
-- ---------------------------------------------------------------------------
create table vaga_aberta (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- Quem liberou o tempo. FK: é do mesmo módulo.
  agendamento_id     uuid not null references agendamento(id),
  origem             text not null check (origem in ('CANCELAMENTO', 'REAGENDAMENTO')),

  -- De quem era o tempo. Sem FK: profissional é de outro módulo.
  profissional_id    uuid not null,
  inicio             timestamptz not null,
  fim                timestamptz not null,

  aberta_em          timestamptz not null,

  constraint vaga_tem_duracao check (fim > inicio)
);

-- A leitura quente: as vagas que ainda não passaram.
create index idx_vaga_aberta_fim on vaga_aberta (estabelecimento_id, fim);

select aplicar_rls_tenant('vaga_aberta');

-- Fato consumado: a vaga abriu. Corrigir seria reescrever o que aconteceu.
revoke update, delete on vaga_aberta from salao_app;

-- ---------------------------------------------------------------------------
-- A chamada (RN-AGD-026, RN-AGD-027)
--
-- Uma linha por ligação, nunca editada: a trilha de uma vaga conta quem foi
-- chamada, a que horas, por quem, o que respondeu, e quem foi pulada e por quê.
-- ---------------------------------------------------------------------------
create table chamada_da_fila (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  vaga_id            uuid not null references vaga_aberta(id),
  espera_id          uuid not null references fila_espera(id),

  resultado          text not null check (resultado in ('ACEITOU', 'RECUSOU', 'NAO_ATENDEU')),

  -- O horário marcado quando ela aceitou. Aceitar é marcar: sem agendamento
  -- não há "aceitou", e só "aceitou" tem agendamento.
  agendamento_id     uuid references agendamento(id),

  -- Preenchido quando havia alguém antes na ordem ainda sem chamada nesta vaga.
  motivo_do_pulo     text check (motivo_do_pulo is null
                                 or char_length(motivo_do_pulo) between 3 and 300),

  registrada_em      timestamptz not null,
  registrada_por     uuid,

  constraint chamada_aceita_tem_agendamento
    check ((resultado = 'ACEITOU') = (agendamento_id is not null))
);

create index idx_chamada_da_vaga on chamada_da_fila (vaga_id, registrada_em);

-- Uma espera aceita uma vez: depois disso ela saiu da fila como atendida.
create unique index idx_chamada_aceita_uma_vez
  on chamada_da_fila (espera_id) where resultado = 'ACEITOU';

select aplicar_rls_tenant('chamada_da_fila');

revoke update, delete on chamada_da_fila from salao_app;

-- ---------------------------------------------------------------------------
-- A espera que veio de uma série (RT-AGD-009)
-- ---------------------------------------------------------------------------
alter table fila_espera add column recorrencia_id uuid references recorrencia(id);

-- ---------------------------------------------------------------------------
-- Uma espera por serviço e profissional em DIAS QUE SE SOBREPÕEM (RN-AGD-028)
--
-- O índice único antigo proibia a segunda espera em qualquer dia — e com isso
-- as três quintas de uma série que não couberam não podiam esperar, cada uma
-- no seu dia. Duas esperas sobrepostas seriam duas ligações pela mesma vaga;
-- em dias separados, não.
--
-- As linhas atuais já respeitam a regra nova: o índice antigo era mais forte.
-- ---------------------------------------------------------------------------
drop index idx_fila_sem_duplicata;

alter table fila_espera add constraint fila_sem_sobreposicao
  exclude using gist (
    estabelecimento_id with =,
    cliente_id with =,
    servico_id with =,
    (coalesce(profissional_id, '00000000-0000-0000-0000-000000000000'::uuid)) with =,
    daterange(aceita_de, aceita_ate, '[]') with &&)
  where (atendida_em is null and desistiu_em is null);
