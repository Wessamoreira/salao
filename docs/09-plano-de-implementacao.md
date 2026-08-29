# Plano de implementação

Backlog completo em rotinas, ordenado por dependência. **Nunca implemente fora de ordem sem
checar `depende_de`.** Ordem existe porque a Fase 4 chama os casos de uso das fases 1–3:
construir a IA antes do domínio estável é retrabalho garantido.

Legenda de tamanho: `P` ≤ 1 dia · `M` 2–3 dias · `G` 4–7 dias · `GG` > 1 semana.

---

## Fase 0 — Fundação

**Pronta quando:** login funciona, o teste de vazamento de tenant passa, o deploy em hmg é
automático, e criar uma migration sem `estabelecimento_id` quebra o build.

| ID | Rotina | Tam | Depende de |
|---|---|---|---|
| RT-INF-001 | Bootstrap: Maven multi-módulo, Spring Modulith, compose dev, Flyway baseline | M | — |
| RT-INF-002 | `TenantContext` + `SET LOCAL` por transação + RLS + **teste de vazamento** | G | INF-001 |
| RT-INF-003 | Catálogo de erros + `@RestControllerAdvice` + Problem Details | P | INF-001 |
| RT-INF-004 | Paginação keyset + `Money` + `Relogio` + IDs no `shared` | P | INF-001 |
| RT-IAM-001 | Provisionar estabelecimento (fuso, moeda, política de comissão) | M | INF-002 |
| RT-IAM-002 | Login com Argon2id + lockout progressivo | M | IAM-001 |
| RT-IAM-003 | Refresh rotativo com **detecção de reuso** (revoga a família) | M | IAM-002 |
| RT-IAM-004 | Logout e revogação de sessão | P | IAM-003 |
| RT-IAM-005 | MFA TOTP: enroll, verificação, códigos de recuperação | M | IAM-002 |
| RT-IAM-006 | `GET /me/capabilities` | M | IAM-002 |
| RT-IAM-007 | CRUD de usuário e atribuição de perfil | M | IAM-006 |
| RT-IAM-008 | Auditoria append-only (quem, o quê, antes/depois, IP, trace) | M | INF-002 |
| RT-INF-005 | `Idempotency-Key` (tabela, filtro, replay) | M | INF-003 |
| RT-INF-006 | Outbox: Modulith event publication + publisher + expurgo + métrica | M | INF-001 |
| RT-INF-007 | Cache Caffeine + listener `LISTEN/NOTIFY` + flush no reconnect | G | INF-001 |
| RT-INF-008 | Observabilidade: Sentry, Micrometer, log JSON com trace, `/actuator` | M | INF-001 |
| RT-INF-009 | CI/CD: testes, ArchUnit, Trivy, imagem multi-arch, deploy hmg | G | INF-008 |
| RT-INF-010 | Shell do front: rotas, auth, TanStack Query, tokens de design | G | IAM-006 |

> **Fora da fila, mas comece na semana 1:** abrir a conta Meta Business, verificar o negócio e
> submeter os templates do WhatsApp. A aprovação leva dias e é assíncrona. Descobrir isso na
> Fase 4 atrasa a fase inteira. Ver `11-fragilidades-e-riscos.md`, R-03.

---

## Fase 1 — Agenda

**Pronta quando:** a recepção roda um dia inteiro sem abrir a planilha.

