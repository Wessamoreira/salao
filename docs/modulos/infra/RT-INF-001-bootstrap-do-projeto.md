---
id: RT-INF-001
titulo: Bootstrap do projeto
modulo: infra
fase: 0
perfil: leve
status: implementado
depende_de: []
permissoes: []
eventos: []
regras: [RN-INF-005, RN-INF-006]
atualizado_em: 2026-08-28
---

# RT-INF-001 — Bootstrap do projeto

## 1. Objetivo

Ter um projeto que sobe com um comando, com as fronteiras de módulo já verificadas por teste
antes de existir a primeira regra de negócio.

## 2. Contexto

Fronteira criada depois não é criada. O momento de instalar os testes de ArchUnit e Modulith é
quando eles passam trivialmente — assim a primeira violação real quebra o build no dia em que
acontece, e não seis meses depois.

## 3. Pré-requisitos de ambiente

| Ferramenta | Versão | Verificar com |
|---|---|---|
| JDK | 25 (LTS) | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Docker | qualquer runtime compatível | `docker ps` |
| Node | 22+ | `node --version` |

Docker é obrigatório: Testcontainers usa Postgres real, nunca H2. Sem ele, `RT-INF-002` não
tem como existir.

## 4. Entregáveis

**Estrutura Maven** — módulo único com pacotes por bounded context (não multi-módulo Maven: o
Spring Modulith já verifica a fronteira, e multi-módulo Maven só adiciona cerimônia de build para
um desenvolvedor).

```
br.com.salao/
├── SalaoApplication.java
├── shared/
├── iam/ equipe/ cliente/ catalogo/ agenda/ atendimento/
├── financeiro/ estoque/ conversacional/ notificacao/ arquivos/
```

Cada módulo nasce com `api/` e `internal/{domain,application,infra,web}` e um `package-info.java`
declarando o módulo para o Modulith.

**`docker-compose.dev.yml`** — `postgres:18`, `minio`, `mailpit`. Um `docker compose up` e o
projeto roda (requisito, não conveniência).

**Flyway** — `V1__baseline.sql` com as extensões (`btree_gist`, `pg_trgm`, `unaccent`) e nada mais.
`ddl-auto=validate` em todos os perfis.

**Perfis** — `dev`, `hmg`, `prod`. `application-<perfil>.yml` versionado só com o que não é segredo.

**Testes de arquitetura que já passam hoje:**

| Teste | O que garante |
|---|---|
| `modulos_respeitam_fronteiras` | `ApplicationModules.verify()` |
| `dominio_nao_depende_de_spring` | Domínio limpo |
| `controller_nao_chama_repository` | Camadas |
| `nenhuma_entidade_jpa_em_assinatura_de_controller` | Mass assignment |
| `dinheiro_nunca_e_double_ou_float` | RN-INF-005 |
| `instante_nunca_vem_de_Instant_now` | RN-INF-006 |

## 5. Configuração relevante

```yaml
spring:
  threads.virtual.enabled: true
  jpa:
    hibernate.ddl-auto: validate
    properties.hibernate:
      query.fail_on_pagination_over_collection_fetch: true
      generate_statistics: ${STATS:false}      # true em teste
  datasource.hikari:
    maximum-pool-size: 20
    connection-timeout: 3000                   # falhar rápido, não empilhar (R-18)
```

`connection-timeout` curto é deliberado: com virtual threads o gargalo passa a ser o pool, e
empilhar mil requisições esperando conexão é pior que recusar rápido.

## 6. Casos de borda

| Situação | Comportamento |
|---|---|
| Docker ausente | Testes de integração falham com mensagem clara, não com stack trace de conexão |
| Porta 5432 ocupada | Compose usa porta alternativa mapeada, documentada no README |
| Build em ARM e x86 | Imagem multi-arch desde o início (R-11) |

## 7. Testes obrigatórios

- [ ] `ArquiteturaTest` — os seis testes da seção 4
- [ ] `AplicacaoSobeIT.contexto_carrega_com_postgres_real`
- [ ] `FlywayIT.migrations_aplicam_em_banco_limpo`

## 8. Como validar

1. `docker compose -f docker-compose.dev.yml up -d`
2. `./mvnw verify` — todos os testes passam
3. `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` — sobe
4. `curl localhost:8080/actuator/health` → `UP`
5. Crie um import proibido de propósito (`agenda` importando `financeiro.internal`) e confirme que
   `./mvnw test` **quebra**. Se não quebrar, a fronteira não existe.

