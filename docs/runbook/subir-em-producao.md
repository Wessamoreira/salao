# Runbook — subir a aplicação numa máquina

Vale para produção e para homologação: muda o domínio e as senhas, não o procedimento.

> **O que este runbook NÃO faz:** criar a máquina. Enquanto não houver uma, o job `homologacao` do
> pipeline fica pulado (decisão do dono, 22/09/2026) e este documento é a receita esperando o
> servidor.

## O que precisa existir antes

- Uma VM com Docker e `docker compose` (plugin v2), 2 GB de RAM e disco de 20 GB.
- Um domínio apontando para o IP da máquina, com as portas **80 e 443 abertas**. O Caddy precisa
  da 80 para a validação do certificado — fechá-la faz o HTTPS nunca nascer.
- Nenhuma outra coisa escutando em 80 ou 443.

## 1. Levar os arquivos

Os quatro ficam em `/opt/salao`, e só eles:

```bash
sudo mkdir -p /opt/salao && sudo chown "$USER" /opt/salao
scp ops/producao/docker-compose.yml ops/producao/Caddyfile \
    ops/producao/backup.sh ops/producao/enviar-backup.sh \
    ops/producao/.env.exemplo usuario@maquina:/opt/salao/
```

## 2. Preencher o `.env`

```bash
cd /opt/salao && cp .env.exemplo .env && chmod 600 .env
openssl rand -base64 32   # uma vez para cada senha e para a CRIPTO_CHAVE
openssl rand -base64 48   # para o JWT_SEGREDO
```

`DOMINIO` e `EMAIL_TLS` são o domínio real e o e-mail que recebe o aviso de expiração.
`CORS_ORIGENS` é a origem do front, explícita: vazio significa nenhuma.

## 3. Subir só o banco e criar as roles

As migrations criam `salao_app` e `salao_manutencao` **sem senha**, de propósito
(`provisionar-banco.md` explica por quê). A senha é definida aqui, uma vez por máquina:

```bash
docker compose up -d postgres
docker compose exec postgres psql -U salao_owner -d salao
```

```sql
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'salao_app')
    then create role salao_app login; end if;
  if not exists (select 1 from pg_roles where rolname = 'salao_manutencao')
    then create role salao_manutencao login; end if;
end $$;
alter role salao_app password 'A_SENHA_DO_ENV';
alter role salao_manutencao password 'A_OUTRA_SENHA_DO_ENV';
```

## 4. Subir o resto

```bash
docker compose pull      # a imagem vem do GHCR; pule se estiver usando uma local
docker compose up -d --wait
```

`--wait` respeita o `HEALTHCHECK` da imagem. Se o comando voltar com erro, **o deploy falhou** —
não é um aviso. O primeiro `up` demora: o Flyway aplica as migrations antes de a aplicação
responder.

## 5. Conferir, sem confiar

```bash
docker compose ps                                   # app, postgres e backup: healthy
curl -sI https://SEU.DOMINIO/api/v1/me/capabilities # 401 é a resposta certa: chegou na aplicação
docker compose exec backup ls -l /backups           # o primeiro dump sai no `up`
docker compose logs app | grep -i "não configurado" # o que está desligado, e é esperado
```

O 401 é o sinal de que a rota inteira funciona: TLS, proxy e aplicação. Uma tela de erro do Caddy,
ou um 502, param aqui.

## 6. Primeiro estabelecimento

A aplicação sobe com o banco vazio. Provisione o salão e o primeiro usuário como manda
`provisionar-banco.md`; o dono troca a senha no primeiro acesso.

## Atualizar depois

```bash
cd /opt/salao && docker compose pull && docker compose up -d --wait
```

É o que o job `homologacao` do pipeline faz por SSH quando `HMG_HOST` existir. Para voltar atrás,
fixe o `TAG` no `.env` com o sha do commit que funcionava e repita o comando — as imagens antigas
continuam no GHCR.

## Quando algo não sobe

| Sintoma | Quase sempre é |
|---|---|
| `app` fica `unhealthy` | Migration falhando, ou senha de `salao_app` não definida (passo 3). `docker compose logs app` |
| `caddy` sem certificado | Porta 80 fechada, ou DNS ainda não propagado. `docker compose logs caddy` |
| `502` no navegador | A aplicação ainda está subindo — o Flyway roda antes do Tomcat |
| `backup` reiniciando | Senha do owner errada no `.env` |
| `backup-remoto` reiniciando | `BACKUP_DESTINO` preenchido e `BACKUP_SENHA_DO_ARQUIVO` vazio — o dump não sai em claro, de propósito |
| `backup-remoto` *unhealthy* | O envio parou. O log diz se é credencial, bucket ou rede — e `up -d --wait` recusa deploy novo até resolver |
| `backup` `unhealthy` | Nenhum dump nas últimas 26 horas. `docker compose logs backup` diz por quê; `docker compose restart backup` faz um na hora |
| Deploy recusado com o `backup` `unhealthy` | É de propósito: código novo por cima de backup quebrado é o que não se quer. Resolva o backup e repita |

## O que fica de fora, e é decisão

- **Deploy em produção é manual.** Um desenvolvedor sozinho não tem quem reverta às 22h.
- **O backup fica na própria máquina**, sete dias. Protege contra erro humano, não contra perder a
  máquina. O passo seguinte é um bucket fora dela — e é o que o teste de restore vai passar a usar.
