-- RT-EQP-001 — cadastro de profissional.

create table profissional (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  nome               text not null,
  -- Como é chamado na agenda e pela cliente. "Nice" cabe na coluna da grade; o nome
  -- completo, não.
  apelido            text,
  -- Só dígitos, como o telefone (RN-CLI-001). Máscara é assunto de quem exibe.
  documento          text not null,
  tipo_documento     text not null check (tipo_documento in ('CPF', 'CNPJ')),
  tipo_vinculo       text not null check (tipo_vinculo in ('PARCEIRO','EMPREGADO','AUTONOMO')),
  telefone           text,
  email              text,

  -- ---------------------------------------------------------------------
  -- Comissão em pontos percentuais: 45.00 é 45%.
  --
  -- numeric, nunca ponto flutuante: metade de um por cento sobre o faturamento
  -- de um ano é dinheiro de verdade, e o erro de arredondamento aparece como
  -- centavo que não fecha no extrato do profissional.
  -- ---------------------------------------------------------------------
  comissao_percentual numeric(5,2) not null default 0
    check (comissao_percentual >= 0 and comissao_percentual <= 100),

  -- ---------------------------------------------------------------------
  -- Contrato.
  --
  -- `contrato_assinado_em` existe por causa da Lei 13.352/2016: a parceria só
  -- se sustenta com contrato ESCRITO, homologado pelo sindicato ou assinado
  -- diante de duas testemunhas. Sem ele, a relação pode ser reclassificada
  -- como emprego, com encargos retroativos.
  --
  -- É anulável de propósito. Bloquear o cadastro por falta do contrato faria o
  -- salão não cadastrar o profissional — e aí o sistema perde o profissional E
  -- continua sem o contrato. Registrar a ausência é o que permite avisar.
  -- ---------------------------------------------------------------------
  contrato_inicio      date,
  contrato_fim         date,
  contrato_assinado_em date,

  -- Quem executa serviço tem histórico: desativa, nunca apaga (como usuário).
  ativo      boolean not null default true,
  versao     integer not null default 0,
  criado_em  timestamptz not null default now(),
  criado_por uuid,

  constraint profissional_nome_nao_vazio check (length(trim(nome)) > 0),
  constraint profissional_contrato_coerente check (
    contrato_fim is null or contrato_inicio is null or contrato_fim >= contrato_inicio
  ),
  -- Parceiro é pessoa jurídica pela lei; empregado e autônomo são pessoa física.
  -- No banco também, e não só no domínio: o dia em que alguém inserir por script,
  -- a regra continua valendo.
  constraint profissional_documento_do_vinculo check (
    (tipo_vinculo = 'PARCEIRO' and tipo_documento = 'CNPJ')
    or (tipo_vinculo in ('EMPREGADO','AUTONOMO') and tipo_documento = 'CPF')
  )
);

-- O mesmo documento não se cadastra duas vezes no mesmo salão. Diferente do telefone
-- do cliente (RN-CLI-005), aqui a unicidade é real: duas pessoas não compartilham CPF,
-- e duas fichas com o mesmo CNPJ dividiriam a comissão de um só em dois extratos.
create unique index idx_profissional_documento
  on profissional (estabelecimento_id, documento);

create index idx_profissional_ativo on profissional (estabelecimento_id, nome)
  where ativo;

select aplicar_rls_tenant('profissional');
