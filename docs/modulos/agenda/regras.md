# Regras de negócio — módulo `agenda`

Catálogo. Rotinas **referenciam** estes IDs; nunca redefinem o enunciado.

---

### RN-AGD-001 — Agendamento só em serviço que o profissional executa

**Enunciado.** Só é possível agendar um serviço para um profissional que tenha esse serviço nas
suas habilidades e esteja ativo.

**Motivo.** Recepção agendava progressiva com quem só faz manicure, e a descoberta era no balcão,
com a cliente já sentada.

**Onde é garantida.** Aplicação: `AgendaPolitica.validarHabilidade`, consultando `EquipeApi`.
Não há constraint de banco — a habilidade vive em outro módulo e join entre módulos é proibido.

**Rotinas.** RT-AGD-002, RT-AGD-003, RT-AGD-009, RT-CNV-005
**Teste.** `CriarAgendamentoIT.profissional_sem_habilidade_e_rejeitado`
**Erro.** `ER-AGD-PROFISSIONAL_NAO_EXECUTA` (422) · **Configurável?** Não
**Origem.** 2026-08-28 · **Revisada em:** 2026-08-28

---

### RN-AGD-002 — Agendamento só dentro da jornada, descontadas as exceções

**Enunciado.** Todo bloco que ocupa o profissional precisa estar contido em uma faixa de jornada
do dia, subtraídas as exceções (feriado, férias, curso, atestado).

**Motivo.** Agendar às 19h para quem sai às 18h é conflito garantido com o profissional.

**Onde é garantida.** Aplicação: `DisponibilidadePolitica`. Não há constraint — a jornada é do
módulo `equipe` e muda retroativamente, o que tornaria a constraint intratável.

**Consequência aceita.** Alterar a jornada **não** cancela agendamentos já feitos fora dela. Eles
ficam órfãos e aparecem sinalizados na grade para decisão humana. Cancelar automaticamente seria
pior: some o compromisso da cliente sem ninguém avisar.

**Rotinas.** RT-AGD-001, RT-AGD-002, RT-AGD-003
**Teste.** `DisponibilidadeIT.fora_da_jornada_nao_aparece_como_livre`
**Erro.** `ER-AGD-FORA_DA_JORNADA` (422) · **Configurável?** Sim — `admin` pode forçar com
`agenda:write:all` e justificativa registrada em auditoria.
**Origem.** 2026-08-28

---

### RN-AGD-003 — Serviço define os blocos; o agendamento os materializa

**Enunciado.** Ao criar um agendamento, os blocos são gerados a partir da definição do serviço
(sequência ordenada de `ATIVO` e `PAUSA`, com duração cada). Blocos `ATIVO` ocupam o profissional;
blocos `PAUSA` não. Blocos que exijam recurso ocupam o recurso, inclusive durante a `PAUSA`.

**Motivo.** Durante a química, o profissional está livre para um encaixe, mas a cliente continua
ocupando a cadeira. Modelar com uma janela única força escolher qual dos dois estará errado.

**Onde é garantida.** Domínio: `Agendamento.materializarBlocos(Servico, Instant)`. O banco garante
a não sobreposição (RN-AGD-004), não a composição.

**Rotinas.** RT-AGD-002, RT-AGD-003, RT-AGD-009
**Teste.** `AgendamentoBlocosTest.servico_com_pausa_gera_tres_blocos_e_libera_profissional`
**Configurável?** Não — é o modelo. · **ADR.** [0003](../../adr/0003-agenda-em-blocos.md)
**Origem.** 2026-08-28

---

### RN-AGD-004 — Blocos do mesmo profissional não podem se sobrepor

**Enunciado.** Dois blocos que ocupam o mesmo profissional, em agendamentos com status
`SOLICITADO`, `CONFIRMADO` ou `EM_ATENDIMENTO`, não podem ter interseção de período.

**Motivo.** Duas clientes na mesma cadeira no mesmo horário. Foi a reclamação nº 1 do caderno.

**Onde é garantida.**
- **Banco (fonte da verdade):** constraint `bloco_sem_sobreposicao_profissional`
- **Aplicação (só UX):** `AgendaPolitica.validarConflito`, para dar erro amigável antes de gravar

A distinção importa: trava de aplicação não resiste a duas instâncias concorrentes. A aplicação
valida por educação; o banco garante.

**Rotinas.** RT-AGD-002, RT-AGD-003, RT-AGD-007, RT-AGD-008, RT-AGD-009, RT-CNV-005
**Teste.** `AgendamentoConcorrenteIT.duas_threads_no_mesmo_horario_uma_falha`
**Erro.** `ER-AGD-CONFLITO_HORARIO` (409) · **Configurável?** Não
**Origem.** 2026-08-28

---

### RN-AGD-005 — Blocos do mesmo recurso não podem se sobrepor

