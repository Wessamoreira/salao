---
id: RT-AGD-002
titulo: Criar agendamento
modulo: agenda
fase: 1
perfil: completo
status: especificado
depende_de: [RT-CAT-001, RT-EQP-002, RT-EQP-004, RT-CLI-001, RT-AGD-001]
permissoes: [agenda:write:own, agenda:write:all]
eventos: [EV-AGD-001]
regras: [RN-AGD-001, RN-AGD-002, RN-AGD-003, RN-AGD-004, RN-AGD-005, RN-AGD-007, RN-AGD-008]
atualizado_em: 2026-08-28
---

# RT-AGD-002 — Criar agendamento

> **Rotina de referência.** Serve de exemplo do padrão. Ao escrever uma rotina nova, copie o
> `_templates/rotina.md` e use esta aqui para calibrar a profundidade esperada.

## 1. Objetivo

Reservar as janelas de tempo de um profissional (e dos recursos exigidos) para que um cliente
receba um serviço, sem que nenhuma dessas janelas colida com outra reserva.

## 2. Contexto de negócio

É a operação mais executada do sistema — dezenas de vezes por dia, quase sempre com o cliente ao
telefone. Hoje é feita em caderno, o que produz três falhas recorrentes: horário duplicado
(descoberto quando as duas clientes chegam), agendamento com profissional que não faz o serviço,
e horário fora da jornada. As três são eliminadas aqui.

Se esta rotina for lenta ou trabalhosa, a recepção volta para o caderno e o projeto inteiro
falha — por isso R-UX-01 e R-UX-02 são requisitos e não sugestões.

## 3. Atores e permissões

| Ator | Permissão | Escopo | Observação |
|---|---|---|---|
| Recepção | `agenda:write:all` | Qualquer profissional | Uso principal |
| Administrador / Gerente | `agenda:write:all` | Qualquer profissional | Pode forçar fora da jornada (RN-AGD-002) e data passada (RN-AGD-008) |
| Profissional | `agenda:write:own` | Só a própria agenda | `profissionalId` diferente do seu → **404** |
| Bot (`conversacional`) | Herdada do usuário efetivo | — | Só via `RT-CNV-005` (`simular` → `confirmar`) |
| Painel | — | — | Sem acesso de escrita |

## 4. Pré-condições

- [ ] Estabelecimento ativo, com fuso configurado
- [ ] Cliente existente (ou criado inline por `RT-CLI-001`)
- [ ] Serviço ativo, com blocos definidos (`RT-CAT-001`)
- [ ] Profissional ativo, com jornada (`RT-EQP-002`) e habilitado no serviço (`RT-EQP-004`)
- [ ] Recursos exigidos pelo serviço cadastrados (`RT-CAT-003`)

## 5. Fluxo principal

1. Requisição chega com cliente, profissional, serviço, início e origem.
2. `TenantContext` resolvido do JWT; `SET LOCAL app.tenant_id` aplicado na transação.
3. Autorização: `agenda:write:all`, ou `agenda:write:own` **e** `profissionalId` == o do usuário.
4. Carrega o serviço via `CatalogoApi` (blocos, duração, recursos exigidos) — cacheado.
5. Valida **RN-AGD-001** (habilidade) e **RN-AGD-008** (não retroativo).
6. Domínio materializa os blocos a partir do serviço e do instante inicial (**RN-AGD-003**),
   atribuindo `profissional_id` aos blocos `ATIVO` e `recurso_id` aos que exigem recurso.
7. Valida **RN-AGD-002** (jornada) contra `EquipeApi`.
8. Pré-checagem de conflito (**RN-AGD-004/005**) — apenas para erro amigável.
9. Aloca os recursos exigidos: escolhe o primeiro livre da categoria no período.
10. Persiste a cabeça em `agendamento` e os blocos em `agendamento_bloco`, em lote.
11. **O banco decide.** Violação de exclusion constraint → `ER-AGD-CONFLITO_HORARIO` (409).
12. Registra `AgendamentoCriado` no outbox (mesmo commit).
13. Commit. Devolve 201 com o agendamento e os blocos materializados.
14. Assíncrono: `notificacao` envia a confirmação ao cliente.

