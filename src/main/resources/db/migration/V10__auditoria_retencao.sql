-- RT-IAM-008 — retenção da trilha de auditoria.

-- O expurgo varre por data ATRAVESSANDO estabelecimentos: o índice existente
-- (estabelecimento_id, ocorrido_em) não serve para isso, porque a coluna de
-- data não é o prefixo.
create index idx_auditoria_ocorrido_em on auditoria (ocorrido_em);

-- Também por entidade: a retenção é diferente para agenda e financeiro (5 anos,
-- prazo de disputa) e para o resto (1 ano).
create index idx_auditoria_entidade_data on auditoria (entidade, ocorrido_em);

comment on table auditoria is
  'RT-IAM-008: append-only. salao_app tem apenas INSERT e SELECT (V3); '
  'UPDATE e DELETE foram revogados de propósito.';
