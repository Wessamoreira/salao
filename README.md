# Sistema de gestão para salão

Agenda, atendimento, comissão de profissional PJ, estoque com validade, conciliação de
pagamento e agente conversacional no WhatsApp. Multi-tenant desde o primeiro commit.

## Pré-requisitos

JDK 25 · Maven 3.9+ · um runtime de container · Node 22+ (só para o front)

Com **Colima** (mais leve que o Docker Desktop, e libera a RAM inteira ao parar):

```bash
colima start --cpu 2 --memory 2 --disk 20
```

O `pom.xml` tem um profile `colima` que se ativa sozinho quando o socket existe e injeta
`DOCKER_HOST` no JVM dos testes — não é preciso exportar nada na sua sessão. Ao terminar,
`colima stop` devolve a memória.

## Subir o projeto (meta: 5 minutos)

```bash
docker compose -f docker-compose.dev.yml up -d   # postgres 18, minio, mailpit
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm install && npm run dev
```

## Testes

```bash
mvn verify
```

Unitários e de arquitetura rodam sem container; os de integração sobem um Postgres 18 real via
Testcontainers. **H2 não é usado em lugar nenhum** — ele não tem `EXCLUDE`, nem RLS, nem
`tstzrange`, que são as três coisas de que este projeto mais depende.

- API: http://localhost:8080 · OpenAPI: http://localhost:8080/swagger-ui.html
- Front: http://localhost:5173 · MinIO: http://localhost:9001 · Mailpit: http://localhost:8025

## Antes de codar

Leia `CLAUDE.md`. Depois `docs/09-plano-de-implementacao.md` para saber qual rotina é a próxima.

## Estado

**Fase 0 em andamento.** 137 testes passando.

| Rotina | Status |
|---|---|
| RT-INF-001 Bootstrap | implementado |
| RT-INF-002 Tenant, RLS e teste de vazamento | implementado |
| RT-INF-003 Catálogo de erros | implementado |
| RT-INF-004 Money, paginação, relógio | implementado |
| RT-INF-005 Idempotência de escrita | implementado |
| RT-INF-006 Outbox transacional | implementado |
| RT-INF-007 Cache + LISTEN/NOTIFY | implementado |
| RT-INF-008 Observabilidade | implementado |
| RT-INF-009 CI/CD, imagem e deploy | implementado-parcial · falta máquina de hmg |
| RT-IAM-001 Provisionar estabelecimento | implementado |
| RT-IAM-002 Login com Argon2id e bloqueio | implementado |
| RT-IAM-003 Refresh rotativo com detecção de reuso | implementado |
| RT-IAM-004 Logout e revogação de sessão | implementado |
| RT-IAM-005 MFA TOTP | implementado |
| RT-IAM-006 /me/capabilities e autorização | implementado |
| RT-IAM-007 CRUD de usuário | próxima |
| RT-INF-010 Shell do front | aguarda Node |

Backlog completo em `docs/09-plano-de-implementacao.md`.