## 6. Fluxos alternativos

**A1 — Cliente novo.** O front envia `cliente.novo` com nome e telefone; o caso de uso chama
`ClienteApi.criarRapido` na mesma transação (R-UX-04). Telefone normalizado para E.164.

**A2 — Encaixe na pausa de outro agendamento.** Nada especial: o bloco `PAUSA` do outro
agendamento não tem `profissional_id`, então não participa da exclusão. É o modelo de blocos
funcionando (RN-AGD-003).

**A3 — Duração diferente do padrão.** `duracaoMinutos` no request sobrepõe a do serviço,
distribuída proporcionalmente entre os blocos `ATIVO`. Registrado para auditoria.

**A4 — Fora da jornada, com permissão.** `agenda:write:all` + `forcarForaJornada: true` +
`justificativa` obrigatória, registrada em `auditoria`.

**A5 — Sem recurso disponível.** Se o serviço exige recurso e nenhum da categoria está livre no
período → `ER-AGD-RECURSO_OCUPADO` (409), sugerindo o próximo horário com recurso livre.

## 7. Regras de negócio aplicadas

| ID | Resumo | Garantida em |
|---|---|---|
| RN-AGD-001 | Profissional precisa executar o serviço | Aplicação |
| RN-AGD-002 | Dentro da jornada, menos exceções | Aplicação (forçável por admin) |
| RN-AGD-003 | Blocos materializados do serviço | Domínio |
| RN-AGD-004 | Blocos do profissional não se sobrepõem | **Banco** |
| RN-AGD-005 | Blocos do recurso não se sobrepõem | **Banco** |
| RN-AGD-007 | Nasce em `SOLICITADO` ou `CONFIRMADO` | Domínio |
| RN-AGD-008 | Não agenda no passado (exceto admin) | Aplicação |

## 8. Contrato de API

```http
POST /api/v1/agendamentos
Idempotency-Key: 8f14e45f-ea0a-4b8f-9d1e-1a2b3c4d5e6f
Content-Type: application/json

{
  "clienteId": "0d9a...",
  "profissionalId": "7b21...",
  "servicoId": "3c55...",
  "inicio": "2026-09-02T13:00:00Z",
  "origem": "WEB",
  "observacao": "Cliente pediu tom mais claro",
  "duracaoMinutos": null,
  "forcarForaJornada": false
}
```

**201 Created**

```json
{
  "id": "a1b2...",
  "status": "CONFIRMADO",
  "inicio": "2026-09-02T13:00:00Z",
  "fim": "2026-09-02T14:40:00Z",
  "cliente": { "id": "0d9a...", "nome": "Marilda S." },
  "profissional": { "id": "7b21...", "nome": "Ana" },
  "servico": { "id": "3c55...", "nome": "Mechas" },
  "blocos": [
    { "ordem": 1, "tipo": "ATIVO", "inicio": "...T13:00:00Z", "fim": "...T13:30:00Z", "recursoId": "lav-1" },
    { "ordem": 2, "tipo": "PAUSA", "inicio": "...T13:30:00Z", "fim": "...T14:10:00Z", "recursoId": "cad-3" },
    { "ordem": 3, "tipo": "ATIVO", "inicio": "...T14:10:00Z", "fim": "...T14:40:00Z", "recursoId": "cad-3" }
  ],
  "versao": 0
}
```

**Erros**

| HTTP | Código | Quando |
|---|---|---|
| 409 | `ER-AGD-CONFLITO_HORARIO` | RN-AGD-004 — inclusive na corrida perdida contra o banco |
| 409 | `ER-AGD-RECURSO_OCUPADO` | RN-AGD-005 / A5 |
| 422 | `ER-AGD-FORA_DA_JORNADA` | RN-AGD-002 sem `forcarForaJornada` |
| 422 | `ER-AGD-PROFISSIONAL_NAO_EXECUTA` | RN-AGD-001 |
| 422 | `ER-AGD-DATA_PASSADA` | RN-AGD-008 |
| 422 | `ER-AGD-SERVICO_INATIVO` | Serviço desativado |
| 422 | `ER-INF-IDEMPOTENCIA_CONFLITO` | Mesma chave, payload diferente |
| 404 | `ER-INF-NAO_ENCONTRADO` | Id de outro tenant, ou fora do escopo `:own` |

