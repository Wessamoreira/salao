# Runbook — teste de restore do backup

> Backup que nunca foi restaurado não é backup. Este teste é **mensal** e o registro abaixo é a
> prova. Alerta dispara se não houver registro nos últimos 35 dias.

## Procedimento

1. Baixe o dump mais recente do bucket (bucket **separado da VM**).
2. Suba um Postgres 18 limpo em contêiner.
3. Restaure: `pg_restore -d salao_restore --no-owner --role=salao_app dump.tar`
4. Verifique, no banco restaurado:
   - [ ] Extensões presentes: `btree_gist`, `pg_trgm`, `unaccent`
   - [ ] Role `salao_app` existe e **não é dona** das tabelas
   - [ ] RLS habilitada **e forçada** em todas as tabelas de negócio
   - [ ] Contagem de `agendamento`, `comanda` e `lancamento` bate com a produção do dia do dump
   - [ ] `select sum(valor) from lancamento` bate com o relatório do mesmo dia
5. Anote o tempo total. É o seu RTO real, não o estimado.

## Registro

| Data | Dump de | Duração | Resultado | Problemas encontrados |
|---|---|---|---|---|
| _(preencher no primeiro teste)_ | | | | |

O primeiro restore quase sempre revela algo — extensão faltando, role inexistente, ordem de
dependência. É exatamente para isso que ele existe.
