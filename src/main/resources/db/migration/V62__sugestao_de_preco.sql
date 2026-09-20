-- RT-EST-010 — que preço está errado hoje, e por quê.

-- ===========================================================================
-- A política de preço do salão
--
-- Uma linha por salão: a margem mínima que nenhuma sugestão fura.
--
-- Mora no estoque, e não na configuração do salão (`iam`): margem mínima sobre custo médio é
-- vocabulário de estoque, e é o estoque quem a aplica. Mesma escolha da ficha técnica (RT-EST-007).
--
-- Percentual em forma percentual: 30.0000 é 30%. O teto largo (mil por cento) existe porque revenda
-- de cosmético trabalha com margens que assustam quem vem de outro ramo — e recusar o número certo do
-- dono seria pior que aceitá-lo.
-- ===========================================================================
create table politica_de_preco (
  estabelecimento_id  uuid primary key references estabelecimento(id),
  margem_minima       numeric(9, 4) not null
                        check (margem_minima >= 0 and margem_minima <= 1000),
  atualizada_em       timestamptz not null,
  atualizada_por      uuid
);

select aplicar_rls_tenant('politica_de_preco');

-- Renegociar a régua é o caminho normal; apagá-la deixaria o motor sem chão.
revoke delete on politica_de_preco from salao_app;

-- ===========================================================================
-- A sugestão aberta de cada produto
--
-- Uma por produto: gerar de novo substitui. O que deixou de se aplicar some — um produto que voltou a
-- vender não continua pedindo promoção. O histórico do que foi decidido é de RT-EST-011.
--
-- Os números que geraram a sugestão ficam junto dela. Sem isso, "por que 49,90?" só teria resposta
-- recalculando com o custo de hoje — que já é outro.
-- ===========================================================================
create table sugestao_de_preco (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),
  produto_id          uuid not null references produto(id) on delete cascade,

  motivo              text not null check (motivo in
                        ('MARGEM_ABAIXO_DO_MINIMO', 'ENCALHADO', 'MARGEM_ALTA', 'PRECO_QUEBRADO')),

  -- Nulo é produto sem preço de venda — que é um dos motivos de haver sugestão.
  preco_atual         numeric(19, 4) check (preco_atual >= 0),
  custo_medio         numeric(19, 4) not null check (custo_medio > 0),
  preco_sugerido      numeric(19, 4) not null check (preco_sugerido > 0),

  -- Em forma percentual, como a política. Nula quando não há preço atual.
  margem_atual        numeric(9, 4),
  margem_sugerida     numeric(9, 4) not null,

  -- A conta por extenso, escrita pelo sistema. É o que responde "por quê" sem a IA.
  racional            text not null check (char_length(racional) between 1 and 1000),
  -- A redação do modelo. Nula quando não havia credencial, o teto do dia fechou, ou o modelo falhou.
  explicacao          text check (char_length(explicacao) <= 1000),

  gerada_em           timestamptz not null,

  constraint uma_sugestao_por_produto unique (estabelecimento_id, produto_id)
);

create index idx_sugestao_de_preco_do_salao
  on sugestao_de_preco (estabelecimento_id, gerada_em desc);

select aplicar_rls_tenant('sugestao_de_preco');

-- A sugestão não se edita: gerar de novo apaga e reescreve, e decidir sobre ela é RT-EST-011.
revoke update on sugestao_de_preco from salao_app;
