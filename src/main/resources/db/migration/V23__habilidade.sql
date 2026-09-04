-- RT-EQP-004 — quais serviços cada profissional executa.

-- ---------------------------------------------------------------------------
-- A pergunta que decide esta rotina: lista VAZIA significa "faz tudo" ou
-- "não faz nada"?
--
-- Lista branca pura ("vazia = não faz nada") é a resposta segura e é a errada
-- aqui. Um salão com 6 profissionais e 40 serviços precisaria marcar 240
-- caixas antes do primeiro agendamento — e, pior, todo serviço NOVO nasceria
-- invisível para todo mundo até alguém lembrar de revisitar 6 cadastros. O
-- sintoma seria "por que a coloração nova não aparece na agenda?", em
-- silêncio, para sempre.
--
-- Lista negra pura ("vazia = faz tudo") deixa a recepção marcar progressiva
-- com quem só faz manicure, e a descoberta é no balcão com a cliente sentada.
--
-- A saída é não deixar o padrão implícito: uma CHAVE no profissional, que diz
-- em qual dos dois regimes ele está. `true` (o padrão) é o que faz um salão
-- novo funcionar no primeiro dia; virá-la é um ato deliberado, com
-- consequência visível na tela.
-- ---------------------------------------------------------------------------
alter table profissional
  add column executa_todos_os_servicos boolean not null default true;

comment on column profissional.executa_todos_os_servicos is
  'RN-EQP-013: true = faz todo o cardápio; false = só o que estiver em habilidade.';

create table habilidade (
  estabelecimento_id uuid not null references estabelecimento(id),
  profissional_id    uuid not null references profissional(id) on delete cascade,

  -- ---------------------------------------------------------------------
  -- SEM chave estrangeira para `servico`, e de propósito.
  --
  -- `servico` pertence ao módulo `catalogo`; `habilidade`, a `equipe`. Uma FK
  -- aqui costuraria os dois esquemas e deixaria um `on delete` de um módulo
  -- alterar dado do outro — a fronteira existiria no código e não no banco,
  -- que é o inverso do que este projeto faz em todo o resto.
  --
  -- Quem garante que o serviço existe e é deste salão é
  -- `DefinirHabilidadesUseCase`, via `ServicoApi`. E vale lembrar de RN-CAT-011:
  -- mesmo se a FK existisse ela NÃO separaria os salões, porque as checagens de
  -- integridade referencial do PostgreSQL não passam pela RLS. Aqui a checagem
  -- da aplicação não é reforço, é a única barreira — como já era lá.
  --
  -- Serviço não se apaga, se desativa (RT-CAT-001), então não há linha órfã a
  -- temer.
  -- ---------------------------------------------------------------------
  servico_id         uuid not null,

  criado_em          timestamptz not null default now(),
  criado_por         uuid,

  primary key (profissional_id, servico_id)
);

-- "Quem pode fazer coloração?" é a consulta da tela de marcação, e a chave
-- primária não a atende: ela começa pelo profissional.
create index idx_habilidade_por_servico on habilidade (servico_id);

select aplicar_rls_tenant('habilidade');
