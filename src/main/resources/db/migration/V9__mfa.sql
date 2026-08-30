-- RT-IAM-005 — segundo fator (TOTP).

alter table usuario add column mfa_ativo boolean not null default false;

create table mfa_credencial (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  usuario_id         uuid not null references usuario(id),
  -- IV || texto cifrado (AES-256-GCM). Cifrado porque o segredo TOTP é
  -- equivalente ao segundo fator inteiro: quem o obtém gera códigos válidos
  -- para sempre. Um dump do banco não pode entregá-lo em claro.
  segredo_cifrado    bytea not null,
  -- Nulo enquanto a pessoa não confirmou com um código. Gerar o segredo não
  -- ativa o MFA: sem confirmar, alguém poderia se trancar para fora ao errar
  -- a configuração do autenticador.
  confirmado_em      timestamptz,
  -- Última janela usada com sucesso. É o que impede reapresentar o mesmo código
  -- dentro dos seus 30 segundos de validade.
  ultimo_contador    bigint,
  criado_em          timestamptz not null default now(),
  constraint mfa_credencial_por_usuario unique (usuario_id)
);

create table mfa_codigo_recuperacao (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  usuario_id         uuid not null references usuario(id),
  -- SHA-256: são 160 bits de CSPRNG, mesma lógica do refresh token. Não há
  -- dicionário a explorar, e a busca precisa ser por índice.
  codigo_hash        text not null,
  usado_em           timestamptz,
  criado_em          timestamptz not null default now()
);

create unique index mfa_codigo_recuperacao_hash on mfa_codigo_recuperacao (codigo_hash);
create index mfa_codigo_recuperacao_por_usuario
    on mfa_codigo_recuperacao (estabelecimento_id, usuario_id) where usado_em is null;

select aplicar_rls_tenant('mfa_credencial');
select aplicar_rls_tenant('mfa_codigo_recuperacao');

-- O segundo fator é conferido depois da senha, quando o tenant já é conhecido
-- pelo desafio — não há alcance cross-tenant aqui, e por isso nenhuma policy
-- de manutenção. A exceção é o expurgo, que não se aplica: código de
-- recuperação usado é registro de auditoria e fica.
