-- RT-EST-006 — inventário: contagem, divergência e ajuste em lote.

-- ---------------------------------------------------------------------------
-- O inventário é um DOCUMENTO DE TRABALHO, não um razão.
--
-- Ele nasce aberto, recebe contagens que podem ser refeitas, e termina aplicado
-- ou cancelado. Por isso aceita UPDATE — ao contrário do movimento de estoque
-- (RN-EST-015), que é o que ele PRODUZ. A separação importa: quem recontou uma
-- prateleira corrige a linha do inventário; o movimento que saiu dele, nunca.
-- ---------------------------------------------------------------------------
create table inventario (
  id                 uuid primary key default gen_random_uuid(),
  estabelecimento_id uuid not null references estabelecimento(id),

  status             text not null default 'ABERTO'
                       check (status in ('ABERTO', 'APLICADO', 'CANCELADO')),
  observacao         text,

  criado_em          timestamptz not null,
  criado_por         uuid,
  encerrado_em       timestamptz,
  encerrado_por      uuid,
  -- Aberto não tem data de encerramento; encerrado tem. Sem isto caberia um
  -- inventário "APLICADO" sem dizer quando, e a pergunta "quando o estoque foi
  -- corrigido?" ficaria só na auditoria.
  constraint encerrado_tem_data check ((status = 'ABERTO') = (encerrado_em is null))
);

-- ---------------------------------------------------------------------------
-- UM inventário aberto por salão.
--
-- Dois abertos ao mesmo tempo contariam o mesmo lote com esperados diferentes, e
-- aplicar os dois somaria duas correções para uma única divergência — o estoque
-- ficaria pior do que antes da contagem.
--
-- Índice único PARCIAL: o histórico de inventários encerrados fica, e só a
-- unicidade do aberto é imposta. É a mesma forma do EXCLUDE parcial do
-- fechamento (V43), pela mesma razão.
-- ---------------------------------------------------------------------------
create unique index um_inventario_aberto_por_salao
  on inventario (estabelecimento_id)
  where status = 'ABERTO';

-- ---------------------------------------------------------------------------
-- A LINHA CONGELA O ESPERADO NO MOMENTO DA CONTAGEM.
--
-- É a decisão que sustenta a rotina. Três alternativas, e as duas primeiras
-- estragam o resultado:
--
--   1. Congelar tudo ao ABRIR o inventário. Uma venda durante a contagem viraria
--      divergência falsa: o sistema esperava 10, venderam 2, você contou 7, e o
--      ajuste sairia -3 quando a divergência real é -1.
--   2. Recalcular ao APLICAR. Aí o número que se confirma não é o que se viu, e a
--      confirmação deixa de confirmar coisa alguma — o mesmo defeito que a versão
--      da prévia resolve no fechamento (RN-FIN-019).
--   3. Congelar POR LINHA, no instante em que aquele lote foi contado.
--
-- A terceira é a certa porque o ajuste do inventário é um DELTA, não um "passe a
-- valer X": `contado - esperado`. Se duas unidades saíram legitimamente depois da
-- contagem, elas continuam valendo em cima da correção — 10 - 2 - 3 dá 5, que é
-- exatamente 7 - 2. A janela de erro fica do tamanho de uma prateleira, não do
-- inventário inteiro.
-- ---------------------------------------------------------------------------
create table inventario_item (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),
  inventario_id       uuid not null references inventario(id),

  -- Sem FK para lote/produto: são do mesmo módulo, mas a FK não separa tenant
  -- (RN-CAT-011) e quem confere é o caso de uso, pela API do dono do dado.
  lote_id             uuid not null,
  produto_id          uuid not null,

  quantidade_esperada numeric(12,3) not null,
  quantidade_contada  numeric(12,3) not null check (quantidade_contada >= 0),
  observacao          text,

  contado_em          timestamptz not null,
  contado_por         uuid,

  -- Contar o mesmo lote de novo é RECONTAR, não somar: a segunda contagem
  -- substitui a primeira, com o esperado do novo instante.
  constraint um_item_por_lote unique (inventario_id, lote_id)
);

create index idx_inventario_item_do_inventario
  on inventario_item (estabelecimento_id, inventario_id);

select aplicar_rls_tenant('inventario');
select aplicar_rls_tenant('inventario_item');

-- Nada de DELETE: uma contagem apagada é uma prateleira que ninguém sabe se foi
-- contada. Recontar sobrescreve; desistir cancela o inventário inteiro.
revoke delete on inventario from salao_app;
revoke delete on inventario_item from salao_app;

comment on column inventario_item.quantidade_esperada is
  'RT-EST-006: o saldo do lote NO INSTANTE DA CONTAGEM. O ajuste é contado - esperada, '
  'um delta — movimento legítimo posterior continua valendo em cima dele.';
