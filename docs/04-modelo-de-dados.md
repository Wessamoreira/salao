# Modelo de dados

DDL de referência. A verdade é a migration Flyway; este documento explica **por que** cada
decisão está lá. Todas as tabelas de negócio têm `estabelecimento_id` e RLS — sem exceção.

## Convenções

- `uuid` como PK (`gen_random_uuid()`), nunca serial — o ID viaja em URL e webhook.
- `timestamptz` sempre. `date` só quando o conceito é dia civil (competência, validade).
- Dinheiro em `numeric(19,4)`. Percentual em `numeric(7,4)` (0.4000 = 40%).
- Enum como `text` + `check`, não `enum` nativo — alterar enum nativo trava a tabela.
- `criado_em`, `criado_por`, `versao` em toda tabela mutável.
- Nome de tabela no singular (`agendamento`), coerente com o agregado.

## Fundação

```sql
create extension if not exists btree_gist;
create extension if not exists pg_trgm;      -- busca de cliente por nome
create extension if not exists unaccent;     -- "joao" acha "João"

create table estabelecimento (
  id                       uuid primary key default gen_random_uuid(),
  nome                     text not null,
  documento                text,                                   -- CNPJ
  timezone                 text not null default 'America/Sao_Paulo',
  moeda                    char(3) not null default 'BRL',
  base_comissao            text not null default 'BRUTO'
                             check (base_comissao in ('BRUTO','LIQUIDO')),
  desconto_afeta_comissao  boolean not null default false,
  periodicidade_fechamento text not null default 'MENSAL'
                             check (periodicidade_fechamento in ('SEMANAL','QUINZENAL','MENSAL')),
  ativo                    boolean not null default true,
  criado_em                timestamptz not null default now()
);
```

`timezone` por estabelecimento é o que sustenta D1 + D6 juntos. `base_comissao` e
`desconto_afeta_comissao` transformam as perguntas em aberto 1 e 2 em configuração — respondê-las
deixa de bloquear a Fase 2.

## Agenda — o núcleo do sistema

O agendamento é uma **cabeça** com N **blocos**. Sobreposição é proibida no bloco, não na cabeça.

```sql
create table agendamento (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),
  cliente_id          uuid not null,
  profissional_id     uuid not null,
  servico_id          uuid not null,
  status              text not null check (status in
                        ('SOLICITADO','CONFIRMADO','EM_ATENDIMENTO','CONCLUIDO','CANCELADO','NO_SHOW')),
  inicio              timestamptz not null,     -- span total, para índice e listagem
  fim                 timestamptz not null,
  origem              text not null check (origem in ('WEB','WHATSAPP','RECORRENCIA','PAINEL')),
  recorrencia_id      uuid,
  observacao          text,
  motivo_cancelamento text,
  versao              integer not null default 0,
  criado_em           timestamptz not null default now(),
  criado_por          uuid,
  constraint agendamento_periodo_valido check (fim > inicio)
);

create table agendamento_bloco (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null,
  agendamento_id      uuid not null references agendamento(id) on delete cascade,
  ordem               smallint not null,
  tipo                text not null check (tipo in ('ATIVO','PAUSA')),
  profissional_id     uuid,        -- null = não ocupa o profissional (pausa de química)
  recurso_id          uuid,        -- null = não ocupa recurso
  periodo             tstzrange not null,
  status              text not null,   -- desnormalizado da cabeça; ver nota abaixo
  constraint bloco_periodo_nao_vazio check (not isempty(periodo))
);

alter table agendamento_bloco
  add constraint bloco_sem_sobreposicao_profissional
  exclude using gist (estabelecimento_id with =, profissional_id with =, periodo with &&)
  where (profissional_id is not null
         and status in ('SOLICITADO','CONFIRMADO','EM_ATENDIMENTO'));

alter table agendamento_bloco
  add constraint bloco_sem_sobreposicao_recurso
  exclude using gist (estabelecimento_id with =, recurso_id with =, periodo with &&)
  where (recurso_id is not null
         and status in ('SOLICITADO','CONFIRMADO','EM_ATENDIMENTO'));
```

**Por que blocos:** um serviço de coloração é `ATIVO 30min → PAUSA 40min → ATIVO 30min`. Durante a
pausa o profissional está livre para um encaixe, mas o cliente continua ocupando a cadeira. Com um
único `tstzrange` por agendamento, o encaixe seria rejeitado pelo banco e a cadeira ficaria livre —
os dois errados. Blocos com `profissional_id` e `recurso_id` anuláveis expressam exatamente isso.