| ID | Rotina | Tam | Depende de |
|---|---|---|---|
| RT-CLI-001 | Cadastro de cliente (nome + telefone E.164, resto opcional) | M | INF-002 |
| RT-CLI-002 | Busca de cliente por nome, apelido e **4 últimos dígitos** (trigram) | M | CLI-001 |
| RT-CLI-003 | Detecção de duplicado no cadastro + mesclagem | M | CLI-002 |
| RT-EQP-001 | Cadastro de profissional PJ (documento, contrato, comissão padrão) | M | IAM-007 |
| RT-EQP-002 | Jornada semanal por profissional | M | EQP-001 |
| RT-EQP-003 | Exceção de jornada: feriado, férias, curso, atestado | M | EQP-002 |
| RT-EQP-004 | Habilidades: quais serviços cada profissional executa | P | EQP-001, CAT-001 |
| RT-CAT-001 | Serviço com **blocos** ATIVO/PAUSA, duração e recursos exigidos | G | INF-002 |
| RT-CAT-002 | Preço com vigência + histórico | M | CAT-001 |
| RT-CAT-003 | Recurso (lavatório, cadeira, sala) | P | INF-002 |
| RT-CAT-004 | Override de duração por profissional | P | CAT-001, EQP-001 |
| RT-AGD-001 | **Consultar disponibilidade** (jornada − bloqueios − blocos ocupados) | G | CAT-001, EQP-002 |
| RT-AGD-002 | **Criar agendamento** com blocos e exclusion constraint | G | AGD-001, CLI-001 |
| RT-AGD-003 | Reagendar / mover (drag & drop) | M | AGD-002 |
| RT-AGD-004 | Confirmar agendamento | P | AGD-002 |
| RT-AGD-005 | Cancelar, com motivo | M | AGD-002 |
| RT-AGD-006 | Registrar no-show | P | AGD-002 |
| RT-AGD-007 | Bloqueio de agenda (arrastar na grade, sem formulário) | M | AGD-002 |
| RT-AGD-008 | **Empurrar agenda a partir de um horário** (profissional atrasou) | M | AGD-003 |
| RT-AGD-009 | Recorrência: gera ocorrências materializadas | G | AGD-002 |
| RT-AGD-010 | Fila de espera + chamada automática no cancelamento | M | AGD-005 |
| RT-AGD-011 | Grade de agenda no front: virtualização, drag & drop, otimista | GG | AGD-002, INF-010 |
| RT-AGD-012 | Painel do balcão via SSE, somente leitura | G | AGD-011 |
| RT-NOT-001 | Envio de mensagem com template, retry e opt-out | G | INF-006 |
| RT-NOT-002 | **Lembrete de agendamento 24h antes** (sem IA) | M | NOT-001, AGD-002 |
| RT-ARQ-001 | Upload por presigned URL + validação de magic bytes | M | INF-002 |

> `RT-NOT-002` é o maior retorno de negócio do sistema inteiro: lembrete reduz no-show de forma
> drástica e não precisa de agente de IA nenhum. Antecipado da Fase 4 de propósito.

---

## Fase 2 — Atendimento e comissão

**Pronta quando:** o fechamento do mês bate com a conta que o dono faz à mão.

| ID | Rotina | Tam | Depende de |
|---|---|---|---|
| RT-ATD-001 | Abrir comanda (do agendamento ou avulsa) | M | AGD-002 |
| RT-ATD-002 | Adicionar item de serviço + executantes | M | ATD-001, EQP-001 |
| RT-ATD-003 | Adicionar item de produto | M | ATD-001, EST-001 |
| RT-ATD-004 | Desconto com permissão e limite por perfil | M | ATD-002 |
| RT-ATD-005 | Executante adicional (assistente) com percentual próprio | M | ATD-002 |
| RT-ATD-006 | **Fechar comanda** — transacional: estoque + pagamento + comissão + evento | G | ATD-004, FIN-001, EST-004 |
| RT-ATD-007 | Estornar / reabrir comanda | M | ATD-006 |
| RT-ATD-008 | Tela de fechamento em um único passo, com split de pagamento | G | ATD-006, INF-010 |
| RT-FIN-001 | Registrar pagamento (1:N com a comanda, split) | M | ATD-001 |
| RT-FIN-002 | Regra de comissão em cascata + **snapshot** no executante | G | ATD-002 |
| RT-FIN-003 | Apurar comissão no fechamento da comanda | M | FIN-002, ATD-006 |
| RT-FIN-004 | Extrato do profissional, item a item | M | FIN-003 |
| RT-FIN-005 | Vale / adiantamento | M | FIN-004 |
| RT-FIN-006 | Fechamento de período e repasse | G | FIN-004, FIN-005 |
| RT-FIN-007 | Estorno de lançamento | M | FIN-003 |
| RT-FIN-008 | Comprovante de atendimento (impressão térmica ou WhatsApp) | M | ATD-006, NOT-001 |

---

## Fase 3 — Estoque

**Pronta quando:** o inventário físico bate com o saldo do sistema.

