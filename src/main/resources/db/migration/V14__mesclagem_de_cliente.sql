-- RT-CLI-003 — mesclagem de cliente duplicado.

-- ---------------------------------------------------------------------------
-- Mesclar é REDIRECIONAR, não apagar.
--
-- A ficha absorvida continua existindo e passa a apontar para a sobrevivente.
-- O motivo é que qualquer coisa que já guardou este `cliente_id` — agendamento,
-- comanda, extrato de comissão, e o que a Fase 2 ainda vai criar — continua
-- resolvendo para a pessoa certa sem precisar ser reescrita no momento da
-- mesclagem.
--
-- Apagar exigiria encontrar e repontar TODAS as referências numa transação só,
-- e a que fosse esquecida viraria um registro órfão apontando para uma ficha
-- que não existe mais. O redirecionamento falha do lado seguro: uma referência
-- esquecida continua achando a pessoa.
-- ---------------------------------------------------------------------------
alter table cliente
  add column mesclado_para uuid references cliente(id),
  add column mesclado_em   timestamptz,
  -- Os dois andam juntos ou nenhum: uma ficha "mesclada" sem destino seria uma
  -- pessoa que sumiu da busca e não aponta para lugar nenhum.
  add constraint cliente_mesclagem_completa check (
    (mesclado_para is null and mesclado_em is null)
    or (mesclado_para is not null and mesclado_em is not null)
  ),
  -- Uma ficha não se absorve.
  add constraint cliente_nao_mescla_em_si check (mesclado_para is null or mesclado_para <> id);

-- Quem foi absorvido some da busca e do cadastro; o índice parcial reflete isso.
drop index idx_cliente_busca;
drop index idx_cliente_telefone_reverso;
drop index idx_cliente_telefone;
drop index idx_cliente_nome;

create index idx_cliente_busca on cliente using gin (busca gin_trgm_ops)
  where anonimizado_em is null and mesclado_para is null;

create index idx_cliente_telefone_reverso
  on cliente (reverse(telefone) text_pattern_ops)
  where telefone is not null and anonimizado_em is null and mesclado_para is null;

create index idx_cliente_telefone on cliente (estabelecimento_id, telefone)
  where telefone is not null and anonimizado_em is null and mesclado_para is null;

create index idx_cliente_nome on cliente (estabelecimento_id, nome)
  where anonimizado_em is null and mesclado_para is null;

-- Achar quem aponta para uma ficha: usado para manter as cadeias com um salto só.
create index idx_cliente_mesclado_para on cliente (mesclado_para)
  where mesclado_para is not null;
