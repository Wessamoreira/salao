---
id: RT-INF-001
titulo: Bootstrap do projeto
modulo: infra
fase: 0
perfil: leve
status: especificado
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

## 9. Decisões e trade-offs

| Decisão | Alternativa | Por quê |
|---|---|---|
| Módulo Maven único + Modulith | Multi-módulo Maven | Modulith já verifica a fronteira; multi-módulo só adiciona cerimônia para 1 dev |
| Testcontainers desde o commit 1 | H2 no começo | H2 não tem `EXCLUDE`, nem RLS, nem `tstzrange`. Testaria outro sistema |
| Baseline Flyway mínima | Schema inteiro numa migration | Cada tabela nasce na rotina que a usa, junto do teste |
