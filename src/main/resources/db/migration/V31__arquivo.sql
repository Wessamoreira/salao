-- RT-ARQ-001 — upload por presigned URL com validação por magic bytes.

-- ---------------------------------------------------------------------------
-- O arquivo é registrado ANTES de existir.
--
-- Com presigned URL, os bytes vão do navegador direto para o armazenamento e a
-- aplicação nunca os vê passar — que é o ponto: uma foto de cabelo tem alguns
-- megabytes, e proxiá-la queima memória e prende thread por upload.
--
-- O preço é perder o controle do momento da escrita. A linha criada antes é o
-- que devolve esse controle: ela dá a chave, guarda quem pediu, e existe para
-- ser confirmada depois. Sem ela, um arquivo no bucket seria um arquivo órfão
-- que ninguém sabe de quem é.
-- ---------------------------------------------------------------------------
create table arquivo (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- A que este arquivo pertence. Sem FK: cliente e profissional são de outros
  -- módulos (RN-EQP-015), e o dono pode ser de qualquer um deles.
  dono_tipo          text not null check (dono_tipo in ('CLIENTE', 'PROFISSIONAL')),
  dono_id            uuid not null,

  -- A chave no armazenamento. Gerada aqui e nunca derivada do nome enviado:
  -- nome de arquivo vem do cliente e aceita `../`, o que faria a chave apontar
  -- para fora do prefixo do salão.
  chave              text not null unique,

  -- O nome original, só para exibir. Nunca usado para montar caminho nem para
  -- decidir tipo.
  nome_original      text,

  -- ---------------------------------------------------------------------
  -- `tipo` é preenchido na CONFIRMAÇÃO, a partir dos bytes — nunca do que o
  -- cliente declarou. Fica nulo enquanto o upload não foi validado, e é essa
  -- coluna que o download usa para montar o Content-Type de saída.
  -- ---------------------------------------------------------------------
  tipo               text check (tipo in ('JPEG', 'PNG', 'HEIC', 'WEBP', 'PDF')),
  tamanho_bytes      bigint,

  status             text not null default 'AGUARDANDO' check (status in
                       ('AGUARDANDO', 'VALIDADO', 'RECUSADO')),
  motivo_recusa      text,

  criado_em          timestamptz not null default now(),
  criado_por         uuid,
  confirmado_em      timestamptz
);

-- "Quais arquivos são desta cliente?" é a consulta da ficha, e a que a
-- anonimização (RN-CLI-007) precisa para saber o que apagar.
create index idx_arquivo_do_dono
  on arquivo (estabelecimento_id, dono_tipo, dono_id, criado_em desc);

-- O que ficou aguardando confirmação e nunca chegou: é o que o expurgo remove.
create index idx_arquivo_aguardando
  on arquivo (criado_em) where status = 'AGUARDANDO';

select aplicar_rls_tenant('arquivo');
