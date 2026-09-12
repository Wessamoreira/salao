-- RT-CNV-001 — o webhook do WhatsApp: roteamento por número e caixa de entrada.

-- ---------------------------------------------------------------------------
-- O PROBLEMA QUE ESTA MIGRATION RESOLVE
--
-- Toda requisição do sistema chega com um tenant: o JWT diz de qual salão ela é,
-- e a RLS filtra a partir daí. O webhook não. Ele chega da Meta, sem token, sem
-- cabeçalho de tenant, e o que ele traz é o `phone_number_id` — o identificador
-- do número do salão na Cloud API.
--
-- Descobrir de quem é a mensagem é, portanto, uma leitura CROSS-TENANT, e ela
-- acontece antes de existir qualquer escopo. É a mesma categoria do despachante
-- de mensagens (V30) e do purgador de idempotência (V4): operação de
-- PLATAFORMA, não de tenant. Vai pela role de manutenção, com concessão
-- estreita.
-- ---------------------------------------------------------------------------

create table canal_whatsapp (
  -- O id do número na Cloud API. É a CHAVE DE ROTEAMENTO: é ele que chega no
  -- payload, e é PK porque um número atende um salão só.
  --
  -- O contrário — `estabelecimento_id` como PK — proibiria o salão de ter dois
  -- números, e ter dois é normal: uma unidade nova ganha número próprio antes
  -- de ganhar cadastro próprio.
  phone_number_id    text primary key
                       check (length(btrim(phone_number_id)) > 0),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- E.164, para a tela dizer QUAL número é. Não roteia nada: o número exibido
  -- muda de formatação entre painéis da Meta, e o id não muda.
  numero_exibicao    text not null,

  -- Desligar sem apagar. Apagar a linha faria as mensagens seguintes chegarem
  -- sem dono e serem descartadas em silêncio; com `ativo = false` o descarte é
  -- deliberado e aparece na contagem.
  ativo              boolean not null default true,

  criado_em          timestamptz not null default now(),
  atualizado_em      timestamptz not null default now()
);

create index idx_canal_do_salao on canal_whatsapp (estabelecimento_id);

select aplicar_rls_tenant('canal_whatsapp');

-- Só SELECT para a plataforma: o webhook precisa LER de quem é o número, e nada
-- mais. Quem cadastra o canal é o salão, dentro do escopo dele.
create policy manutencao on canal_whatsapp to salao_manutencao using (true);
grant select on canal_whatsapp to salao_manutencao;

-- Apagar um canal é o modo de falha silenciosa descrito acima. Desativa-se.
revoke delete on canal_whatsapp from salao_app;

-- ---------------------------------------------------------------------------
-- A CAIXA DE ENTRADA
--
-- Mensagem de cliente é dado pessoal, e aqui ele é necessário: é o pedido dela.
-- Fica dentro de tabela com RLS, como `mensagem` (V30) — e ao contrário de
-- `event_publication`, que não tem política de tenant e por isso está proibida
-- de carregar PII (RN-INF-009).
--
-- O QUE NÃO É GUARDADO: o payload cru da Meta.
--
-- Guardá-lo seria conveniente para depurar uma integração que não controlamos,
-- e foi recusado. Ele é o MESMO dado pessoal duas vezes, num formato de
-- terceiro: a anonimização de cliente (RT-CLI-009) teria de encontrar e limpar
-- nome e telefone dentro de um jsonb cuja forma a Meta muda quando quiser. O
-- que se extrai fica em coluna; o que não se entende vira `NAO_SUPORTADO`, e o
-- log registra a ESTRUTURA (tipo e chaves), nunca os valores.
-- ---------------------------------------------------------------------------

create table mensagem_recebida (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  -- TELEGRAM já está previsto porque ADR-0006 o escolheu como plano B e como
  -- ambiente de teste do agente. A coluna existir agora evita a migration que
  -- mexe em tabela cheia depois.
  canal              text not null check (canal in ('WHATSAPP', 'TELEGRAM')),

  -- O `wamid` da Meta. É a chave de idempotência do webhook, e o motivo de ela
  -- existir está em RN-CNV-002: a Meta reenvia o lote inteiro, e um reenvio
  -- traz mensagens novas JUNTO com as que já chegaram.
  provedor_id        text not null,

  -- Quem mandou, em E.164. Sem FK para cliente: descobrir de quem é o telefone
  -- é RT-CNV-002, e a mensagem existe mesmo quando o número não é de cliente
  -- nenhum — o que é justamente o caso do primeiro contato.
  de_telefone        text not null,

  tipo               text not null check (tipo in
                       ('TEXTO', 'AUDIO', 'IMAGEM', 'DOCUMENTO', 'NAO_SUPORTADO')),

  texto              text,

  -- O id da mídia na Meta. O download é RT-CNV-003; aqui só se guarda o
  -- bilhete, porque baixar dentro do webhook estouraria a janela de resposta.
  midia_id           text,
  midia_mime         text,

  -- Os dois instantes, e a diferença entre eles é a latência do provedor — que
  -- é o que explica uma resposta que chegou tarde.
  --
  -- `recebida_em` NÃO tem `default now()`: a fila de processamento compara este
  -- valor com o port `Relogio` da aplicação (RN-INF-006), e deixar o banco
  -- preencher poria dois relógios na mesma comparação. Quem grava informa.
  enviada_em         timestamptz not null,
  recebida_em        timestamptz not null,
  processada_em      timestamptz,

  -- O tipo manda no que pode existir. Sem isto, `AUDIO` sem `midia_id` passaria,
  -- e a falha apareceria na RT-CNV-003 como "mídia nula" — longe daqui.
  constraint mensagem_coerente_com_o_tipo check (
    (tipo = 'TEXTO' and texto is not null and midia_id is null)
    or (tipo in ('AUDIO', 'IMAGEM', 'DOCUMENTO') and midia_id is not null and texto is null)
    or (tipo = 'NAO_SUPORTADO' and texto is null and midia_id is null))
);

-- O árbitro da idempotência, e não um detalhe de integridade: é este índice que
-- transforma o reenvio da Meta em zero linhas inseridas.
create unique index uma_mensagem_por_provedor
  on mensagem_recebida (estabelecimento_id, provedor_id);

-- A consulta da fila: o que chegou e ainda não foi processado.
create index idx_mensagem_recebida_a_processar
  on mensagem_recebida (recebida_em)
  where processada_em is null;

create index idx_mensagem_recebida_do_telefone
  on mensagem_recebida (estabelecimento_id, de_telefone, recebida_em desc);

select aplicar_rls_tenant('mensagem_recebida');

-- ---------------------------------------------------------------------------
-- O que a cliente pediu é PROVA, e prova não se edita.
--
-- A aplicação só pode marcar a mensagem como processada. Reescrever `texto`
-- seria reescrever o pedido — e o consumidor desta tabela vai ser um LLM, com
-- permissão de escrita concedida por engano num caso de uso qualquer. Grant de
-- coluna, como em `fechamento.estornado_em` (V43).
--
-- Apagar também não: a retenção é trabalho de plataforma, e ainda não existe.
-- ---------------------------------------------------------------------------
revoke update, delete on mensagem_recebida from salao_app;
grant update (processada_em) on mensagem_recebida to salao_app;
