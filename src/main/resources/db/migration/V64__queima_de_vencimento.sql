-- RT-EST-012 — a queima de vencimento é o quinto motivo de sugestão, e não uma campanha à parte.
--
-- Nenhuma tabela nova: a queima passa pelo mesmo fluxo de RT-EST-010 e RT-EST-011 — gerar, aprovar ou
-- recusar, trilha. Só a lista de motivos cresce, nas duas tabelas que a guardam.

alter table sugestao_de_preco drop constraint sugestao_de_preco_motivo_check;
alter table sugestao_de_preco add constraint sugestao_de_preco_motivo_check
  check (motivo in ('QUEIMA_DE_VENCIMENTO', 'MARGEM_ABAIXO_DO_MINIMO', 'ENCALHADO', 'MARGEM_ALTA',
                    'PRECO_QUEBRADO'));

-- A trilha também: é nela que a única exceção ao chão de margem fica à vista (RN-EST-037).
alter table decisao_de_preco drop constraint decisao_de_preco_motivo_check;
alter table decisao_de_preco add constraint decisao_de_preco_motivo_check
  check (motivo in ('QUEIMA_DE_VENCIMENTO', 'MARGEM_ABAIXO_DO_MINIMO', 'ENCALHADO', 'MARGEM_ALTA',
                    'PRECO_QUEBRADO'));
