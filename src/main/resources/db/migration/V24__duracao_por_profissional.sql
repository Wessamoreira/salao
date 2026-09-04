-- RT-CAT-004 — quanto tempo CADA PROFISSIONAL leva num serviço.

-- ---------------------------------------------------------------------------
-- A Ana faz escova em 30 minutos e a Júlia em 45. Hoje o sistema promete o
-- mesmo horário para as duas: ou a agenda da Ana fica com buraco de 15 minutos
-- em cada escova, ou a da Júlia atrasa 15 e o atraso acumula pelo dia inteiro.
--
-- É COLUNA de `habilidade`, e não tabela própria, porque duração e habilidade
-- são a MESMA relação: profissional x serviço. Duas tabelas permitiriam
-- "a Ana leva 30 minutos numa coloração que ela não faz".
--
-- E há uma razão mecânica além da conceitual: `substituirHabilidades` apaga e
-- regrava as linhas a cada edição (RT-EQP-004). Numa tabela irmã, toda edição
-- de habilidade apagaria as durações em silêncio — o mesmo defeito que
-- RT-CAT-003 evitou gravando o recurso exigido junto com o bloco.
--
-- NULL = usa a duração do catálogo. É o caso da maioria, e é diferente de
-- zero: zero não é duração, é serviço que não acontece.
-- ---------------------------------------------------------------------------
alter table habilidade
  add column duracao_minutos smallint
    check (duracao_minutos is null or (duracao_minutos > 0 and duracao_minutos <= 600));

comment on column habilidade.duracao_minutos is
  'RN-CAT-013: duração TOTAL do serviço para este profissional; null usa a do catálogo.';