**Trade-off assumido — `status` desnormalizado no bloco.** A constraint parcial precisa do status na
mesma linha; Postgres não permite subquery em `EXCLUDE`. Consequência: cancelar um agendamento
**tem que** atualizar o status de todos os seus blocos na mesma transação. Isso fica no agregado
`Agendamento`, nunca em trigger, e tem teste dedicado
(`AgendamentoIT.cancelar_libera_todos_os_blocos`). Alternativa descartada: apagar os blocos no
cancelamento — perde-se o histórico do que estava reservado, que é exatamente o que se quer
auditar em disputa com cliente.

**A aplicação valida antes por UX; o banco garante.** Trava de aplicação não resiste a duas
instâncias concorrentes. A violação da constraint volta como erro genérico do driver e **precisa
ser mapeada pelo nome da constraint** para `ER-AGD-CONFLITO_HORARIO` — sem isso, a corrida
legítima de dois agendamentos vira HTTP 500.

```sql
create table bloqueio (
  id uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null,
  profissional_id uuid, recurso_id uuid,
  motivo text not null check (motivo in ('ALMOCO','FERIAS','CURSO','FERIADO','PESSOAL','MANUTENCAO')),
  periodo tstzrange not null,
  descricao text,
  criado_em timestamptz not null default now()
);
-- o bloqueio participa da MESMA exclusão do profissional/recurso:
-- ou vira linha em agendamento_bloco com agendamento_id nulo, ou tem constraint espelhada.
-- Decisão: vira agendamento_bloco com tipo='BLOQUEIO'. Uma constraint, um lugar para errar.
```

Índices obrigatórios no dia 1:

```sql
create index on agendamento (estabelecimento_id, profissional_id, inicio);
create index on agendamento (estabelecimento_id, cliente_id, inicio desc);
create index on agendamento (estabelecimento_id, inicio) where status <> 'CANCELADO';
create index on agendamento_bloco using gist (estabelecimento_id, periodo);
```

## Atendimento

```sql
create table comanda (
  id uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null,
  cliente_id uuid not null,
  numero bigint not null,               -- sequencial por estabelecimento, visível ao usuário
  status text not null check (status in ('ABERTA','FECHADA','ESTORNADA')),
  aberta_em timestamptz not null default now(),
  fechada_em timestamptz,
  valor_bruto numeric(19,4) not null default 0,
  valor_desconto numeric(19,4) not null default 0,
  valor_liquido numeric(19,4) not null default 0,
  versao integer not null default 0,
  unique (estabelecimento_id, numero)
);

create table item_comanda (
  id uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null,
  comanda_id uuid not null references comanda(id),
  tipo text not null check (tipo in ('SERVICO','PRODUTO')),
  servico_id uuid, produto_id uuid, agendamento_id uuid,
  descricao text not null,              -- snapshot: nome no momento da venda
  quantidade numeric(12,3) not null default 1,
  preco_unitario numeric(19,4) not null,      -- SNAPSHOT (RN-ATD-010)
  desconto numeric(19,4) not null default 0,
  valor_total numeric(19,4) not null,
  custo_medio_unitario numeric(19,4),         -- snapshot p/ margem, produto apenas
  constraint item_tem_referencia check (
    (tipo='SERVICO' and servico_id is not null) or (tipo='PRODUTO' and produto_id is not null))
);

create table executante (
  id uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null,
  item_comanda_id uuid not null references item_comanda(id),
  profissional_id uuid not null,
  papel text not null check (papel in ('PRINCIPAL','ASSISTENTE')),
  percentual_comissao numeric(7,4) not null,  -- SNAPSHOT da regra aplicada
  regra_origem text not null,                 -- qual nível da cascata venceu: auditoria
  base_calculo numeric(19,4) not null,
  valor_comissao numeric(19,4) not null,
  unique (item_comanda_id, profissional_id)
);
```

**RN-ATD-010 — snapshot obrigatório.** `preco_unitario`, `percentual_comissao` e `regra_origem`
são gravados no fechamento e nunca recalculados. Sem isso, reprocessar um fechamento de três meses
atrás produz número diferente do repasse já pago, e você não consegue explicar a diferença ao
profissional. `regra_origem` existe para responder "por que a minha comissão foi 35% e não 40%".

`executante` resolve o assistente que lava o cabelo (pergunta em aberto 6): é uma linha a mais no
item, com percentual próprio.

## Financeiro — livro-razão

```sql
create table pagamento (
  id uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null,
  comanda_id uuid not null references comanda(id),
  forma text not null check (forma in
        ('DINHEIRO','PIX','CREDITO','DEBITO','TRANSFERENCIA','CORTESIA','FIADO')),
  valor numeric(19,4) not null check (valor > 0),
  bandeira text, parcelas smallint default 1,
  nsu text, autorizacao text,
  taxa_prevista numeric(19,4), previsao_recebimento date,
  status_conciliacao text not null default 'PENDENTE'
        check (status_conciliacao in ('PENDENTE','CONCILIADO','DIVERGENTE','NAO_APLICAVEL')),
  criado_em timestamptz not null default now()
);
```

