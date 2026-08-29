---
id: RT-INF-008
titulo: Observabilidade
modulo: infra
fase: 0
perfil: completo
status: implementado
depende_de: [RT-INF-006, RT-INF-007]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-08-29
---

# RT-INF-008 — Observabilidade

## 1. Objetivo

Tornar visíveis as falhas que não geram exceção — e ligar cada linha de log ao clique que a
originou.

## 2. Contexto

As rotinas anteriores criaram métricas, mas **nenhum alerta**. Os dois modos de falha silenciosa
do sistema seguiam silenciosos:

| Falha | Sintoma para o usuário | Sintoma técnico |
|---|---|---|
| Outbox parado | Confirmação nunca chega | **Nenhum.** Sem exceção, sem 5xx, sem Sentry |
| Ouvinte de cache caído | Preço desatualizado por até 30 min | **Nenhum.** A aplicação responde 200 em tudo |

Sentry cobre exceção. Nenhum destes dois lança uma.

## 3. Quatro camadas

| Camada | Responde |
|---|---|
| Log JSON estruturado | "O que aconteceu nesta requisição" |
| Métricas (Micrometer + Prometheus) | "Como está a série ao longo do tempo" |
| Health (`/actuator/health`) | **"Está quebrado agora?"** — a pergunta de quem está de plantão |
| Tracing (`traceId`) | "Onde foram os 3 segundos deste clique" |

Health e métrica não são redundantes. Às 22h, com o dono do salão ligando, ninguém abre o
Prometheus para montar uma query: abre o health e lê o texto.

## 4. Log estruturado — sem dependência nova

O Boot 4 traz formatação estruturada nativa (`logging.structured.format.file: ecs`), então o
`logstash-logback-encoder` previsto no plano **não é necessário**. Uma dependência a menos.

`logging.structured.json.context.include: true` leva o MDC para dentro do JSON — é assim que
`tenantId` e `traceId` aparecem em toda linha.

**O `tenantId` entra no MDC pelo `TenantFilter`**, junto com a abertura do escopo. Sem ele,
investigar um incidente em produção multi-tenant vira adivinhação: os logs de todos os
estabelecimentos misturados, sem forma de separá-los.

> **Só o identificador.** Nunca telefone, nome ou conteúdo de mensagem — a mesma regra do outbox
> (RN-INF-009) e da LGPD em `05-seguranca`.

## 5. O propagador cresceu, e o nome mudou

`PropagadorDeTenant` virou `PropagadorDeContexto`: além do tenant, agora carrega o **MDC**.

O MDC é `ThreadLocal` e some na fronteira de thread exatamente como o `ScopedValue`. Sem
propagá-lo, o log do trabalho assíncrono perde `traceId` e `tenantId` — e deixa de ser possível
ligar "a confirmação não saiu" ao clique que a originou, que é justamente a investigação que se
quer fazer.

**Ele também limpa o MDC ao terminar.** Thread de pool é reutilizada: MDC sujo faria o próximo
trabalho logar o `traceId` do anterior — pior que não logar nenhum, porque leva a investigação
para o clique errado.

## 6. Health indicators

| Componente | `DOWN` quando |
|---|---|
| `outbox` | A publicação pendente mais antiga passa de 5 min |
| `ouvinteDeCache` | A conexão de `LISTEN` está fora |

`outbox` reporta a **idade**, não só a contagem: um outbox saudável e movimentado também tem
pendências a qualquer instante. E reporta `DOWN`, não `OUT_OF_SERVICE` — fila parada significa que
efeitos externos combinados não estão acontecendo, mesmo que a aplicação responda 200 em tudo.

O detalhe do `ouvinteDeCache` diz o **efeito**, não o estado: *"invalidações estão sendo perdidas;
o cache local depende apenas do expireAfterWrite de 30 min"*. Quem lê às 22h precisa saber o que
está em risco, não o nome do componente.

## 7. Actuator fora da porta pública

`management.server.port: 9090`. Enquanto não houver Spring Security (RT-IAM-002), separar a porta
é a proteção que dá para ter — métrica de negócio e health interno não são endpoint de aplicação.
`ObservabilidadeIT.actuator_fora_da_porta_publica` verifica que a porta da aplicação não responde.

Isso **não substitui** autenticação: qualquer um dentro da rede alcança a porta 9090. Autenticar
entra com RT-IAM-002.

## 8. Alertas

`ops/prometheus/alertas.yml` — 8 regras, cada uma apontando para um runbook. As duas primeiras são
as falhas silenciosas; as demais cobrem 5xx, p95 da agenda, saturação de pool, transação sem
tenant, disco e backup sem teste de restore.

`TransacaoSemTenant` dispara na **primeira ocorrência**: RN-INF-003 diz que qualquer valor é bug,
nunca fluxo normal. O contador `tenant.ausente` vive no `TenantAwareTransactionManager`, e não no
handler HTTP, porque transação sem tenant também acontece em job agendado e em listener
assíncrono — que nunca passam por um controller.

## 9. Testes

- [x] `PropagadorDeContextoTest` — propaga tenant e MDC; limpa a thread ao terminar; sem tenant
      não inventa um
- [x] `ObservabilidadeIT.health_reporta_outbox_e_ouvinte_de_cache`
- [x] `ObservabilidadeIT.health_do_outbox_cai_com_pendencia_antiga`
- [x] `ObservabilidadeIT.prometheus_expoe_metricas` — as três séries que os alertas consultam
- [x] `ObservabilidadeIT.actuator_fora_da_porta_publica`

## 10. O que ficou incompleto, e por quê

**`traceId` ainda não aparece nos logs.** `micrometer-tracing-bridge-otel` está no classpath e o
padrão de correlação está configurado, mas **verifiquei o arquivo de log gerado pelos testes e
nenhum `traceId` foi emitido** — não há controller produzindo observação HTTP de servidor, então
não existe span para correlacionar. A fiação está pronta; a verificação ponta a ponta só é
possível com o primeiro endpoint real. Até lá, o campo `traceId` do Problem Details continua indo
nulo, como já estava documentado em RT-INF-003.

**Sentry não entrou.** O SDK atual (8.54.0) tem como alvo o Spring Boot 3.x, e o Boot 4
reorganizou os módulos de autoconfiguração — o mesmo tipo de incompatibilidade que já apareceu com
o Flyway nesta fase. Adicionar um starter que pode não carregar, sem DSN para testar contra, seria
trocar cobertura real por uma dependência inerte. Fica como pendência com critério objetivo:
entrar quando houver versão declarando suporte a Boot 4, **e** um DSN para validar.

## 11. Pendências

- [ ] Verificar `traceId` ponta a ponta com o primeiro controller (RT-IAM-002/006)
- [ ] Sentry, quando houver suporte a Boot 4 e um DSN (ver acima)
- [ ] Autenticar o `/actuator` — hoje só está em outra porta (RT-IAM-002)
- [ ] `backup_ultimo_restore_testado_timestamp_seconds`: a regra de alerta existe, falta quem
      publique a métrica. Sai do registro manual em `runbook/restore.md`
- [ ] Runbooks citados pelos alertas: só `restore.md` existe. Os demais nascem na primeira vez que
      cada problema acontecer — runbook escrito preventivamente descreve o problema imaginado, não
      o real
- [ ] Hit rate do Caffeine no Micrometer (pendência herdada de RT-INF-007)
