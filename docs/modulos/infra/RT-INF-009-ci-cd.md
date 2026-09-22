---
id: RT-INF-009
titulo: CI/CD, imagem e deploy
modulo: infra
fase: 0
perfil: completo
status: implementado-parcial
depende_de: [RT-INF-001, RT-INF-008]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-09-22
---

# RT-INF-009 — CI/CD, imagem e deploy

> **Status honesto:** pipeline, imagem, compose de produção e backup prontos e **verificados
> rodando**. O passo de deploy está escrito mas **nunca executou** — não existe máquina de
> homologação (risco R-11), e o dono decidiu em 22/09/2026 seguir sem ela por enquanto. Por isso
> `implementado-parcial`, e não `implementado`: a rotina só fecha no dia do primeiro deploy de
> verdade.

## 1. Objetivo

Que todo commit em `main` seja testado, escaneado e empacotado sem intervenção, e que a imagem
resultante rode numa VM pequena.

## 2. Pipeline

```
verificar → seguranca → imagem → homologacao
```

| Job | O que faz | Bloqueia? |
|---|---|---|
| `verificar` | `mvn verify`: unitários, arquitetura, integração com Postgres real | Sim |
| `seguranca` | Trivy nas dependências | Sim, em CRITICAL/HIGH com correção |
| `imagem` | Multi-arch amd64+arm64 no GHCR, e escaneia o que publicou | Só em `main` |
| `homologacao` | `compose pull && up -d --wait` por SSH | Pulado até haver máquina |

**`--wait` respeita o `HEALTHCHECK` da imagem.** Sem ele, o deploy declara sucesso sobre um
contêiner que subiu quebrado — e o pipeline verde vira a pior espécie de mentira.

**O job de homologação é pulado, não falha,** enquanto `HMG_HOST` não existir. Pipeline vermelho
por infraestrutura ausente treina a equipe a ignorar vermelho, e aí o vermelho que importa passa
despercebido.

## 3. Duas substituições em relação ao plano

**Trivy no lugar do OWASP Dependency-Check.** Desde 2023 o Dependency-Check exige chave da API do
NVD e leva cerca de 10 minutos em cache frio. O Trivy cobre as mesmas dependências Java, sem
chave, e ainda escaneia a imagem depois de publicada. `ignore-unfixed: true` porque vulnerabilidade
sem correção disponível não é acionável hoje — só ruído que ensina a ignorar o relatório.

**Dependabot no lugar do Renovate.** É nativo do GitHub, não exige app instalado nem configuração
própria. Agrupado por família: o Spring Boot sobe dezenas de artefatos juntos, e revisá-los em PRs
separados é ruído sem informação.

## 4. A imagem

**Empacota um jar já construído**, em vez de rodar o Maven dentro do `Dockerfile`. O CI já executa
`mvn verify` com testes; refazer o build na imagem duplicaria isso e descartaria o cache de
dependências do runner. O preço é que `docker build` sozinho não basta — precisa de
`mvn package` antes, e o CI faz os dois na ordem certa.

**Extração em camadas** (`-Djarmode=tools ... extract --layers`): dependências mudam raramente,
código muda a todo commit. Sem isso, cada deploy empurra os 66 MB inteiros pela rede em vez de
alguns KB.

**Alpine (musl)** pela economia: o alvo é uma VM pequena, onde tempo de pull e disco contam. A
aplicação é Java puro com JDBC, sem biblioteca nativa. Critério de reversão escrito no
`Dockerfile`: se entrar alguma lib nativa, mover para `eclipse-temurin:25-jre-noble` (glibc).

**Não-root** (uid 100), verificado. **`MaxRAMPercentage=75`** em vez de `-Xmx` fixo, porque o
limite do contêiner muda por ambiente. **`UseSerialGC`** porque o alvo tem 1–2 vCPU — G1 só
compensa acima disso. **`ExitOnOutOfMemoryError`** para o orquestrador reiniciar, em vez de manter
um processo vivo e inútil.

