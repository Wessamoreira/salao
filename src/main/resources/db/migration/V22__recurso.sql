-- RT-CAT-003 — recurso: o que o salão tem em número limitado.

-- ---------------------------------------------------------------------------
-- Um recurso é uma coisa CONTÁVEL do salão: lavatório, cadeira, sala.
--
-- Sem ele, a agenda vê a profissional livre às 15h e marca — e às 15h três
-- colorações chegam juntas na lavagem, com dois lavatórios. Duas clientes
-- esperam sentadas com tinta no cabelo, e o horário que o sistema prometeu
-- nunca existiu.
--
-- É a mesma economia dos blocos ATIVO/PAUSA (RT-CAT-001) aplicada à outra
-- coisa escassa: lá o gargalo é a pessoa, aqui é o equipamento.
-- ---------------------------------------------------------------------------
create table recurso (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),
  nome               text not null,

  -- ---------------------------------------------------------------------
  -- Quantas unidades intercambiáveis. "Lavatório" com quantidade 3 é o
  -- conjunto dos lavatórios: ninguém escolhe qual, só importa se sobra um.
  --
  -- Não são três linhas ("Lavatório 1", "Lavatório 2"...) porque o salão não
  -- distingue as unidades — nomeá-las criaria um dado que ninguém consulta e
  -- uma escolha que a recepção não sabe fazer. Trocar 2 por 3 é uma edição.
  --
  -- Mínimo 1, de propósito: quantidade zero e `ativo = false` diriam a mesma
  -- coisa de duas formas, e duas formas de dizer a mesma coisa é o começo de
  -- um relatório que não fecha (a mesma razão de RN-CAT-003).
  -- ---------------------------------------------------------------------
  quantidade         smallint not null check (quantidade between 1 and 99),

  ativo              boolean not null default true,
  versao             integer not null default 0,
  criado_em          timestamptz not null default now(),
  criado_por         uuid,

  constraint recurso_nome_nao_vazio check (length(trim(nome)) > 0)
);

-- Dois "Lavatório" no cadastro fariam metade dos serviços exigir um e metade o
-- outro, e a conta de disponibilidade daria o dobro da capacidade real.
create unique index idx_recurso_nome
  on recurso (estabelecimento_id, lower(nome));

-- ---------------------------------------------------------------------------
-- A exigência mora no BLOCO, não no serviço, e é aqui que a rotina se paga.
--
-- Coloração ocupa o lavatório só na lavagem (30 min de 100). Pendurar a
-- exigência no serviço reservaria o lavatório pelos 100 — exatamente o
-- desperdício que os blocos ATIVO/PAUSA existem para eliminar, só que movido
-- para outra coisa escassa.
--
-- E o inverso importa tanto quanto: um bloco PAUSA pode exigir recurso. Durante
-- os 40 minutos da química, a profissional está livre mas a cliente continua
-- sentada na CADEIRA. Um modelo que não saiba dizer isso vende a mesma cadeira
-- duas vezes.
-- ---------------------------------------------------------------------------
create table bloco_exige_recurso (
  estabelecimento_id uuid not null references estabelecimento(id),
  bloco_id           uuid not null references bloco_do_servico(id) on delete cascade,

  -- Sem `on delete cascade`: recurso não se apaga, se desativa — e desativar um
  -- que alguma receita exige é recusado (RN-CAT-012).
  recurso_id         uuid not null references recurso(id),

  primary key (bloco_id, recurso_id)
);

-- A pergunta reversa — "quais serviços exigem este recurso?" — é o que sustenta
-- a recusa de desativação, e a chave primária não a atende.
create index idx_bloco_exige_recurso_reverso on bloco_exige_recurso (recurso_id);

-- ---------------------------------------------------------------------------
-- ATENÇÃO, e é por isto que existe validação no caso de uso: no PostgreSQL as
-- checagens de integridade referencial rodam como o dono da tabela e NÃO passam
-- pela RLS. A FK acima aceitaria um `recurso_id` de outro salão sem reclamar.
--
-- Quem impede é `CadastrarServicoUseCase`, que confere os recursos com uma
-- consulta normal — sujeita à política de tenant — antes de gravar. O teste
-- `recurso_de_outro_salao_nao_serve` prova as duas metades: que a tentativa é
-- recusada, e que a FK sozinha teria deixado passar.
-- ---------------------------------------------------------------------------
select aplicar_rls_tenant('recurso');
select aplicar_rls_tenant('bloco_exige_recurso');
