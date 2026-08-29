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
atualizado_em: 2026-08-29
---

# RT-INF-009 — CI/CD, imagem e deploy

> **Status honesto:** pipeline e imagem prontos e **verificados**. O passo de deploy está escrito
> mas **nunca executou** — não existe máquina de homologação (risco R-11). Por isso
> `implementado-parcial`, e não `implementado`.

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

## 5. Verificação feita

A imagem foi construída e **executada de verdade** contra um Postgres real:

| Verificado | Resultado |
|---|---|
| Build da imagem | 431 MB |
| Contêiner sobe e fica `healthy` | Sim |
| Processo roda como não-root | `uid=100(salao)` |
| `/actuator/health` responde | `db`, `outbox` e `ouvinteDeCache` UP |
| YAML do workflow, dependabot, alertas e compose | Válidos |

O multi-arch **não** foi verificado localmente — depende do buildx com QEMU no runner. É o
principal risco residual do job `imagem`.

## 6. O bug que só apareceu rodando

**O `docker-compose.dev.yml` estava quebrado desde o RT-INF-001 e ninguém sabia.**

O `postgres:18` mudou a convenção do diretório de dados: o volume vai em `/var/lib/postgresql`, e
não mais em `/var/lib/postgresql/data`. A imagem **recusa subir** ao encontrar dados no caminho
antigo. O compose apontava para o caminho antigo.

Nenhum teste pegou isso, porque o **Testcontainers não monta volume** — ele sempre parte de um
banco vazio e efêmero. O erro só apareceria na primeira vez que alguém rodasse
`docker compose up`, ou seja, no README que promete "subir o projeto em 5 minutos".

Fica a lição, anotada no próprio compose: suíte verde não é o mesmo que ambiente de
desenvolvimento funcionando. São caminhos diferentes, e só um deles tem teste.

## 7. Pendências

- [ ] **Máquina de homologação** (risco R-11). O job existe e está pulado. Decidir entre Oracle
      Ampere e alternativa antes que a Fase 1 precise de um ambiente para demonstração
- [ ] Multi-arch nunca foi construído de fato — verificar no primeiro push em `main`
- [ ] **AOT/CDS não entrou.** O treino de CDS (`spring.context.exit=onRefresh`) precisa de banco
      alcançável no momento do build, o que a etapa de imagem não tem. Sai com um perfil que
      desative datasource e Flyway no treino — vale o esforço só quando o tempo de startup
      incomodar de verdade
- [ ] Deploy em produção segue manual e é assim de propósito: um desenvolvedor sozinho não tem
      quem reverta às 22h
- [ ] `docker-compose.yml` de produção (o de dev não serve: expõe portas e traz MinIO e Mailpit)
