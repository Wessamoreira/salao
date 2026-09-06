-- RT-ATD-001 — abrir comanda, do agendamento ou avulsa.

-- ---------------------------------------------------------------------------
-- A comanda é o documento do atendimento: é nela que os serviços viram
-- dinheiro, e é dela que sai a comissão. Aqui ela nasce vazia — item, desconto
-- e pagamento chegam nas rotinas seguintes.
--
-- `numero` é o que a recepção fala em voz alta ("a comanda 42 da Maria"). Por
-- isso é sequencial POR ESTABELECIMENTO e não um uuid: ninguém grita um uuid.
-- ---------------------------------------------------------------------------
create table comanda (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- Sem FK: cliente é de outro módulo (RN-EQP-015). E FK não separaria tenant
  -- de qualquer forma — a checagem de integridade referencial do PostgreSQL
  -- roda por fora da RLS (RT-CAT-003).
  cliente_id         uuid not null,

  numero             bigint not null,

  status             text not null default 'ABERTA'
                       check (status in ('ABERTA', 'FECHADA', 'ESTORNADA')),

  -- Como ela começou. Não é vínculo: uma comanda aberta pelo agendamento das
  -- 9h recebe também o serviço marcado para as 14h, porque é uma conta só.
  -- Qual item veio de qual agendamento é do item (RT-ATD-002).
  origem             text not null check (origem in ('AGENDAMENTO', 'AVULSA')),

  -- Sem `default now()`: o instante vem da aplicação, pelo port Relogio
  -- (RN-INF-006). O default do banco é um segundo relógio, e foi assim que
  -- RT-NOT-001 acabou com uma fila que não andava.
  aberta_em          timestamptz not null,

  -- Quem abriu. É a pergunta que a barra de comandas abertas faz quando uma
  -- delas está lá há três horas (R-UX-07).
  aberta_por         uuid,

  fechada_em         timestamptz,

  -- Zerados até RT-ATD-002 trazer os itens. Existem desde já porque é a
  -- soma gravada que o fechamento congela — recalcular do item a cada leitura
  -- é justamente o que o snapshot de RN-ATD-010 existe para não fazer.
  valor_bruto        numeric(19,4) not null default 0,
  valor_desconto     numeric(19,4) not null default 0,
  valor_liquido      numeric(19,4) not null default 0,

  versao             integer not null default 0,

  unique (estabelecimento_id, numero)
);

-- ---------------------------------------------------------------------------
-- UMA comanda aberta por cliente, e é o banco que garante.
--
-- Duas abertas para a mesma pessoa é o defeito que ninguém percebe na hora:
-- metade dos itens cai numa, metade na outra, e o salão cobra uma só. O
-- índice parcial faz "abrir de novo" devolver a que já existe em vez de criar
-- a segunda.
-- ---------------------------------------------------------------------------
create unique index uma_comanda_aberta_por_cliente
  on comanda (estabelecimento_id, cliente_id) where status = 'ABERTA';

-- A barra de comandas abertas do dia (R-UX-07): comanda esquecida é dinheiro
-- saindo pela porta, e ela precisa estar sempre à vista.
create index idx_comanda_aberta
  on comanda (estabelecimento_id, aberta_em) where status = 'ABERTA';

-- ---------------------------------------------------------------------------
-- O contador do número visível.
--
-- Não é `sequence`: sequence é global ao banco, e o número 42 do salão A
-- gastaria o 42 do salão B — além de contar para o vizinho quantas comandas
-- cada salão abre. Também não é `max(numero) + 1`, que duas recepcionistas
-- simultâneas leem igual.
--
-- Uma linha por salão, incrementada com `on conflict do update`: atômico num
-- comando só, e o lock é por salão. O preço é serializar as aberturas de um
-- mesmo salão — que é exatamente o que "sem buracos na numeração" custa, e um
-- salão abre dezenas de comandas por dia, não milhares por segundo.
-- ---------------------------------------------------------------------------
create table contador_de_comanda (
  estabelecimento_id uuid primary key references estabelecimento(id),
  proximo            bigint not null
);

select aplicar_rls_tenant('comanda');
select aplicar_rls_tenant('contador_de_comanda');