O passo 5 é o único que realmente valida esta rotina.

## 9. O que a implementação revelou

Três descobertas que só apareceram ao resolver as dependências de verdade. Ficam registradas
porque nenhuma delas é adivinhável a partir da documentação das bibliotecas.

**Versões reais, conferidas no Maven Central em 28/08/2026** (o rascunho original tinha algumas
defasadas):

| Artefato | Versão real | Observação |
|---|---|---|
| `spring-boot-starter-parent` | **4.1.1** | 4.2.0-M1 existe, mas é milestone |
| `spring-modulith-bom` | **2.1.1** | par do Boot 4.1 |
| `archunit-junit5` | **1.5.0** | — |
| `testcontainers-bom` | **2.0.5** | ver abaixo |
| Flyway | gerenciado pelo Boot | não fixar versão à mão |

**O Boot 4.1 não gerencia mais a versão do Testcontainers.** O BOM 2.0.5 precisa ser importado
explicitamente no `dependencyManagement`, senão o build nem lê o POM.

**O Testcontainers 2.x renomeou todos os artefatos.** `org.testcontainers:postgresql` virou
`org.testcontainers:testcontainers-postgresql`, e `junit-jupiter` virou
`testcontainers-junit-jupiter`. As classes também mudaram de pacote:
`org.testcontainers.postgresql.PostgreSQLContainer` (o antigo
`org.testcontainers.containers.PostgreSQLContainer` continua presente como compatibilidade).

**O Boot 4 separou as autoconfigurações em módulos próprios, e isso falha em silêncio.**
`flyway-core` no classpath não traz mais `FlywayAutoConfiguration` — é preciso
`org.springframework.boot:spring-boot-flyway`. Sem ele **as migrations simplesmente não rodam, sem
erro nenhum**. O sintoma aparece longe da causa: o Hibernate falha com *"Unable to determine
Dialect without JDBC metadata"*, porque a role da aplicação, que a migration criaria, não existe.
Vale checar o mesmo padrão ao adicionar qualquer integração nova do Boot 4.

**O Testcontainers não enxerga o Colima, e só aceita variável de ambiente.** O Colima publica o
socket em `~/.colima/default/docker.sock`; o Testcontainers procura em `/var/run/docker.sock`.
`System.setProperty("docker.host", ...)` **não funciona** — a estratégia de descoberta lê variável
de ambiente e `~/.testcontainers.properties`, nunca propriedade de sistema. Resolvido com um
profile Maven `colima`, ativado automaticamente pela existência do socket, que injeta
`DOCKER_HOST` e `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` no JVM dos testes. Em CI o profile fica
inerte e nada muda — e ninguém precisa exportar variável na própria sessão.

**ArchUnit reprova regra sem alvo.** Com os módulos de negócio ainda vazios, regras como
`dominio_nao_depende_de_spring` não encontram classe nenhuma e falham por padrão. Resolvido com
`archRule.failOnEmptyShould=false` em `src/test/resources/archunit.properties`. **Isso é
temporário** — está anotado no próprio arquivo para voltar a `true` quando a Fase 1 estiver
implementada, senão uma regra que deixou de casar por engano passa despercebida.

## 10. Estado

Implementado. `mvn verify` → **29 testes passando** (22 unitários + 7 de integração).

| Arquivo | O quê |
|---|---|
| `pom.xml` | Boot 4.1.1, Modulith 2.1.1, Testcontainers 2.0.5, ArchUnit 1.5.0 |
| `SalaoApplication.java` | `@Modulithic(sharedModules = "shared")` |
| `<modulo>/package-info.java` | 11 módulos declarados com `@ApplicationModule` |
| `application.yml` | `ddl-auto: validate`, virtual threads, pool com timeout curto |
| `docker-compose.dev.yml` | postgres:18, minio, mailpit |
| `V1__baseline.sql` | `btree_gist`, `pg_trgm`, `unaccent` |
| `ArquiteturaTest.java` | os 5 testes de fronteira |

## 11. Decisões e trade-offs

| Decisão | Alternativa | Por quê |
|---|---|---|
| Módulo Maven único + Modulith | Multi-módulo Maven | Modulith já verifica a fronteira; multi-módulo só adiciona cerimônia para 1 dev |
| Testcontainers desde o commit 1 | H2 no começo | H2 não tem `EXCLUDE`, nem RLS, nem `tstzrange`. Testaria outro sistema |
| Baseline Flyway mínima | Schema inteiro numa migration | Cada tabela nasce na rotina que a usa, junto do teste |
