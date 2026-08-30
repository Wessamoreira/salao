-- RT-IAM-002 — usuários e autenticação.

create table usuario (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),
  nome                text not null,
  email               text not null,
  -- Coluna separada para busca: o login é case-insensitive, mas o e-mail é
  -- exibido como a pessoa o escreveu. Guardar só o normalizado perderia isso;
  -- normalizar na consulta impediria o uso do índice único.
  email_normalizado   text not null,
  senha_hash          text not null,
  perfil              text not null check (perfil in
                        ('ADMIN','GERENTE','PROFISSIONAL','RECEPCAO','PAINEL','BOT')),
  ativo               boolean not null default true,
  falhas_consecutivas integer not null default 0,
  bloqueado_ate       timestamptz,
  ultimo_acesso_em    timestamptz,
  versao              integer not null default 0,
  criado_em           timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Unicidade GLOBAL, não por estabelecimento — e isto é uma decisão de produto,
-- não um descuido.
--
-- O login é só e-mail e senha: a pessoa não escolhe o salão numa lista antes de
-- entrar. Para isso funcionar, o e-mail precisa determinar o estabelecimento
-- sozinho. O preço: alguém que trabalhe em dois salões do mesmo sistema precisa
-- de dois e-mails.
--
-- A alternativa seria identificar o estabelecimento por subdomínio ou por um
-- campo a mais na tela de login — mais infraestrutura e mais fricção para um
-- caso raro. Se ele deixar de ser raro, esta é a decisão a revisitar.
-- ---------------------------------------------------------------------------
create unique index usuario_email_unico on usuario (email_normalizado);
create index usuario_por_estabelecimento on usuario (estabelecimento_id, ativo);

select aplicar_rls_tenant('usuario');

-- ---------------------------------------------------------------------------
-- O login precisa descobrir o estabelecimento A PARTIR do e-mail, ou seja,
-- antes de existir tenant no escopo. É um alcance cross-tenant legítimo, e vai
-- pela role de plataforma (ADR-0010) — o mesmo caminho do provisionamento.
--
-- SELECT apenas. Todo o resto do login (conferir senha, contar falhas, aplicar
-- bloqueio) acontece já dentro do escopo do tenant, pela conexão da aplicação.
-- Assim o alcance cross-tenant fica reduzido a uma única consulta estreita.
-- ---------------------------------------------------------------------------
create policy manutencao on usuario
  to salao_manutencao
  using      (true)
  with check (true);

-- insert também: o primeiro ADMIN nasce junto com o estabelecimento, na mesma
-- instrução (RT-IAM-001). Um tenant sem ninguém que possa entrar nele não é
-- entregável, e criar o admin depois, por outra conexão, deixaria uma janela em
-- que o estabelecimento existe e está inacessível.
grant select, insert on usuario to salao_manutencao;
