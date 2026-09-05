-- RT-NOT-001 — envio com template, retry e opt-out.

-- ---------------------------------------------------------------------------
-- A mensagem é gravada ANTES de sair, e é isso que torna o reenvio possível.
--
-- Um envio feito direto na chamada do caso de uso não tem como ser repetido
-- quando o provedor está fora do ar — e "o WhatsApp caiu por dez minutos" é
-- rotina, não exceção. Gravar primeiro transforma uma falha de rede em uma
-- linha para tentar de novo.
--
-- É a mesma forma do outbox de eventos (RT-INF-006), com uma diferença que
-- importa: aqui a tabela TEM RLS. O payload carrega nome e horário da cliente,
-- e `event_publication` não tem política de tenant — foi por isso que RN-INF-009
-- proibiu PII lá. Aqui o dado pessoal é necessário e fica dentro do perímetro.
-- ---------------------------------------------------------------------------
create table mensagem (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- Sem FK: cliente é de outro módulo (RN-EQP-015).
  cliente_id         uuid not null,

  finalidade         text not null check (finalidade in
                       ('LEMBRETE_DE_AGENDAMENTO', 'MARKETING', 'PESQUISA_DE_SATISFACAO')),
  canal              text not null check (canal in ('WHATSAPP', 'SMS', 'EMAIL')),
  modelo             text not null,

  -- Para onde foi, como estava no momento do envio. Guardado em vez de buscado
  -- na hora porque a cliente pode trocar de telefone entre a fila e o envio, e
  -- a pergunta de auditoria é "para onde ISTO foi", não "qual o telefone dela".
  destino            text not null,

  -- Os parâmetros do modelo. Carregam nome e horário — dado pessoal, dentro de
  -- uma tabela com RLS.
  parametros         jsonb not null default '{}'::jsonb,

  status             text not null default 'PENDENTE' check (status in
                       ('PENDENTE', 'ENVIADA', 'FALHOU', 'BLOQUEADA')),

  -- ---------------------------------------------------------------------
  -- Retry com espera crescente. `tentativas` conta as que já falharam;
  -- `proxima_tentativa_em` é quando vale tentar de novo.
  --
  -- Sem a espera, um provedor fora do ar vira uma rajada de chamadas que o
  -- mantém fora do ar — e o salão descobre pelo bloqueio da conta.
  -- ---------------------------------------------------------------------
  tentativas           smallint not null default 0,

  -- Sem `default now()`, e de propósito. O despachante compara este valor com o
  -- port `Relogio` da aplicação (RN-INF-006); deixar o BANCO preencher poria dois
  -- relógios na mesma comparação, e qualquer defasagem entre eles faria a
  -- mensagem não ser vista — sem erro, sem log, só uma fila que não anda.
  --
  -- Quem enfileira informa o instante. Um relógio só.
  proxima_tentativa_em timestamptz not null,
  ultimo_erro          text,

  enviada_em         timestamptz,
  criado_em          timestamptz not null default now(),

  -- ---------------------------------------------------------------------
  -- A referência é o que impede o envio duplicado.
  --
  -- "Lembrete do agendamento X" só pode existir uma vez. Sem isto, o job que
  -- varre os agendamentos de amanhã manda de novo a cada execução — e a
  -- cliente recebe seis lembretes do mesmo horário, que é pior do que nenhum.
  --
  -- Anulável porque nem toda mensagem tem um fato único por trás: uma campanha
  -- de marketing não tem.
  -- ---------------------------------------------------------------------
  referencia         uuid
);

create unique index idx_mensagem_sem_duplicata
  on mensagem (estabelecimento_id, modelo, referencia)
  where referencia is not null;

-- A consulta do despachante: o que está esperando e já pode ir.
create index idx_mensagem_a_enviar
  on mensagem (proxima_tentativa_em)
  where status = 'PENDENTE';

create index idx_mensagem_do_cliente
  on mensagem (estabelecimento_id, cliente_id, criado_em desc);

select aplicar_rls_tenant('mensagem');

-- ---------------------------------------------------------------------------
-- O despachante roda por fora de qualquer requisição e atende TODOS os salões:
-- ele não tem tenant, e é exatamente esse poder que `salao_app` não pode ter.
--
-- Mesmo padrão de `refresh_token` (V8): uma política própria para a role de
-- plataforma, e uma concessão estreita — ele lê e atualiza o estado do envio,
-- nunca insere e nunca apaga. Enfileirar continua sendo operação da aplicação,
-- dentro do escopo do tenant.
-- ---------------------------------------------------------------------------
create policy manutencao on mensagem to salao_manutencao using (true);
grant select, update on mensagem to salao_manutencao;
