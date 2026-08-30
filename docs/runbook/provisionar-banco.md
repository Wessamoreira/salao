# Runbook — provisionar o banco de um ambiente

Uma vez por ambiente, antes do primeiro deploy.

## Por que este passo existe

As migrations criam as roles **sem senha**, de propósito. `alter role ... password 'x'` grava o
segredo dentro do próprio comando SQL — e com `log_statement = 'ddl'` ou `'all'` no Postgres ele
vai para o log do servidor **em texto claro**. Log de banco costuma ser copiado, arquivado e lido
por mais gente do que quem tem acesso ao segredo.

Então a senha é definida aqui, fora da migration, por quem provisiona.

## Passos

**1. Gere as senhas.** Longas e aleatórias — ninguém precisa digitá-las.

```bash
openssl rand -base64 32
```

Uma para `salao_app`, outra para `salao_manutencao`.

**2. Gere a chave de cifragem de campo** (AES-256, Base64 de 32 bytes):

```bash
openssl rand -base64 32
```

**3. Gere o segredo do JWT** (mínimo 32 bytes):

```bash
openssl rand -base64 48
```

**4. Crie as roles e defina as senhas**, conectado como owner:

```sql
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'salao_app')
    then create role salao_app login; end if;
  if not exists (select 1 from pg_roles where rolname = 'salao_manutencao')
    then create role salao_manutencao login; end if;
end $$;

alter role salao_app password 'A_SENHA_GERADA';
alter role salao_manutencao password 'A_OUTRA_SENHA';
```

> Rode isto em uma sessão `psql` interativa, não em script versionado — e confira se
> `log_statement` está em `none` ou `mod` antes. Se estiver em `ddl` ou `all`, desligue durante
> este passo.

**5. Registre as variáveis** no gerenciador de segredos do ambiente:

| Variável | O que é |
|---|---|
| `DB_URL` | JDBC do banco |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | Dono das tabelas — só o Flyway usa |
| `DB_APP_PASSWORD` | Senha de `salao_app` |
| `DB_MANUTENCAO_PASSWORD` | Senha de `salao_manutencao` |
| `JWT_SEGREDO` | Assinatura dos tokens |
| `CRIPTO_CHAVE` | Cifragem de campo (segredo TOTP e, depois, ficha do cliente) |
| `CORS_ORIGENS` | Origem do front, explícita |

**Nenhuma tem valor padrão.** A aplicação recusa subir sem elas, e isso é deliberado: funcionar com
credencial fraca é silencioso e dura até alguém descobrir.

**6. Suba a aplicação.** O Flyway roda as migrations como owner e aplica os timeouts por role.

## Se `app.rede.atras-de-proxy` for ligado

Só ligue quando **nada** alcançar a aplicação sem passar pelo proxy. Ligado sem a regra de firewall
correspondente, ele piora a segurança: `X-Forwarded-For` é enviado pelo cliente, e confiar nele
sem proxy à frente dá a qualquer um um IP novo por requisição — o limite de taxa deixa de existir
sem parar de parecer que funciona.

Confira antes:

```bash
sudo lsof -iTCP:8080 -sTCP:LISTEN
```

A aplicação deve estar ouvindo apenas na interface que o proxy alcança.

## Actuator

`MANAGEMENT_ADDRESS` é `127.0.0.1` por padrão — o actuator não é alcançável da rede. Health e
métrica precisam ser lidos por sonda e por scraper, que não carregam token, então a proteção é
alcance, não autenticação.

Para raspar de outra máquina, exponha numa interface privada **com firewall**, ou use um túnel.
Nunca na interface pública.