**1:N com a comanda, deliberadamente.** Cliente pagar metade no cartão e metade no pix é o caso
comum, não a exceção. A conciliação casa **pagamento a pagamento**, nunca comanda a comanda.

```sql
create table lancamento (
  id uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null,
  data_competencia date not null,
  tipo text not null check (tipo in
    ('RECEITA_SERVICO','RECEITA_PRODUTO','COMISSAO','TAXA_ADQUIRENTE',
     'VALE','REPASSE','AJUSTE','ESTORNO')),
  origem_tipo text not null check (origem_tipo in ('COMANDA','PAGAMENTO','FECHAMENTO','MANUAL')),
  origem_id uuid not null,
  contraparte_tipo text check (contraparte_tipo in ('PROFISSIONAL','CLIENTE','SALAO','ADQUIRENTE')),
  contraparte_id uuid,
  valor numeric(19,4) not null,          -- o SINAL define débito/crédito
  descricao text not null,
  estorna_lancamento_id uuid references lancamento(id),
  criado_em timestamptz not null default now(),
  criado_por uuid
);

create index on lancamento (estabelecimento_id, contraparte_tipo, contraparte_id, data_competencia);
create index on lancamento (estabelecimento_id, origem_tipo, origem_id);
```

**Imutabilidade não é convenção, é permissão.** A role da aplicação recebe apenas
`GRANT INSERT, SELECT ON lancamento`. Sem `UPDATE`, sem `DELETE`. Teste
`LancamentoImutavelIT.update_em_lancamento_falha` prova. Saldo é sempre `sum(valor)` filtrado —
nunca uma coluna. Estorno é linha nova apontando para a original.

## Estoque

```sql
create table lote (
  id uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null,
  produto_id uuid not null references produto(id),
  codigo text, custo_unitario numeric(19,4) not null,
  quantidade_inicial numeric(12,3) not null,
  validade date, recebido_em date not null default current_date
);
create index on lote (estabelecimento_id, produto_id, validade nulls last);  -- FEFO

create table movimento_estoque (
  id uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null,
  produto_id uuid not null, lote_id uuid not null references lote(id),
  tipo text not null check (tipo in ('ENTRADA','VENDA','CONSUMO_INTERNO','PERDA','AJUSTE','DEVOLUCAO')),
  quantidade numeric(12,3) not null,     -- sinal define entrada/saída
  origem_tipo text, origem_id uuid,
  custo_unitario numeric(19,4) not null,
  observacao text,
  criado_em timestamptz not null default now(), criado_por uuid
);
```

Mesma disciplina do razão: movimento é imutável, saldo é projeção
(`sum(quantidade) group by lote_id`). O índice `(produto_id, validade nulls last)` é o que faz o
FEFO ser uma query e não um loop na aplicação.

## Auditoria e conversas

```sql
create table auditoria (
  id bigserial primary key,
  estabelecimento_id uuid not null,
  ocorrido_em timestamptz not null default now(),
  usuario_id uuid, ator text not null,          -- USUARIO | BOT | SISTEMA
  acao text not null, entidade text not null, entidade_id uuid,
  antes jsonb, depois jsonb,
  ip inet, user_agent text, trace_id text
);

create table acao_ia (
  id uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null,
  conversa_id uuid not null, usuario_efetivo_id uuid not null,
  mensagem_original text, transcricao text,
  tools_chamadas jsonb not null, resultado jsonb,
  tokens_entrada integer, tokens_saida integer, custo_estimado numeric(19,6),
  criado_em timestamptz not null default now()
);
```

`acao_ia` é o que permite responder "quem cancelou o agendamento da cliente?" quando a resposta
é "o bot, a pedido da recepção, às 14h32, com esta transcrição". Sem essa tabela, a Fase 4 é
indefensável em qualquer reclamação.

## Retenção — o que apaga e quando

Nenhuma tabela de log cresce para sempre. Job diário:

| Tabela | Retenção | Motivo |
|---|---|---|
| `auditoria` | 5 anos (agenda e financeiro), 1 ano (resto) | Prazo de disputa |
| `acao_ia` | 12 meses, com transcrição anonimizada aos 90 dias | LGPD: minimização |
| `conversa_mensagem` | 90 dias | Nada exige mais |
| `idempotencia` | 7 dias | TTL da chave |
| `event_publication` (Modulith) | Completos apagados em 7 dias | Senão cresce sem limite |
