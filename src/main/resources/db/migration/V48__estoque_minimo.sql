-- RT-EST-008 — estoque mínimo e alerta de validade.

-- ---------------------------------------------------------------------------
-- NULO NÃO É ZERO. Quinta vez neste projeto, e a distinção muda o produto.
--
--   NULO  = "ninguém definiu mínimo para este produto". Não alerta.
--   ZERO  = "avise quando acabar". É uma decisão, e uma decisão comum: para o
--           shampoo de vitrine, o dono quer saber no instante em que zerar.
--
-- Se a coluna fosse `not null default 0`, todo produto nasceria com "avise
-- quando acabar" e a tela de alertas viraria uma lista de cem linhas que
-- ninguém lê — e um alerta que ninguém lê é pior que nenhum, porque ele ocupa
-- o lugar do que importa.
--
-- É a mesma distinção de `preco_venda` ("zero é um preço"), de `taxa_prevista`
-- ("zero seria taxa isenta"), de `custo_medio` ("zero seriam unidades que não
-- custaram nada") e de `comissao_percentual` (RN-FIN-005).
-- ---------------------------------------------------------------------------
alter table produto add column estoque_minimo numeric(12,3);

alter table produto
  add constraint estoque_minimo_nao_negativo
  check (estoque_minimo is null or estoque_minimo >= 0);

comment on column produto.estoque_minimo is
  'RT-EST-008: NULO significa "sem mínimo definido" e não alerta. Zero significa '
  '"avise quando acabar", que é uma decisão diferente.';

-- "Quem está abaixo do mínimo?" — a consulta da tela de alertas. Parcial porque
-- só quem tem mínimo definido entra nela, e num salão isso é a minoria dos
-- produtos.
create index idx_produto_com_minimo
  on produto (estabelecimento_id)
  where estoque_minimo is not null and ativo;