**O `HEALTHCHECK` aponta para a porta 9090**, não para a 8080: depois do RT-INF-008 o actuator
mudou de porta, e um healthcheck apontando para a porta errada reportaria "unhealthy" para sempre.

## 5. Verificação feita em 29/08/2026

A imagem foi construída e **executada de verdade** contra um Postgres real:

| Verificado | Resultado |
|---|---|
| Build da imagem | 431 MB |
| Contêiner sobe e fica `healthy` | Sim — e **deixou de ser verdade** depois de RT-INF-008 fixar o endereço do actuator (seção 7) |
| Processo roda como não-root | `uid=100(salao)` |
| `/actuator/health` responde | `db`, `outbox` e `ouvinteDeCache` UP |
| YAML do workflow, dependabot, alertas e compose | Válidos |

## 6. O que foi verificado em 22/09/2026

Decisões do dono: **sem máquina de homologação por enquanto** (o job segue pulado), banco no mesmo
compose com volume, **Caddy** terminando HTTPS, e **dump diário guardado sete dias na própria
máquina**.

As decisões viraram os arquivos da seção 9. A pilha de produção inteira subiu localmente, com domínio `localhost`, e foi conferida rodando:

| Verificado | Resultado |
|---|---|
| `docker compose up -d --wait` com os quatro serviços | Tudo `healthy` de primeira |
| HTTPS pelo Caddy | `HTTP/2 401` na API — TLS, proxy e aplicação no mesmo caminho |
| Cabeçalhos | HSTS, `nosniff`, `Referrer-Policy`; `Server` removido |
| Portas publicadas pela aplicação | Nenhuma: só o Caddy fala com fora |
| Actuator pela porta pública | Não responde (fica na 9090, só em loopback) |
| Usuário e volumes | `uid=100(salao)`, escrevendo em `/dados/arquivos` e `/app/logs` |
| Dump diário | 291 KB no primeiro ciclo, dono `postgres`, não root |
| **Restauração do dump** | 63 tabelas, 60 com RLS forçada, extensões, **0 erros** |
| Imagem multi-arch | `linux/amd64` **e** `linux/arm64` construídas de fato, com buildx e emulação |

O multi-arch sai da lista de riscos: as duas arquiteturas compilam, incluindo a extração em camadas
sob emulação. O que ainda não aconteceu é a **publicação** no GHCR, que só o primeiro push em `main`
faz.

## 7. Três defeitos que só apareceram rodando

**1. O contêiner nunca ficaria `healthy`.** O `HEALTHCHECK` batia em
`http://localhost:9090/actuator/health/readiness`. Dentro do contêiner, `localhost` resolve primeiro
para `::1`, e o actuator escuta no IPv4 de loopback desde RT-INF-008
(`management.server.address=127.0.0.1`): o wget levava *connection refused* em toda tentativa. Com
`up -d --wait` no deploy, isso é **pipeline vermelho para sempre**; num orquestrador, reinício em
laço. O healthcheck passou a usar `127.0.0.1`, e o `start-period` subiu para 90s, porque a aplicação
leva ~45s para responder numa máquina pequena.

**2. Os arquivos iam para `/tmp`.** `app.arquivos.raiz` tem como padrão o diretório temporário; sem
configuração, ficha e foto sumiriam no primeiro restart. O compose aponta para `/dados/arquivos`, com
volume — e o `Dockerfile` passou a **criar o diretório com o dono certo**, porque volume nomeado novo
herda dono do ponto de montagem na imagem: se não existisse, o Docker o criaria como root e a
aplicação (uid 100) não escreveria nele.

**3. O backup restaurava sem a tabela `cliente`** — e é o pior dos três, porque parecia funcionar.
`imutavel_unaccent` (V13) chamava `unaccent` sem qualificar o schema; o `pg_restore` roda com
`search_path` vazio de propósito, o `CREATE TABLE cliente` falhava no inlining da coluna gerada, e o
`pg_restore` **seguia em frente**: "errors ignored on restore: 14" e um banco sem clientes. Corrigido
em `V67`, com teste (`BuscaIndexadaIT.normalizacao_sobrevive_ao_restore`) que roda a normalização com
`search_path` vazio — a condição exata da restauração.

