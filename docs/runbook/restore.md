# Runbook — teste de restore do backup

> Backup que nunca foi restaurado não é backup. Este teste é **mensal** e o registro abaixo é a
> prova. Alerta dispara se não houver registro nos últimos 35 dias.

> **Onde está o dump:** na própria máquina, em `/backups` do serviço `backup`, com sete dias de
> retenção — **em claro**, porque é o que protege contra erro humano e vive dentro da VM. E, se
> `BACKUP_DESTINO` estiver preenchido, uma cópia **cifrada** no bucket (RT-INF-009).
>
> **A senha do arquivo mora fora desta máquina.** Sem `BACKUP_SENHA_DO_ARQUIVO` o arquivo do
> bucket não abre — e perder a máquina é exatamente quando ela vai fazer falta.

## Procedimento

1. Pegue o dump mais recente.

   **Da máquina** (o caso comum — "alguém apagou a agenda de ontem"):
   `docker compose exec backup ls -lt /backups`

   **Do bucket** (o caso que importa — a máquina se perdeu):

   ```sh
   aws s3 ls "$BACKUP_DESTINO/" ${BACKUP_ENDPOINT:+--endpoint-url "$BACKUP_ENDPOINT"}
   aws s3 cp "$BACKUP_DESTINO/salao-AAAAMMDD-HHMM.dump.enc" . \
     ${BACKUP_ENDPOINT:+--endpoint-url "$BACKUP_ENDPOINT"}

   # Decifra. A senha é a BACKUP_SENHA_DO_ARQUIVO, guardada fora da máquina.
   printf '%s' "$BACKUP_SENHA_DO_ARQUIVO" \
     | openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 \
         -in salao-AAAAMMDD-HHMM.dump.enc -out salao.dump -pass stdin
   ```
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
| 26/09/2026 | **Do bucket**, cifrado (RT-INF-009) | ~1 min | **Passou** | Volta inteira verificada com a pilha de produção e um servidor S3 local: dump → cifra → envio → `aws s3 cp` de volta → `openssl enc -d` → `pg_restore` → a linha gravada antes do dump veio de volta. Achado no caminho: o serviço de envio reiniciava em laço porque `/backups` é montado só-leitura e a marca de "último envio" era escrita lá; ela passou a morar num volume próprio |

O primeiro restore quase sempre revela algo — extensão faltando, role inexistente, ordem de
dependência. É exatamente para isso que ele existe, e foi exatamente o que aconteceu em 22/09/2026:
a suíte inteira verde, o banco de produção correto, e o backup dele restaurando sem a tabela mais
importante do sistema.
