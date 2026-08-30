-- RT-IAM-003 — refresh rotativo com detecção de reuso.

create table refresh_token (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  usuario_id         uuid not null references usuario(id),
  -- Todos os tokens de uma mesma cadeia de rotação compartilham a família. É ela
  -- que se revoga inteira quando um token usado reaparece: se um elo vazou, os
  -- descendentes dele estão na mão de alguém.
  familia_id         uuid not null,
  -- ---------------------------------------------------------------------------
  -- SHA-256, não Argon2 — e a diferença aqui é o oposto da senha.
  --
  -- Senha é escolhida por humano, tem pouca entropia e precisa de hash LENTO para
  -- resistir a força bruta. Refresh token é 256 bits de CSPRNG: não há dicionário
  -- nem padrão a explorar, então lentidão não protege de nada.
  --
  -- E há um impedimento prático: Argon2 usa sal por linha, o que torna impossível
  -- procurar por hash. Toda validação viraria varredura da tabela inteira.
  -- ---------------------------------------------------------------------------
  token_hash         text not null,
  emitido_em         timestamptz not null default now(),
  expira_em          timestamptz not null,
  -- Marcado na rotação. Reaparecer com usado_em preenchido é o sinal de vazamento.
  usado_em           timestamptz,
  revogado_em        timestamptz,
  motivo_revogacao   text,
  substituido_por    uuid references refresh_token(id),
  ip                 inet,
  user_agent         text
);

create unique index refresh_token_hash_unico on refresh_token (token_hash);
create index refresh_token_por_familia on refresh_token (estabelecimento_id, familia_id);
create index refresh_token_por_usuario on refresh_token (estabelecimento_id, usuario_id);
-- Para o expurgo: token vencido há muito não serve nem para auditoria.
create index refresh_token_por_expiracao on refresh_token (expira_em);

select aplicar_rls_tenant('refresh_token');

-- O refresh chega sem access token — a sessão expirada é justamente o motivo de
-- ele existir —, então o tenant só é conhecido DEPOIS de encontrar o token. Mesmo
-- padrão do login: uma consulta estreita pela role de plataforma, e todo o resto
-- dentro do escopo do tenant.
create policy manutencao on refresh_token to salao_manutencao using (true);
grant select, delete on refresh_token to salao_manutencao;