Os três estavam no caminho do deploy e nenhum teste os pegaria: são coisas que só existem quando a
imagem roda, o volume monta e o dump volta.

O terceiro agora tem teste, e ele foi conferido por mutação: devolvendo a chamada sem schema em
`V67`, `BuscaIndexadaIT.normalizacao_sobrevive_ao_restore` falha. Os outros dois não têm — o que os
pega é este runbook executado de novo, e é honesto dizer isso em vez de fingir cobertura.

## 8. O bug de 29/08 — o compose de desenvolvimento quebrado

**O `docker-compose.dev.yml` estava quebrado desde o RT-INF-001 e ninguém sabia.**

O `postgres:18` mudou a convenção do diretório de dados: o volume vai em `/var/lib/postgresql`, e
não mais em `/var/lib/postgresql/data`. A imagem **recusa subir** ao encontrar dados no caminho
antigo. O compose apontava para o caminho antigo.

Nenhum teste pegou isso, porque o **Testcontainers não monta volume** — ele sempre parte de um
banco vazio e efêmero. O erro só apareceria na primeira vez que alguém rodasse
`docker compose up`, ou seja, no README que promete "subir o projeto em 5 minutos".

Fica a lição, anotada no próprio compose: suíte verde não é o mesmo que ambiente de
desenvolvimento funcionando. São caminhos diferentes, e só um deles tem teste.

## 9. O compose de produção

Em `ops/producao/`, e vai para `/opt/salao` na máquina (runbook `subir-em-producao.md`):

| Arquivo | O quê |
|---|---|
| `docker-compose.yml` | app, postgres, caddy e backup. A aplicação não publica porta |
| `Caddyfile` | HTTPS automático, cabeçalhos, proxy para a aplicação |
| `backup.sh` | `pg_dump -Fc` diário, sete dias, com o cliente da **mesma** versão do servidor |
| `.env.exemplo` | Todas as variáveis, nenhum valor real |

Detalhes que não são decoração: rotação do log de contêiner (sem ela o disco da VM enche e o
sintoma aparece como "o banco parou de gravar"); volume do Caddy para o certificado (sem ele, cada
restart pede um certificado novo e o Let's Encrypt barra por limite); `ATRAS_DE_PROXY=true` ligado
só porque nada alcança a aplicação sem passar pelo Caddy; e o backup largando privilégio com `gosu`
depois do `chown` do volume.

## 10. Pendências

- [ ] **Máquina de homologação** (risco R-11). O job existe e está pulado. Decisão do dono em
      22/09/2026: seguir sem ela por enquanto. É a única coisa que falta para a rotina fechar, e
      quando existir o procedimento já está escrito (`subir-em-producao.md`)
- [ ] **A imagem nunca foi publicada no GHCR** — o build das duas arquiteturas foi verificado
      localmente em 22/09/2026; o `push` só acontece no primeiro commit em `main`
- [ ] **AOT/CDS não entrou.** O treino de CDS (`spring.context.exit=onRefresh`) precisa de banco
      alcançável no momento do build, o que a etapa de imagem não tem. Sai com um perfil que
      desative datasource e Flyway no treino — vale o esforço só quando o tempo de startup
      incomodar de verdade
- [ ] Deploy em produção segue manual e é assim de propósito: um desenvolvedor sozinho não tem
      quem reverta às 22h
- [x] ~~`docker-compose.yml` de produção~~ — feito em 22/09/2026, em `ops/producao/`, e verificado
      rodando (seção 5b)
- [ ] **O backup não sai da máquina.** Sete dias de dump no mesmo disco protegem contra erro
      humano, não contra perder a VM. O `backup.sh` ganha o envio para um bucket quando houver um, e
      é ele que o teste mensal de restore passa a usar
- [ ] **Nada avisa se o backup parar.** O contêiner reinicia sozinho e o erro fica no log. Um
      alerta ("nenhum dump nas últimas 26 horas") é pequeno e ainda não existe