## 9. Dados

**Tabelas:** `agendamento` (insert), `agendamento_bloco` (insert em lote), `event_publication`
(insert), `auditoria` (insert), `idempotencia` (insert).

**Migration:** `V12__agendamento_e_blocos.sql` — cria as duas tabelas, `btree_gist`, as duas
exclusion constraints e os índices de `04-modelo-de-dados.md`.

**Orçamento de queries: 6.**
1 serviço (cache, 0 em hit) · 1 jornada + exceções · 1 habilidade · 1 recursos livres no período ·
1 insert da cabeça · 1 insert em lote dos blocos.
`CriarAgendamentoIT.orcamento_de_queries_respeitado` falha se passar.

## 10. Efeitos colaterais

| Efeito | Quando | Onde |
|---|---|---|
| `AgendamentoCriado` no outbox | Mesmo commit | `event_publication` |
| Confirmação ao cliente | Assíncrono, após publicação | `notificacao` |
| Lembrete 24h antes | Agendado na criação | `RT-NOT-002` |
| Registro em auditoria | Mesmo commit | `auditoria` |
| Cliente criado (A1) | Mesmo commit | `cliente` |

Nenhum efeito em estoque ou financeiro. Agendamento não movimenta dinheiro — isso é `atendimento`.

## 11. Casos de borda e erros

| Situação | Comportamento | HTTP | Código |
|---|---|---|---|
| Horário preenchido entre a validação e o insert | Banco recusa; erro traduzido pelo **nome da constraint** | 409 | `ER-AGD-CONFLITO_HORARIO` |
| Serviço sem blocos definidos | Trata como bloco `ATIVO` único de duração total | 201 | — |
| Serviço atravessa a meia-noite | Permitido; blocos em UTC, exibição converte com o fuso do estabelecimento | 201 | — |
| Início no fim do horário de verão | Cálculo em UTC não é afetado; teste com fuso que ainda pratica DST | 201 | — |
| Profissional desativado entre a tela e o envio | Rejeita | 422 | `ER-AGD-PROFISSIONAL_NAO_EXECUTA` |
| Duplo clique no botão | `Idempotency-Key` devolve a resposta original | 201 | `Idempotent-Replay: true` |
| Profissional `:own` tentando id alheio | Não confirma existência | 404 | `ER-INF-NAO_ENCONTRADO` |
| Bloco `PAUSA` mais longo que a jornada restante | Rejeita — a pausa termina depois do expediente | 422 | `ER-AGD-FORA_DA_JORNADA` |

## 12. Concorrência e idempotência

- **Idempotência:** `Idempotency-Key` obrigatória para origem `WHATSAPP`; recomendada para `WEB`.
- **Corrida:** a exclusion constraint é a única árbitra. A pré-checagem existe para dar erro bonito
  antes, não para evitar a corrida. Teste com duas threads simultâneas prova que exatamente uma vence.
- **Sem lock de aplicação.** Nada de `synchronized`, nada de lock em memória: não sobrevive a duas
  instâncias e dá falsa segurança.
- O front trata 409 como estado normal, não como falha: recarrega a faixa de horário e mostra as
  opções livres (R-UX-20).

## 13. Observabilidade

| O quê | Tipo | Nome | Alerta |
|---|---|---|---|
| Latência | Timer | `agenda.criar.duracao` | p95 > 500ms |
| Conflitos | Contador | `agenda.criar.conflito` | Subida abrupta = concorrência real ou bug de disponibilidade |
| Por origem | Tag | `origem=WEB\|WHATSAPP\|RECORRENCIA` | — |
| Log | Estruturado | `agendamento_criado` com `traceId`, `tenantId`, `agendamentoId` | Sem nome de cliente, sem telefone |

## 14. UX e front

