-- RT-EST-001 — cadastro de produto: o que se vende, o que se consome, ou ambos.

-- ---------------------------------------------------------------------------
-- O shampoo que a cliente leva para casa e a tinta que a coloração gasta são a
-- mesma entidade com finalidades diferentes — e o mesmo frasco pode ser as
-- duas coisas no mesmo salão.
--
-- Modelar como tabelas separadas obrigaria a cadastrar duas vezes o que é um
-- produto só, e o saldo de estoque se dividiria entre elas: o frasco vendido
-- não baixaria do saldo que a coloração usa.
-- ---------------------------------------------------------------------------
create table produto (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  nome               text not null,
  descricao          text,

  finalidade         text not null
                       check (finalidade in ('VENDA', 'CONSUMO_INTERNO', 'AMBOS')),

  -- ---------------------------------------------------------------------
  -- A unidade é lista fechada porque `quantidade` é numérica em toda parte:
  -- "un", "und" e "unid" digitados à mão viram três unidades diferentes, e a
  -- soma de saldo passa a depender de como cada pessoa escreveu.
  --
  -- Tinta se mede em gramas, ox em mililitros, escova em unidade. Sem isso a
  -- ficha técnica (RT-EST-007) não tem como dizer "40 g de tinta".
  -- ---------------------------------------------------------------------
  unidade            text not null check (unidade in ('UN', 'ML', 'L', 'G', 'KG')),

  -- ---------------------------------------------------------------------
  -- Preço de venda no próprio produto, e não numa tabela de vigência como a
  -- de serviço (V21).
  --
  -- Não é descuido: o mapa de permissões já separava `estoque:write` de
  -- `produto:price:write`, e é a recepção que cadastra produto sem poder
  -- precificá-lo. O que a vigência dá — saber por quanto se vendia em março —
  -- o snapshot em `item_comanda.preco_unitario` já garante para toda venda
  -- fechada.
  --
  -- Nulo é cadastro válido e venda inválida, como serviço sem preço
  -- (RN-ATD-005): o produto pode existir antes de alguém decidir quanto
  -- cobrar. Quem recusa é a venda.
  -- ---------------------------------------------------------------------
  preco_venda        numeric(19,4) check (preco_venda >= 0),

  ativo              boolean not null default true,
  versao             integer not null default 0,
  criado_em          timestamptz not null default now(),
  criado_por         uuid,

  constraint produto_nome_nao_vazio check (length(trim(nome)) > 0),

  -- O que não se vende não tem preço. Um número aqui acabaria lido por alguma
  -- tela de venda, e a tinta apareceria no balcão.
  constraint consumo_interno_nao_tem_preco
    check (finalidade <> 'CONSUMO_INTERNO' or preco_venda is null)
);

-- Nome único por salão, pela mesma razão do serviço (V20): dois "Shampoo
-- Hidratante" fazem a recepção escolher o errado metade das vezes — e aqui é
-- pior, porque o saldo de estoque fica dividido entre os dois.
create unique index idx_produto_nome
  on produto (estabelecimento_id, lower(nome));

create index idx_produto_ativo on produto (estabelecimento_id, nome) where ativo;

-- A lista do balcão: o que dá para vender agora.
create index idx_produto_vendavel
  on produto (estabelecimento_id, nome)
  where ativo and finalidade in ('VENDA', 'AMBOS');

select aplicar_rls_tenant('produto');
