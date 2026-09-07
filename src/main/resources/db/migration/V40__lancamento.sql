-- RT-FIN-003 — o livro-razão.

-- ---------------------------------------------------------------------------
-- Toda linha de dinheiro do salão passa a ter um lugar: receita de serviço,
-- receita de produto, comissão devida, taxa de adquirente, vale, repasse.
--
-- O SINAL define débito e crédito. Uma coluna `natureza` obrigaria toda soma a
-- saber quais tipos entram e quais saem — e a primeira consulta que esquecesse
-- um tipo novo daria um número plausível e errado. É a mesma decisão do
-- movimento de estoque (RN-EST-007), pela mesma razão.
--
-- SALDO É SEMPRE `sum(valor)` FILTRADO, nunca uma coluna. Vale aqui o que já
-- vale no estoque (RN-EST-009): sem coluna, não existe saldo que desande, nem
-- script de correção, nem a conversa de "o razão está errado, precisa rodar
-- aquela rotina".
-- ---------------------------------------------------------------------------
create table lancamento (
  id                    uuid primary key default gen_random_uuid(),
  estabelecimento_id    uuid not null references estabelecimento(id),

  -- A que MÊS o valor pertence, que não é necessariamente o dia em que a linha
  -- foi escrita: a comanda de 31 de março apurada em 1º de abril é de março.
  data_competencia      date not null,

  tipo                  text not null check (tipo in
                          ('RECEITA_SERVICO', 'RECEITA_PRODUTO', 'COMISSAO',
                           'TAXA_ADQUIRENTE', 'VALE', 'REPASSE', 'AJUSTE', 'ESTORNO')),

  -- De onde a linha veio. Sem FK: comanda e pagamento são de outros módulos.
  origem_tipo           text not null check (origem_tipo in
                          ('COMANDA', 'PAGAMENTO', 'FECHAMENTO', 'MANUAL')),
  origem_id             uuid not null,

  contraparte_tipo      text check (contraparte_tipo in
                          ('PROFISSIONAL', 'CLIENTE', 'SALAO', 'ADQUIRENTE')),
  contraparte_id        uuid,

  valor                 numeric(19,4) not null,
  descricao             text not null,

  -- Estorno é LINHA NOVA apontando para a original, nunca linha apagada.
  estorna_lancamento_id uuid references lancamento(id),

  criado_em             timestamptz not null,
  criado_por            uuid
);

-- "Quanto a Ana tem a receber neste mês?" — a consulta do extrato (RT-FIN-004)
-- e do repasse (RT-FIN-006).
create index idx_lancamento_da_contraparte
  on lancamento (estabelecimento_id, contraparte_tipo, contraparte_id, data_competencia);

-- "O que esta comanda gerou?" — e é por ele que a apuração sabe se já rodou.
create index idx_lancamento_da_origem
  on lancamento (estabelecimento_id, origem_tipo, origem_id);

-- ---------------------------------------------------------------------------
-- IMUTABILIDADE NÃO É CONVENÇÃO, É PERMISSÃO.
--
-- A role da aplicação recebe INSERT e SELECT, e mais nada. Um `update` numa
-- linha do razão não é recusado por disciplina de quem escreve o código: é
-- recusado pelo banco, para quem escrever o código errado amanhã.
--
-- Mesma decisão da auditoria (V3), e é o que faz "estorno é linha nova" deixar
-- de ser recomendação.
-- ---------------------------------------------------------------------------
select aplicar_rls_tenant('lancamento');
revoke update, delete on lancamento from salao_app;
