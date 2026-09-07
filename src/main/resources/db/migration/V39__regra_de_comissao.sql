-- RT-FIN-002 — a cascata de comissão.

-- ---------------------------------------------------------------------------
-- PRIMEIRO: desfazer a coerção que faz a cascata nascer decorativa.
--
-- `comissao_percentual` era `not null default 0`, e o cadastro convertia nulo em
-- zero. Com isso TODO profissional tem um percentual — e o nível "padrão do
-- salão", logo abaixo dele na cascata, nunca alcançaria ninguém.
--
-- É a mesma distinção que este projeto já fez três vezes por outros motivos:
-- `precoEm` devolve vazio e não zero porque "zero é um preço"; `taxa_prevista`
-- é nula porque "zero seria taxa isenta"; `custo_medio` é nulo sem estoque
-- porque "zero significaria unidades que não custaram nada".
--
-- Aqui a frase é a mesma: ZERO É UMA COMISSÃO — a de quem trabalha por salário
-- fixo —, e "ninguém configurou" precisa ser outra coisa.
--
-- As linhas existentes NÃO são convertidas. Um zero gravado pode ter sido
-- deliberado, e reescrever intenção alheia é pior do que deixar o campo como
-- está: quem quiser usar a cascata limpa o campo.
-- ---------------------------------------------------------------------------
alter table profissional alter column comissao_percentual drop default;
alter table profissional alter column comissao_percentual drop not null;

comment on column profissional.comissao_percentual is
  'RT-FIN-002: NULO significa "usa a cascata". Zero é uma comissão de zero por cento.';

-- ---------------------------------------------------------------------------
-- A cascata.
--
-- Três níveis, do mais específico para o mais geral:
--
--   PROFISSIONAL_SERVICO  "a Ana faz coloração por 45%"
--   SERVICO               "coloração paga 30%, o material é caro"
--   (ficha do profissional) "a Ana ganha 40% em tudo"      ← equipe, não aqui
--   SALAO                 "o padrão da casa é 30%"
--
-- SERVICO vence a FICHA de propósito, e a razão é que sem isso o nível seria
-- decoração: toda profissional tem percentual na ficha, então uma regra por
-- serviço só alcançaria quem estivesse com o campo vazio. Uma regra de serviço
-- existe para proteger a margem de um serviço caro — e é justamente nos
-- serviços caros que trabalham as pessoas com percentual alto.
--
-- Quem quiser a exceção tem o nível mais específico, que existe para isso.
-- ---------------------------------------------------------------------------
create table regra_comissao (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  escopo             text not null check (escopo in
                       ('SALAO', 'SERVICO', 'PROFISSIONAL_SERVICO')),

  -- Sem FK: profissional e serviço são de outros módulos (RN-EQP-015).
  profissional_id    uuid,
  servico_id         uuid,

  percentual         numeric(7,4) not null
                       check (percentual >= 0 and percentual <= 100),

  criado_em          timestamptz not null,
  criado_por         uuid,

  -- Cada escopo exige exatamente os ids que ele nomeia. Sem isso caberia uma
  -- regra de escopo SALAO com profissional preenchido — e a cascata teria de
  -- adivinhar o que ela quis dizer.
  constraint escopo_tem_os_ids check (
    (escopo = 'SALAO' and profissional_id is null and servico_id is null)
    or (escopo = 'SERVICO' and profissional_id is null and servico_id is not null)
    or (escopo = 'PROFISSIONAL_SERVICO'
        and profissional_id is not null and servico_id is not null))
);

-- ---------------------------------------------------------------------------
-- Uma regra por combinação. `nulls not distinct` porque, no padrão do SQL,
-- dois nulos não são iguais — e sem isso caberiam infinitas regras de escopo
-- SALAO, todas com os mesmos nulos, e a cascata escolheria uma ao acaso.
-- ---------------------------------------------------------------------------
create unique index uma_regra_por_combinacao
  on regra_comissao (estabelecimento_id, escopo, profissional_id, servico_id)
  nulls not distinct;

select aplicar_rls_tenant('regra_comissao');
