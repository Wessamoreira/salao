# Observabilidade e operação

Sentry cobre exceção. Não cobre "está lento" nem "a fila travou". Os dois modos de falha mais
prováveis deste sistema são silenciosos: outbox parado e cache servindo dado velho.

## Camadas

| Camada | Ferramenta | Responde |
|---|---|---|
| Exceção | Sentry (backend + front, mesmo `release`) | "O que quebrou e em qual linha" |
| Métrica | Micrometer + Actuator + Prometheus | "Está lento? A fila cresceu?" |
| Trace | OpenTelemetry, `traceparent` do front ao SQL | "Onde foram os 3 segundos deste clique" |
| Log | JSON (Logback + logstash encoder) | "O que aconteceu nesta requisição" |

Todo log carrega `traceId`, `tenantId`, `userId`. **Nunca** PII, token, telefone completo ou
conteúdo de mensagem de cliente.

## Métricas obrigatórias desde a Fase 0

| Métrica | Por quê |
|---|---|
| p95 por endpoint | R-UX-01 exige p95 < 800ms na agenda do dia |
| `hikaricp.connections.pending` | O gargalo real com virtual threads (R-18) |
| `cache.gets` / hit rate do Caffeine | Gatilho objetivo para migrar a Redis (< 70%) |
| `cache.listener.up` | `LISTEN/NOTIFY` caído = preço velho silencioso (R-09) |
| Tamanho e idade da fila do outbox | Notificação que não sai (R-12) |
| Falha de webhook por origem | WhatsApp perdendo mensagem |
| Custo de LLM por estabelecimento por dia | Teto da Fase 4 (R-17). Tabela `consumo_de_ia`; métrica `conversacional_agente_total{desfecho}` (`recusada_teto` é o corte) |
| Saída para a equipe | RT-CNV-006. `conversacional_saida_total{desfecho=enviada\|adiada\|falhou}`, e o tamanho de `mensagem_para_equipe` pendente — sem provedor, ele só cresce, e é o que avisa que ninguém está recebendo resposta |
| Aceites da profissional | RT-CNV-006. `conversacional_agente_total{desfecho=aceite_aceita\|aceite_recusada\|aceite_vencida\|aceite_vencido\|aceite_nao_encontrado\|aceite_falhou}`. `aceite_nao_encontrado` subindo é toque de número errado — ou telefone de profissional mal cadastrado |
| Confirmações pelo agente | RT-CNV-005. Mesma métrica: `confirmada`, `confirmacao_recusada`, `confirmacao_vencida`, `confirmacao_ja_decidida`, `confirmacao_sem_pedido`. `confirmacao_sem_pedido` subindo é código digitado errado — ou pedido de confirmação que não chegou à pessoa |
| Trilha do agente | RT-CNV-007. Tabela `acao_ia`, lida no painel `/agente` (bloqueadas subindo é ataque ou cadastro errado). O expurgo diário registra `Trilha do agente: N apagadas, M anonimizadas`; silêncio é o normal |
| Aviso de IA a quem não é da equipe | RT-CNV-008. `conversacional_aviso_de_ia_total{desfecho=enfileirado\|ja_avisado\|equipe}`. `enfileirado` subindo muito num dia é o número divulgado para clientes — o que o aviso existe para corrigir |
| Importação do extrato da maquininha | RT-FIN-009. Log `Extrato importado: N linhas — …` e auditoria `IMPORTACAO_DE_EXTRATO_CRIADO`, só com contas. Recusadas subindo de um mês para o outro é o portal mudando o formato — a prévia mostra qual coluna |
| Taxa de 5xx e de 409 | 409 subindo = corrida real na agenda |

## Alertas mínimos

| Alerta | Limite | Ação |
|---|---|---|
| Taxa de 5xx | > 1% em 5 min | `runbook/erros-5xx.md` |
| p95 da agenda | > 1,5s por 10 min | `runbook/agenda-lenta.md` |
| Outbox parado | evento pendente > 5 min | `runbook/outbox-travado.md` |
| Listener de cache caído | `up == 0` por 1 min | Reinicia listener + flush |
| Disco | > 80% | `runbook/disco-cheio.md` |
| Certificado | vence em < 14 dias | Renovação |
| Restore de backup | sem registro há 35 dias | Executar teste de restore |

`/actuator` autenticado e fora da rota pública.

## Ambientes

`dev` · `hmg` · `prod`. `application-<perfil>.yml` versionado apenas com o que **não** é segredo.
Em prod, tudo por variável de ambiente.

`docker-compose.dev.yml`: `postgres:18`, `minio`, `mailpit`, app com hot reload. Um
`docker compose up` e o projeto roda — isso é requisito de `RT-INF-001`, não conveniência.

## Imagem e VM

Dockerfile multi-stage, JRE 25 slim, layered jar, usuário não-root, healthcheck. Multi-arch
(`linux/arm64`) obrigatório. Flags para VM pequena:

```
-XX:MaxRAMPercentage=75 -XX:+UseSerialGC     # 1–2 vCPU; G1 acima disso
```

AOT/CDS cache do Spring Boot ativado — corta boa parte do startup sem os problemas de native
image (proxy, reflection, build lento).

## CI/CD

```
build → testes (Testcontainers) → ArchUnit + Modulith → Dependency-Check + Trivy
      → OWASP ZAP baseline → imagem multi-arch no registry
      → deploy hmg automático → deploy prod manual
      → Flyway roda no start, com lock
```

Deploy em prod é manual de propósito: um desenvolvedor sozinho não tem quem reverter às 22h.

## Runbooks

`docs/runbook/` — um arquivo por alerta, com sintoma, diagnóstico, correção e como confirmar que
voltou. Escreva o runbook **na primeira vez que o problema acontecer**, enquanto o contexto está
fresco. Runbook escrito preventivamente descreve o problema que você imaginou, não o que ocorre.

Mínimos: `restore.md` (com o registro mensal), `outbox-travado.md`, `agenda-lenta.md`,
`erros-5xx.md`, `disco-cheio.md`, `whatsapp-fora.md`.
