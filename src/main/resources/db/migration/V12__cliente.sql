-- RT-CLI-001 — cadastro de cliente.

-- ---------------------------------------------------------------------------
-- O DDD padrão pertence ao estabelecimento (módulo iam), e é lido pelo módulo
-- cliente através de EstabelecimentoApi — nunca por join.
--
-- Existe porque quem atende o telefone anota "98765-4321" e não o DDD: ele é
-- óbvio para quem está ali, e obrigar a digitá-lo é fricção que faz a recepção
-- inventar dado.
-- ---------------------------------------------------------------------------
alter table estabelecimento
  add column pais_padrao text not null default '55',
  add column ddd_padrao  text;

create table cliente (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  nome               text not null,
  -- Como a pessoa é chamada no salão. "Dona Zilda" é o que a recepção procura,
  -- e raramente é o nome do documento.
  apelido            text,
  -- E.164 sem o '+': dígitos apenas. Guardar formatado tornaria a busca por
  -- sufixo (R-UX-03) dependente de como cada pessoa digitou a máscara.
  telefone           text,
  email              text,
  observacao         text,
  -- Anonimização da LGPD (RT-CLI-005) preenche isto em vez de apagar a linha:
  -- o histórico financeiro precisa continuar apontando para alguém.
  anonimizado_em     timestamptz,
  versao             integer not null default 0,
  criado_em          timestamptz not null default now(),
  criado_por         uuid,
  constraint cliente_nome_nao_vazio check (length(trim(nome)) > 0)
);

-- ---------------------------------------------------------------------------
-- NÃO existe unique em telefone, e isso é decisão de domínio.
--
-- Mãe e filha compartilham o número o tempo todo num salão; marido e esposa
-- também. Uma unique aqui recusaria cadastros legítimos e ensinaria a recepção
-- a inventar um número falso para contornar — que é pior que o duplicado.
--
-- Duplicado é tratado por DETECÇÃO no cadastro (RT-CLI-003), não por proibição.
-- ---------------------------------------------------------------------------
create index idx_cliente_telefone on cliente (estabelecimento_id, telefone)
  where telefone is not null and anonimizado_em is null;
create index idx_cliente_nome on cliente (estabelecimento_id, nome)
  where anonimizado_em is null;

select aplicar_rls_tenant('cliente');