| ID | Rotina | Tam | Depende de |
|---|---|---|---|
| RT-EST-001 | Cadastro de produto (venda, consumo interno ou ambos) | M | INF-002 |
| RT-EST-002 | Entrada de lote com custo e validade | M | EST-001 |
| RT-EST-003 | Saldo e custo médio ponderado (projeção, não coluna) | M | EST-002 |
| RT-EST-004 | Baixa **FEFO** — venda e consumo interno | G | EST-003 |
| RT-EST-005 | Perda, ajuste e devolução com motivo obrigatório | M | EST-003 |
| RT-EST-006 | Inventário: contagem, divergência, ajuste em lote | G | EST-005 |
| RT-EST-007 | Consumo interno por serviço (ficha técnica: tinta, ox) | M | EST-004, CAT-001 |
| RT-EST-008 | Alertas de estoque mínimo e de lote vencendo | M | EST-003, NOT-001 |
| RT-EST-009 | Leitor de código de barras (HID: input focado + Enter) | P | EST-002 |

---

## Fase 4 — Conversacional

**Pronta quando:** um agendamento por áudio funciona ponta a ponta com auditoria completa.

| ID | Rotina | Tam | Depende de |
|---|---|---|---|
| RT-CNV-001 | Webhook WhatsApp: validação de assinatura + idempotência por `messageId` | M | INF-005 |
| RT-CNV-002 | Resolução de identidade por telefone → usuário efetivo e escopo | M | CNV-001, IAM-006 |
| RT-CNV-003 | Mídia de áudio → download → STT → transcrição guardada | M | CNV-001, ARQ-001 |
| RT-CNV-004 | Orquestração: allowlist de tools por perfil, teto de custo, rate limit | G | CNV-002 |
| RT-CNV-005 | Tools de agenda: `consultarDisponibilidade`, `simular`, `confirmar` | G | CNV-004, AGD-002 |
| RT-CNV-006 | `IntencaoPendente`: negociação com o profissional, TTL, botões | G | CNV-005, NOT-001 |
| RT-CNV-007 | Auditoria de ação por IA (`acao_ia`) + painel de revisão | M | CNV-004 |
| RT-CNV-008 | Aviso de IA ao cliente na primeira interação (LGPD) | P | CNV-002 |

**Padrão inegociável:** operação que grava passa por `simular` → confirmação humana explícita →
`confirmar`. O LLM nunca escreve direto no domínio. Mensagem de cliente é entrada não confiável;
a defesa contra prompt injection é a permissão do usuário efetivo, não o system prompt.

---

## Fase 5 — Conciliação

**Pronta quando:** divergência aparece na tela e não some sozinha.

| ID | Rotina | Tam | Depende de |
|---|---|---|---|
| RT-FIN-009 | Importar extrato do adquirente (CSV/OFX ou API — ver D3) | G | FIN-001 |
| RT-FIN-010 | Matching: valor + janela de tempo + NSU/autorização | G | FIN-009 |
| RT-FIN-011 | Resolução manual de divergência, com trilha | M | FIN-010 |
| RT-FIN-012 | Taxa por bandeira/parcela e previsão de recebíveis (D+1, D+30) | M | FIN-010 |

---

## Fase 6 — IA de precificação

**Pronta quando:** nenhuma sugestão é aplicada sem clique humano.

| ID | Rotina | Tam | Depende de |
|---|---|---|---|
| RT-EST-010 | Sugestão de preço/promoção com guardrail de margem mínima | G | EST-003, CAT-002 |
| RT-EST-011 | Aprovação humana + registro do racional da sugestão | M | EST-010 |
| RT-EST-012 | Campanha de queima de lote próximo do vencimento | M | EST-010, NOT-001 |

**Guardrails:** nunca abaixo do custo médio × (1 + margem mínima); nunca aplicar sozinha; toda
sugestão registrada com o racional que a gerou.

---

## Marcos de validação com o dono do salão

Não espere a fase acabar para mostrar. Combine estes quatro momentos:

| Quando | O que mostrar | O que você quer descobrir |
|---|---|---|
| Fim de RT-AGD-002 | Agendar um horário de verdade | Se o modelo de blocos bate com o serviço real dele |
| Fim de RT-AGD-011 | A grade da agenda | Se a recepção consegue usar sem treinamento |
| Fim de RT-FIN-004 | Extrato de um profissional de um mês real | Se o número bate com a planilha dele |
| Fim de RT-EST-006 | Inventário de uma prateleira | Se o cadastro reflete o estoque físico |

Os dois do meio são os que costumam devolver a maior lista de correções. É melhor recebê-la ali
do que na entrega final.