- **Estados:** carregando (skeleton da grade, nunca spinner de tela cheia) · vazio ("nenhum
  atendimento hoje" + ação de agendar) · erro por código · sucesso otimista.
- **Otimista:** o bloco aparece na grade antes da resposta e some com rollback visível em erro.
- **Atalhos (R-UX-02):** fluxo completo por teclado, `Esc` fecha sem perder o digitado.
- **Conflito (R-UX-06):** área de destino em vermelho **antes de soltar**, no arraste.
- **Cliente novo inline (R-UX-04):** só nome e telefone.
- **Texto do 409:** "A Ana já tem atendimento das 13:00 às 14:40. O próximo horário livre é 15:00."
  — com o próximo horário calculado, não só a recusa.

## 15. Testes obrigatórios

- [ ] `CriarAgendamentoIT.caminho_feliz_gera_cabeca_e_blocos`
- [ ] `CriarAgendamentoIT.servico_com_pausa_libera_profissional_e_mantem_recurso`
- [ ] `CriarAgendamentoIT.encaixe_na_pausa_de_outro_agendamento_e_aceito`
- [ ] `AgendamentoConcorrenteIT.duas_threads_no_mesmo_horario_uma_falha`
- [ ] `CriarAgendamentoIT.profissional_sem_habilidade_e_rejeitado`
- [ ] `CriarAgendamentoIT.fora_da_jornada_rejeitado_sem_permissao`
- [ ] `CriarAgendamentoIT.fora_da_jornada_aceito_com_forcar_e_justificativa`
- [ ] `CriarAgendamentoIT.passado_rejeitado_para_recepcao_e_aceito_para_admin`
- [ ] `CriarAgendamentoIT.profissional_own_com_id_alheio_recebe_404`
- [ ] `CriarAgendamentoIT.tenant_b_nao_ve_agendamento_do_tenant_a`
- [ ] `CriarAgendamentoIT.idempotency_key_repetida_nao_duplica`
- [ ] `CriarAgendamentoIT.recurso_indisponivel_retorna_409`
- [ ] `CriarAgendamentoIT.orcamento_de_queries_respeitado`
- [ ] `CriarAgendamentoIT.evento_vai_para_o_outbox_no_mesmo_commit`

## 16. Como testar manualmente

1. Cadastre "Mechas" com blocos `ATIVO 30 / PAUSA 40 / ATIVO 30` e recurso "lavatório".
2. Jornada da Ana: seg–sex, 09:00–18:00.
3. Agende Mechas para a Ana às 13:00. Confira: três blocos, fim às 14:40.
4. Agende um "Corte" de 30 min para a Ana às 13:40 — **deve ser aceito** (é a pausa).
5. Tente outro "Corte" às 13:10 — **deve recusar** com conflito.
6. Tente às 18:30 — deve recusar por jornada; repita como admin com justificativa: aceita.
7. Repita o passo 3 com a mesma `Idempotency-Key`: mesma resposta, sem duplicar.

## 17. Decisões e trade-offs

| Decisão | Alternativa descartada | Por quê |
|---|---|---|
| Blocos em tabela filha | Um `tstzrange` na cabeça | Não expressa pausa nem cadeira ocupada sem profissional (ADR-0003) |
| `status` duplicado no bloco | Subquery na constraint | Postgres não aceita subquery em `EXCLUDE`. Preço: RN-AGD-006 |
| Banco como árbitro do conflito | Lock de aplicação | Não sobrevive a duas instâncias |
| Alocar recurso na criação | Alocar no atendimento | Recurso alocado tarde vira conflito na hora, com a cliente presente |
| Jornada na aplicação | Constraint de banco | Jornada é de outro módulo e muda retroativamente |
| Rejeitar 404 no escopo `:own` | 403 | 403 confirma que o recurso existe |

## 18. Pendências

- [ ] Confirmar com o dono se encaixe na pausa é liberado automaticamente ou exige clique
      (pergunta 7 de `13-perguntas-em-aberto.md`) — hoje o default é **manual**
- [ ] Definir se recurso é escolhido pelo usuário ou alocado automaticamente (hoje: automático,
      primeiro livre da categoria)
