-- RT-AGD-010 — "me avisa se abrir vaga".

-- ---------------------------------------------------------------------------
-- A cliente quer quinta às 14h e está tomado. Hoje a recepção anota num papel
-- ao lado do telefone — e o papel é justamente o que se perde quando a vaga
-- abre uma semana depois, com outra pessoa no balcão.
--
-- A janela é um INTERVALO DE DIAS mais uma faixa de hora opcional, e não um
-- horário exato. "Quinta que vem à tarde" é o que a cliente diz; exigir que
-- ela escolha 14h ou 14h30 transformaria a fila numa lista de horários
-- específicos que quase nunca abrem exatamente.
-- ---------------------------------------------------------------------------
create table fila_espera (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- Sem FK: cliente, serviço e profissional são de outros módulos.
  cliente_id         uuid not null,
  servico_id         uuid not null,
  profissional_id    uuid,

  -- De que dia até que dia a cliente aceita.
  aceita_de          date not null,
  aceita_ate         date not null,

  -- Faixa de hora no dia; nula dos dois lados significa "qualquer horário".
  hora_de            time,
  hora_ate           time,

  observacao         text,
  criado_em          timestamptz not null default now(),
  criado_por         uuid,

  -- Saiu da fila. Duas saídas, e a diferença importa no relatório: uma é o
  -- sistema funcionando, a outra é a cliente cansando de esperar.
  atendida_em        timestamptz,
  desistiu_em        timestamptz,

  constraint fila_janela_valida check (aceita_ate >= aceita_de),
  constraint fila_hora_valida check (
    (hora_de is null and hora_ate is null)
    or (hora_de is not null and hora_ate is not null and hora_ate > hora_de)),
  constraint fila_saida_unica check (atendida_em is null or desistiu_em is null)
);

-- ---------------------------------------------------------------------------
-- A mesma cliente esperando duas vezes pelo mesmo serviço seria chamada duas
-- vezes pela mesma vaga — e a segunda ligação é a que faz o salão parecer
-- desorganizado justamente com quem já está esperando.
--
-- Índice parcial: a restrição vale só enquanto ela está na fila. Depois de
-- atendida ou desistida, entrar de novo é legítimo.
-- ---------------------------------------------------------------------------
create unique index idx_fila_sem_duplicata
  on fila_espera (estabelecimento_id, cliente_id, servico_id,
                  coalesce(profissional_id, '00000000-0000-0000-0000-000000000000'::uuid))
  where atendida_em is null and desistiu_em is null;

-- A consulta quente: "abriu esta vaga, quem cabe?"
create index idx_fila_aberta
  on fila_espera (estabelecimento_id, servico_id, aceita_de, aceita_ate)
  where atendida_em is null and desistiu_em is null;

select aplicar_rls_tenant('fila_espera');
