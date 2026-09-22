# Runbook — teste de restore do backup

> Backup que nunca foi restaurado não é backup. Este teste é **mensal** e o registro abaixo é a
> prova. Alerta dispara se não houver registro nos últimos 35 dias.

> **Onde está o dump hoje:** na própria máquina, em `/backups` do serviço `backup` do compose de
> produção, com sete dias de retenção (decisão do dono, 22/09/2026). Isso protege contra erro
> humano e **não** contra perder a máquina — o bucket separado continua sendo o passo seguinte, e
> este runbook já está escrito para ele.

## Procedimento

1. Pegue o dump mais recente:
   `docker compose exec backup ls -lt /backups` — e, quando houver bucket, baixe dele.
2. Suba um Postgres 18 limpo em contêiner.
3. Restaure: `pg_restore -d salao_restore --no-owner --role=salao_app dump.tar`
4. Verifique, no banco restaurado:
   - [ ] Extensões presentes: `btree_gist`, `pg_trgm`, `unaccent`
   - [ ] Role `salao_app` existe e **não é dona** das tabelas
   - [ ] RLS habilitada **e forçada** em todas as tabelas de negócio
   - [ ] **A tabela `cliente` existe.** É a que faltava no primeiro teste (abaixo), e a que some
         em silêncio: o `pg_restore` ignora o erro e segue
   - [ ] Contagem de `agendamento`, `comanda` e `lancamento` bate com a produção do dia do dump
   - [ ] `select sum(valor) from lancamento` bate com o relatório do mesmo dia
5. Anote o tempo total. É o seu RTO real, não o estimado.

## Registro

| Data | Dump de | Duração | Resultado | Problemas encontrados |
|---|---|---|---|---|
| 22/09/2026 | Pilha de produção subida localmente, banco recém-migrado (67) | ~1 min | **Falhou, e depois passou** | O dump restaurava **sem a tabela `cliente`**: `imutavel_unaccent` chamava `unaccent` sem qualificar o schema, e o `pg_restore` roda com `search_path` vazio. 14 erros ignorados, banco sem clientes. Corrigido em `V67`; nova restauração: 63 tabelas, 60 com RLS forçada, 0 erros |

O primeiro restore quase sempre revela algo — extensão faltando, role inexistente, ordem de
dependência. É exatamente para isso que ele existe, e foi exatamente o que aconteceu em 22/09/2026:
a suíte inteira verde, o banco de produção correto, e o backup dele restaurando sem a tabela mais
importante do sistema.
