# Sistema de gestão para salão

Agenda, atendimento, comissão de profissional PJ, estoque com validade, conciliação de
pagamento e agente conversacional no WhatsApp. Multi-tenant desde o primeiro commit.

## Subir o projeto (meta: 5 minutos)

```bash
docker compose -f docker-compose.dev.yml up -d   # postgres 18, minio, mailpit
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm install && npm run dev
```

- API: http://localhost:8080 · OpenAPI: http://localhost:8080/swagger-ui.html
- Front: http://localhost:5173 · MinIO: http://localhost:9001 · Mailpit: http://localhost:8025

## Antes de codar

Leia `CLAUDE.md`. Depois `docs/09-plano-de-implementacao.md` para saber qual rotina é a próxima.

## Estado

Fase 0 (fundação) não iniciada. Documentação em `docs/`.
