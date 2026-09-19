-- RT-FIN-009 — o lado da maquininha: o relatório de vendas da Stone, importado.

-- ===========================================================================
-- Cada importação, com o que aconteceu a cada linha
--
-- O arquivo não fica guardado: a Lista de vendas pode trazer o cartão mascarado, e a conciliação não
-- precisa dele. Fica a impressão SHA-256 — prova de qual arquivo entrou — e as contagens.
-- ===========================================================================
create table importacao_de_extrato (
  id                    uuid primary key,
  estabelecimento_id    uuid not null references estabelecimento(id),
  adquirente            text not null check (adquirente in ('STONE')),
  impressao_do_arquivo  text not null check (impressao_do_arquivo ~ '^[0-9a-f]{64}$'),
  nome_do_arquivo       text check (char_length(nome_do_arquivo) <= 200),
  importada_em          timestamptz not null,
  importada_por         uuid,

  linhas                integer not null check (linhas >= 0),
  novas                 integer not null check (novas >= 0),
  canceladas            integer not null check (canceladas >= 0),
  repetidas             integer not null check (repetidas >= 0),
  ignoradas             integer not null check (ignoradas >= 0),
  recusadas             integer not null check (recusadas >= 0),
  -- As primeiras 500, com linha e motivo. O total está em `recusadas`.
  recusas               jsonb not null,
  -- Qual coluna foi lida como qual campo: sem isto, "por que este valor?" não teria resposta.
  mapeamento            jsonb not null,

  constraint contas_fecham check (novas + canceladas + repetidas + ignoradas + recusadas = linhas)
);

create index idx_importacao_de_extrato_do_salao
  on importacao_de_extrato (estabelecimento_id, importada_em desc, id desc);
create index idx_importacao_de_extrato_por_arquivo
  on importacao_de_extrato (estabelecimento_id, impressao_do_arquivo);

select aplicar_rls_tenant('importacao_de_extrato');
revoke update, delete on importacao_de_extrato from salao_app;

-- ===========================================================================
-- A venda como a adquirente a descreve (ADR-0007: independente de ter vindo de arquivo ou de API)
-- ===========================================================================
create table transacao_do_adquirente (
  id                      uuid primary key default gen_random_uuid(),
  estabelecimento_id      uuid not null references estabelecimento(id),
  adquirente              text not null check (adquirente in ('STONE')),
  id_na_adquirente        text not null check (char_length(id_na_adquirente) between 1 and 64),
  nsu                     text check (char_length(nsu) <= 40),
  autorizacao             text check (char_length(autorizacao) <= 40),

  -- A hora que o portal mostra é a do salão; aqui ela já é instante (ADR-0009). Sem hora no arquivo,
  -- meia-noite do dia — e `hora_informada` diz isso, para o casamento não usar janela de tempo nela.
  capturada_em            timestamptz not null,
  hora_informada          boolean not null,

  produto                 text check (produto in ('CREDITO', 'DEBITO', 'OUTRO')),
  bandeira                text check (char_length(bandeira) <= 40),
  parcelas                smallint not null check (parcelas between 1 and 99),
  valor_bruto             numeric(19, 4) not null check (valor_bruto > 0),
  valor_liquido           numeric(19, 4) check (valor_liquido >= 0),

  situacao                text not null check (situacao in ('APROVADA', 'CANCELADA')),
  cancelada_em            timestamptz,

  -- Adiadas: a importação é gravada depois das linhas, já com as contagens finais.
  primeira_importacao_id  uuid not null
                            references importacao_de_extrato(id) deferrable initially deferred,
  ultima_importacao_id    uuid not null
                            references importacao_de_extrato(id) deferrable initially deferred,
  criada_em               timestamptz not null,

  constraint uma_transacao_por_id unique (estabelecimento_id, adquirente, id_na_adquirente),
  constraint cancelada_tem_data check ((situacao = 'CANCELADA') = (cancelada_em is not null))
);

-- O que o casamento (RT-FIN-010) vai varrer: vendas aprovadas, por período.
create index idx_transacao_do_adquirente_a_casar
  on transacao_do_adquirente (estabelecimento_id, capturada_em)
  where situacao = 'APROVADA';

select aplicar_rls_tenant('transacao_do_adquirente');

-- O valor não se reescreve: o mesmo Stone ID com outro valor é recusado na importação. Só a situação
-- anda — aprovada vira cancelada — e a última importação que a tocou.
revoke update, delete on transacao_do_adquirente from salao_app;
grant update (situacao, cancelada_em, ultima_importacao_id) on transacao_do_adquirente to salao_app;

-- ===========================================================================
-- O mapeamento que o salão confirmou, pelo nome da coluna
-- ===========================================================================
create table mapeamento_de_extrato (
  estabelecimento_id  uuid not null references estabelecimento(id),
  adquirente          text not null check (adquirente in ('STONE')),
  colunas             jsonb not null,
  formato_de_numero   text not null check (formato_de_numero in ('BRASILEIRO', 'INTERNACIONAL')),
  atualizado_em       timestamptz not null,

  primary key (estabelecimento_id, adquirente)
);

select aplicar_rls_tenant('mapeamento_de_extrato');
revoke delete on mapeamento_de_extrato from salao_app;