**Enunciado.** Idem RN-AGD-004, para `recurso_id`.
**Onde é garantida.** Constraint `bloco_sem_sobreposicao_recurso`.
**Rotinas.** RT-AGD-002, RT-AGD-003 · **Erro.** `ER-AGD-RECURSO_OCUPADO` (409)
**Origem.** 2026-08-28

---

### RN-AGD-006 — Status dos blocos acompanha o da cabeça, na mesma transação

**Enunciado.** Toda transição de status do agendamento atualiza o `status` de todos os seus blocos
na mesma transação.

**Motivo.** A constraint parcial precisa do status na própria linha do bloco — o Postgres não
aceita subquery em `EXCLUDE`. Se os blocos ficarem com status velho, um agendamento cancelado
continua bloqueando o horário. É a fragilidade conhecida do modelo de blocos, e o preço pago por
ele funcionar.

**Onde é garantida.** Domínio: `Agendamento.alterarStatus` altera cabeça e blocos juntos.
**Nunca por trigger** — regra invisível não é testável em unidade e some da revisão de código.

**Rotinas.** RT-AGD-004, RT-AGD-005, RT-AGD-006, RT-ATD-001, RT-ATD-006
**Teste.** `AgendamentoIT.cancelar_libera_todos_os_blocos_para_novo_agendamento`
**Configurável?** Não · **Origem.** 2026-08-28

---

### RN-AGD-007 — Transições de status são explícitas

**Enunciado.**
```
SOLICITADO   → CONFIRMADO | CANCELADO | NO_SHOW
CONFIRMADO   → EM_ATENDIMENTO | CANCELADO | NO_SHOW
EM_ATENDIMENTO → CONCLUIDO | CANCELADO
CONCLUIDO    → (terminal)
CANCELADO    → (terminal)
NO_SHOW      → (terminal)
```
Qualquer outra transição é erro de domínio.

**Motivo.** Sem máquina de estados explícita, a validação vira `if` espalhado por seis casos de
uso e diverge.

**Onde é garantida.** Domínio: `StatusAgendamento.podeTransicionarPara`.
**Rotinas.** todas as de escrita da agenda
**Teste.** `StatusAgendamentoTest` — tabela completa de transições válidas e inválidas
**Erro.** `ER-AGD-TRANSICAO_INVALIDA` (409) · **Configurável?** Não · **Origem.** 2026-08-28

---

### RN-AGD-008 — Não se agenda no passado

**Enunciado.** O início do primeiro bloco não pode ser anterior ao instante atual, exceto para
usuário com `agenda:write:all` registrando atendimento já ocorrido.

**Motivo.** A exceção existe porque a recepção lança atendimento de ontem que ficou no papel.

**Onde é garantida.** `AgendaPolitica.validarNaoRetroativo`, usando o port `Relogio`.
**Teste.** `CriarAgendamentoIT.passado_rejeitado_para_recepcao_e_aceito_para_admin`
**Erro.** `ER-AGD-DATA_PASSADA` (422) · **Configurável?** Não · **Origem.** 2026-08-28

---

### RN-AGD-009 — Cancelamento libera a vaga para a fila de espera

**Enunciado.** Cancelar publica `VagaLiberada`. O primeiro da fila compatível (mesmo serviço,
janela aceita) é notificado e tem TTL para responder antes do próximo.

**Onde é garantida.** `CancelarAgendamentoUseCase` publica; `ChamarFilaEsperaUseCase` consome.
**Rotinas.** RT-AGD-005, RT-AGD-010 · **Configurável?** Sim — fila pode ser desligada por
estabelecimento. · **Origem.** 2026-08-28

---

## Erros do módulo

| Código | HTTP | Quando | Texto sugerido ao usuário |
|---|---|---|---|
| `ER-AGD-CONFLITO_HORARIO` | 409 | RN-AGD-004 | "{Profissional} já tem atendimento das {hh:mm} às {hh:mm}." |
| `ER-AGD-RECURSO_OCUPADO` | 409 | RN-AGD-005 | "O {recurso} está ocupado nesse horário." |
| `ER-AGD-FORA_DA_JORNADA` | 422 | RN-AGD-002 | "{Profissional} não trabalha nesse horário." |
| `ER-AGD-PROFISSIONAL_NAO_EXECUTA` | 422 | RN-AGD-001 | "{Profissional} não faz {serviço}." |
| `ER-AGD-TRANSICAO_INVALIDA` | 409 | RN-AGD-007 | "Este agendamento já foi {status} e não pode mais ser alterado." |
| `ER-AGD-DATA_PASSADA` | 422 | RN-AGD-008 | "Não é possível agendar em uma data que já passou." |
| `ER-AGD-SERVICO_INATIVO` | 422 | — | "Este serviço não está mais disponível." |
