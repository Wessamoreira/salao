-- RT-CLI-004 — consentimento do cliente, por finalidade.

-- ---------------------------------------------------------------------------
-- Primeiro, uma correção que não é desta rotina.
--
-- V3 concedeu as sequências com `on all sequences in schema public`, que vale
-- só para as que existiam naquele instante. Toda tabela `bigserial` criada
-- depois nasce com a sequência inacessível para salao_app — e o sintoma é um
-- "permission denied for sequence" no primeiro INSERT, em tempo de execução,
-- não na migração. Foi exatamente assim que apareceu aqui.
--
-- `alter default privileges` resolve para as próximas: o que salao_owner criar
-- daqui em diante já nasce concedido. Vem antes do `create table` de propósito,
-- para que a sequência desta tabela também seja coberta.
-- ---------------------------------------------------------------------------
alter default privileges in schema public
  grant usage, select on sequences to salao_app;

-- ---------------------------------------------------------------------------
-- NÃO existe coluna `base_legal` aqui, e isso é deliberado.
--
-- A base legal é propriedade da FINALIDADE, não da pessoa: mandar lembrete de
-- um horário marcado é execução de contrato para toda cliente do salão, e
-- campanha de promoção exige consentimento para toda cliente do salão. Guardar
-- a base por linha permitiria que duas linhas discordassem sobre um fato que é
-- o mesmo para todo mundo — e a linha errada seria descoberta num processo.
--
-- A base legal vive no enum `Finalidade`, um lugar só.
--
-- O que varia por pessoa, e por isso está aqui, é a DECISÃO dela.
-- ---------------------------------------------------------------------------
create table cliente_consentimento (
  id                 bigserial primary key,
  estabelecimento_id uuid not null references estabelecimento(id),
  cliente_id         uuid not null references cliente(id),
  finalidade         text not null check (finalidade in (
                       'LEMBRETE_DE_AGENDAMENTO',
                       'MARKETING',
                       'PESQUISA_DE_SATISFACAO')),
  -- false é revogação (art. 8 §5) ou oposição (art. 18 §2). A mesma tabela, e o
  -- mesmo esforço para registrar: a lei exige que revogar seja tão fácil quanto
  -- consentir, e um caminho separado para o "não" seria sempre o mais estreito.
  concedido          boolean not null,
  -- Como a decisão foi coletada. É o que se apresenta quando alguém pergunta
  -- "onde está a prova de que ela aceitou?".
  canal              text not null check (canal in (
                       'BALCAO', 'WHATSAPP', 'TELEFONE', 'SITE',
                       'FICHA_PAPEL', 'IMPORTACAO')),
  observacao         text,
  registrado_em      timestamptz not null,
  registrado_por     uuid
);

-- `distinct on (finalidade) ... order by finalidade, registrado_em desc` percorre
-- este índice para achar a decisão vigente sem ler o histórico inteiro.
create index idx_consentimento_vigente
  on cliente_consentimento (cliente_id, finalidade, registrado_em desc, id desc);

select aplicar_rls_tenant('cliente_consentimento');

-- ---------------------------------------------------------------------------
-- Append-only, pelo mesmo motivo da auditoria: a pergunta que esta tabela
-- responde é "o que valia no dia em que a mensagem foi enviada?". Uma linha
-- editável responderia apenas "o que vale hoje", que é justamente a resposta
-- inútil num questionamento.
--
-- A permissão é a garantia, não a convenção.
-- ---------------------------------------------------------------------------
revoke update, delete on cliente_consentimento from salao_app;
