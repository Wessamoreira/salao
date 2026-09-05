# Regras de negócio — módulo `agenda`

Catálogo. Rotinas **referenciam** estes IDs; nunca redefinem o enunciado.

---

### RN-AGD-001 — Agendamento só em serviço que o profissional executa

**Enunciado.** Só é possível agendar um serviço para um profissional que tenha esse serviço nas
suas habilidades e esteja ativo.

**Motivo.** Recepção agendava progressiva com quem só faz manicure, e a descoberta era no balcão,
com a cliente já sentada.

**Onde é garantida.** Aplicação: `AgendaPolitica.validarHabilidade`, consultando
`ProfissionalApi.executa` (RT-EQP-004). Não há constraint de banco — a habilidade vive em outro
módulo e join entre módulos é proibido.

`executa` já devolve `false` para profissional inativo, então a validação é uma pergunta só: a
checagem de atividade mora na consulta e não em quem chama, para nenhum chamador esquecer uma das
duas. E note que "sem habilidade" tem dois sentidos, resolvidos por RN-EQP-013: no regime aberto o
profissional executa tudo; só no restrito a lista limita.

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

**Onde é garantida.** `AgendaJdbc.alterarStatus` altera cabeça e blocos juntos, e é o **único**
caminho por onde o status muda — não haver outro caminho é o que garante que nenhum caso de uso
faça metade. **Nunca por trigger** — regra invisível não é testável em unidade e some da revisão de
código.

*(Nota de RT-AGD-004: a proibição de gatilho foi mantida por ser decisão escrita, mas convive com
uma tensão — RLS e `EXCLUDE` são igualmente invisíveis e o projeto confia neles, verificando-os por
teste de integração. Se um dia a divergência de status acontecer de verdade, vale reabrir.)*

**Rotinas.** RT-AGD-004, RT-AGD-005, RT-AGD-006, RT-ATD-001, RT-ATD-006
**Teste.** `ConfirmarAgendamentoIT.confirma_e_os_blocos_acompanham` · (cancelamento em RT-AGD-005)
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

**Enunciado.** Cancelar publica `AgendamentoCancelado`, que **carrega a janela liberada**
(profissional, serviço, início e fim). O primeiro da fila compatível é notificado e tem TTL para
responder antes do próximo.

*(Revisado em RT-AGD-005: o enunciado dizia `VagaLiberada`, um segundo evento. Seriam dois eventos
com o mesmo conteúdo no mesmo instante — `AgendamentoCancelado` já carrega tudo de que a fila
precisa, e buscar o agendamento de volta não serviria, porque ele acabou de deixar de existir para
a agenda.)*

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

---

### RN-AGD-010 — Janela é fechada no início e aberta no fim

**Enunciado.** Todo período da agenda é `[inicio, fim)`. Dois atendimentos que se encostam — um
termina às 10h, o outro começa às 10h — **não** conflitam.

**Motivo.** Com o fim fechado, todo horário cheio de agenda seria um conflito: a recepção
aprenderia a marcar às 10h01 e o dia perderia um minuto por cliente.

E a semântica precisa ser **a mesma** no domínio e no banco. Se `Janela.cruzaCom` discordasse do
`tstzrange` das constraints, a aplicação ofereceria horários que o banco recusaria na hora de
gravar — ou esconderia horários que ele aceitaria.

**Onde é garantida.** `Janela.cruzaCom` · `tstzrange` nas constraints de exclusão (V25).
**Rotinas.** RT-AGD-001, RT-AGD-002 · **Teste.**
`JanelaESubtracaoTest.encostar_nao_e_cruzar`, `DisponibilidadeIT.banco_deixa_encostar`,
`BuscaDeEncaixeTest.encostar_nao_e_conflito`
**Configurável?** Não · **Origem.** 2026-09-04

---

### RN-AGD-011 — O bloco cabe em uma janela livre, não na soma delas

**Enunciado.** Um bloco `ATIVO` precisa caber inteiro dentro de **um** intervalo livre do
profissional. Blocos `PAUSA` não exigem o profissional, mas todo bloco que exija recurso precisa de
unidade livre — inclusive na pausa.

**Motivo.** Livre das 9h às 12h e das 13h às 18h são oito horas, e mesmo assim um serviço de 45
minutos não começa às 11h30: atravessaria o almoço. Somar as durações disponíveis e comparar com a
duração do serviço é o erro mais fácil de cometer aqui.

A metade da pausa é o que faz a agenda render: durante a química a profissional atende outra
cliente. Exigir que ela estivesse livre jogaria fora 40 minutos por coloração.

**Onde é garantida.** `BuscaDeEncaixe.cabe`.
**Rotinas.** RT-AGD-001, RT-AGD-002, RT-AGD-008 · **Teste.**
`BuscaDeEncaixeTest.ativo_nao_atravessa_buraco`,
`BuscaDeEncaixeTest.pausa_nao_exige_o_profissional`,
`BuscaDeEncaixeTest.recurso_na_pausa_bloqueia`, `DisponibilidadeIT.pausa_libera_encaixe`
**Configurável?** Não — é o modelo · **Origem.** 2026-09-04

---

### RN-AGD-012 — A grade de horários é do salão, e alinhada ao dia

**Enunciado.** A disponibilidade oferece horários de `granularidade_agenda_minutos` em
`granularidade_agenda_minutos`, contados a partir do início do dia civil no fuso do salão.

**Motivo.** Uns salões marcam de 15 em 15, outros de 30, e barbearia costuma trabalhar de 10. Um
número fixo estaria errado para metade deles, e o efeito não seria um erro — seria a agenda
oferecendo horários que aquele salão não usa.

O alinhamento é ao dia e não à jornada de cada um: por profissional, duas colunas da grade da
recepção mostrariam horários desencontrados (9h05 numa, 9h00 na outra) e o dia ficaria ilegível.

**Onde é garantida.** `estabelecimento.granularidade_agenda_minutos` (V25) ·
`BuscaDeEncaixe.horarios`.
**Rotinas.** RT-AGD-001, RT-AGD-011 · **Teste.** `BuscaDeEncaixeTest.granularidade_manda`
**Configurável?** Sim, por salão (5 a 60 minutos) · **Origem.** 2026-09-04

---

### RN-AGD-013 — Quem marca decide o status inicial

**Enunciado.** Agendamento criado com origem `PAINEL` ou `RECORRENCIA` nasce `CONFIRMADO`; com
origem `WEB` ou `WHATSAPP`, nasce `SOLICITADO`.

**Motivo.** Quem marca no balcão já falou com a cliente ali — pedir confirmação seria criar
trabalho para confirmar o que acabou de ser combinado. Quem marca sozinho pelo site ou pelo
WhatsApp não passou por ninguém do salão: sem a distinção, a agenda de sábado encheria de horário
que ninguém validou, e o salão descobriria no sábado.

Os dois ocupam a agenda igualmente (`StatusAgendamento.OCUPAM_A_AGENDA`) — a diferença é sobre
quem precisa olhar, não sobre reservar o horário.

**Onde é garantida.** `StatusAgendamento.inicialPara`.
**Rotinas.** RT-AGD-002, RT-AGD-004, RT-CNV-005 · **Teste.**
`CriarAgendamentoIT.origem_decide_o_status`
**Configurável?** Não hoje. É candidato natural a virar configuração do salão
**Origem.** 2026-09-04

---

### RN-AGD-014 — A unidade do recurso é escolhida na marcação, não contada

**Enunciado.** Ao criar um agendamento, cada bloco que exige recurso recebe uma **unidade**
concreta (`1..quantidade`), a primeira livre no período.

**Motivo.** `recurso` é contável (RN-CAT-009) e `EXCLUDE` garante *no máximo um*, não *no máximo
três*. Contar na aplicação — "dois lavatórios ocupados, ainda cabe" — é verificação sob
concorrência sem trava: duas marcações simultâneas passam pela mesma contagem e estouram a
capacidade. Escolhendo qual unidade, a constraint sobre `(recurso_id, unidade, periodo)` devolve a
garantia ao banco, com o mesmo mecanismo que protege a jornada.

**Custo aceito.** A escolha é gulosa. Em grafo de intervalos, alocar por ordem de *início* é ótimo
— mas as marcações não chegam nessa ordem, e uma marcação posterior que comece antes pode ser
recusada mesmo cabendo com um rearranjo. Com dois ou três lavatórios pode nunca aparecer: medir
antes de complicar.

**Onde é garantida.** `AlocadorDeUnidades.primeiraLivre` · constraint
`bloco_sem_sobreposicao_recurso` (V25).
**Rotinas.** RT-AGD-002, RT-AGD-003 · **Teste.** `CriarAgendamentoIT.aloca_a_segunda_unidade`,
`CriarAgendamentoIT.recurso_indisponivel_retorna_409`,
`DisponibilidadeIT.banco_garante_capacidade_por_unidade`
**Erro.** `ER-AGD-RECURSO_OCUPADO` (409) · **Configurável?** Não
**Origem.** 2026-09-04

---

### RN-AGD-015 — Só se move o que ainda vai acontecer

**Enunciado.** Reagendar exige status `SOLICITADO` ou `CONFIRMADO`. Ao mover, os blocos antigos são
apagados e os novos gravados **na mesma transação**, com o delete antes da alocação de recursos.

**Motivo.** Um atendimento `EM_ATENDIMENTO` não se arrasta: a cliente já está na cadeira. Um
terminal, muito menos — mover o passado reescreveria o que aconteceu.

E a ordem importa mais do que parece: mover das 13h para as 13h15 é mover para um horário que se
**sobrepõe ao atual**. Sem apagar antes, o agendamento colide consigo mesmo na exclusion constraint
e a recepção vê "horário ocupado" apontando para o próprio atendimento que tenta mover. O mesmo
vale para o recurso: sem o delete anterior à alocação, ele disputa o lavatório com ele mesmo.

Na mesma transação porque, se ela falhar depois do delete, o horário não pode ficar vago — o
rollback devolve os blocos e a cliente continua marcada onde estava.

**Onde é garantida.** `StatusAgendamento.podeSerMovido` · `ReagendarUseCase` (ordem das operações)
· `AgendaJdbc.apagarBlocos`.
**Rotinas.** RT-AGD-003, RT-AGD-008 · **Teste.**
`ReagendarIT.mover_para_horario_sobreposto`,
`ReagendarIT.recurso_proprio_nao_disputa_consigo_mesmo`,
`ReagendarIT.conflito_faz_rollback_completo`, `ReagendarIT.em_atendimento_nao_move`
**Erro.** `ER-AGD-NAO_PODE_REAGENDAR` (422) · **Configurável?** Não
**Origem.** 2026-09-04

---

### RN-AGD-016 — Mudar de coluna adota o ritmo de quem vai atender; ficar preserva

**Enunciado.** Ao reagendar, a duração é: a informada no pedido, se houver; senão a atual, quando o
profissional não muda; senão a receita do profissional de destino.

**Motivo.** Arrastar da coluna da Ana (escova em 30) para a da Júlia (45 do catálogo) faz o bloco
**crescer** — é o que RN-CAT-013 diz e o que a grade precisa mostrar. Esconder seria prometer um
horário que não existe.

Mas quem move um bloco de 90 minutos quinze minutos para frente espera um bloco de 90 minutos. Sem
a preservação, uma duração forçada na criação se perderia no primeiro arrasto, em silêncio.

**Onde é garantida.** `ReagendarUseCase.duracaoAPreservar`.
**Rotinas.** RT-AGD-003 · **Teste.**
`ReagendarIT.mudar_de_profissional_recalcula_a_duracao`,
`ReagendarIT.mesma_coluna_preserva_a_duracao`
**Configurável?** Não · **Origem.** 2026-09-04

---

### RN-AGD-017 — Cancelar exige motivo, e registra quem cancelou

**Enunciado.** Cancelar exige um motivo em texto livre e a marcação de quem desmarcou — a cliente
ou o salão. Os dois são gravados na mesma escrita que muda o status. Cancelamento é terminal: não
existe desfazer, e cancelar um cancelado é recusado.

**Motivo.** Sem o motivo, o histórico da cliente vira uma coluna de "cancelado" sem sentido. Sem a
autoria, *"a Marilda desmarcou três vezes"* e *"eu remarquei a Marilda três vezes porque a Ana
faltou"* viram a mesma linha no relatório — e são conversas opostas.

Na mesma escrita porque um cancelamento gravado sem o motivo, ainda que por um instante, já nasceu
incompleto, e não há segunda chance num status terminal.

Recusar o segundo cancelamento é onde esta regra **difere** de RT-AGD-004: confirmar o já confirmado
é inócuo e devolve o estado; cancelar o já cancelado chega com outro motivo e outra autoria, e
aceitar em silêncio sobrescreveria o registro do que aconteceu. A diferença é se o segundo pedido
carrega informação nova.

**Onde é garantida.** `CancelarAgendamentoUseCase` · `AgendaJdbc.alterarStatus` com motivo e
autoria · coluna `agendamento.cancelado_pela_cliente` (V26).
**Rotinas.** RT-AGD-005, RT-AGD-006 · **Teste.**
`CancelarAgendamentoIT.motivo_obrigatorio`, `CancelarAgendamentoIT.motivo_e_autoria_gravados`,
`CancelarAgendamentoIT.cancelar_duas_vezes`
**Erro.** `ER-AGD-MOTIVO_OBRIGATORIO` (422), `ER-AGD-TRANSICAO_INVALIDA` (409)
**Configurável?** Não · **Origem.** 2026-09-04

---

### RN-AGD-018 — No-show só depois de o horário começar

**Enunciado.** Registrar falta exige que o início do agendamento já tenha passado. Basta ter
começado — não é preciso esperar o fim. O status resultante é terminal e libera o horário.

**Motivo.** Cancelar é sobre o futuro; no-show é sobre o passado. Marcar antes registraria uma
ausência que não aconteceu, num status terminal que não teria como ser desfeito quando a cliente
chegasse na hora certa.

Basta o início porque às 13h15 de um horário das 13h a recepção já sabe. Exigir o fim faria o salão
esperar uma hora e meia para registrar o evidente, e o resto do horário — que poderia receber um
encaixe — ficaria bloqueado esse tempo todo.

**Corolário.** `CANCELADO` não vira `NO_SHOW`: quem avisou que não vinha não faltou. Misturar os
dois estragaria o número que sustenta a conversa com a cliente.

**Onde é garantida.** `RegistrarNoShowUseCase`, comparando com o port `Relogio` (RN-INF-006) ·
máquina de estados para o corolário.
**Rotinas.** RT-AGD-006 · **Teste.** `RegistrarNoShowIT.nao_marca_falta_no_futuro`,
`RegistrarNoShowIT.basta_ter_comecado`, `RegistrarNoShowIT.cancelado_nao_vira_falta`
**Erro.** `ER-AGD-AINDA_NAO_COMECOU` (422) · **Configurável?** A tolerância ainda não é, e deveria
**Origem.** 2026-09-04

---

### RN-AGD-019 — Prejuízo de falta conta o tempo do profissional, não o do relógio

**Enunciado.** `minutosPerdidos` de um no-show é a soma dos blocos que ocupam o profissional, não a
duração total do serviço.

**Motivo.** Numa coloração de 100 minutos, 40 são pausa e a profissional estaria livre de qualquer
jeito. Contar os 100 infla o prejuízo — e tira a credibilidade do número justamente na conversa em
que ele precisa ser levado a sério.

É a mesma distinção que faz a agenda render (RN-CAT-001), aplicada ao relatório.

**Onde é garantida.** `AgendamentoResumo.minutosDoProfissional`, usado por
`RegistrarNoShowUseCase`.
**Rotinas.** RT-AGD-006, RT-REL-* · **Teste.**
`RegistrarNoShowIT.minutos_perdidos_descontam_a_pausa`
**Configurável?** Não · **Origem.** 2026-09-04

---

### RN-AGD-020 — Bloqueio de grade é linha do mesmo bloco dos atendimentos

**Enunciado.** Um bloqueio de agenda vira linha em `agendamento_bloco`, com `tipo = 'BLOQUEIO'`,
`agendamento_id` nulo e status `CONFIRMADO`. Ele participa das **mesmas** exclusion constraints dos
atendimentos, nos dois sentidos: não se marca por cima de um bloqueio, e não se bloqueia por cima de
um atendimento.

**Motivo.** Uma tabela própria com constraint espelhada teria duas exclusões para manter coerentes,
e a segunda seria a que ficasse para trás. Como linha do mesmo bloco, a garantia é uma só — e
RT-AGD-002 e RT-AGD-003 não precisaram de nenhuma linha a mais.

Recusar o bloqueio por cima de um atendimento é o comportamento certo: quem quer o horário livre
cancela primeiro, avisando a cliente, em vez de fazê-la chegar e descobrir.

**Distinção de RT-EQP-003.** O bloqueio de *jornada* molda quando a pessoa trabalha, entra no
cálculo de disponibilidade e é **forçável** por admin. O de *grade* ocupa o horário, vale para
recurso e **não é forçável**: "a Ana está em curso" é opinião do dono; "o lavatório está quebrado"
não é.

**Onde é garantida.** Tabela `bloqueio` + linha em `agendamento_bloco` (V27) ·
`bloco_pertence_a_um_dono` impede linha órfã · as constraints de V25.
**Rotinas.** RT-AGD-007, RT-AGD-002, RT-AGD-003 · **Teste.**
`BloquearGradeIT.agendamento_nao_passa_por_cima_do_bloqueio`,
`BloquearGradeIT.bloqueio_nao_passa_por_cima_do_agendamento`,
`BloquearGradeIT.bloqueio_de_recurso`, `BloquearGradeIT.remover_devolve_o_horario`
**Erro.** `ER-AGD-CONFLITO_HORARIO` (409), `ER-AGD-RECURSO_OCUPADO` (409)
**Configurável?** Não · **Origem.** 2026-09-04

---

### RN-AGD-021 — Empurrar a agenda é tudo ou nada, e força a jornada

**Enunciado.** Empurrar move todos os agendamentos móveis de um profissional a partir de um instante,
numa transação só. Se qualquer um não couber, **nada se move**. O empurrão força a jornada
automaticamente, com justificativa obrigatória registrada na trilha; conflito com outro atendimento,
recurso ou bloqueio continua sendo recusa.

**Motivo.** Uma agenda empurrada pela metade é pior do que uma não empurrada: a recepção não
consegue dizer em que estado ela está, e já ligou para parte das clientes avisando um horário que
talvez não exista.

Forçar a jornada é a consequência esperada — se a profissional atrasou meia hora, o dia dela termina
meia hora depois. Recusar por causa disso tornaria a rotina inútil no caso em que ela mais serve. A
distinção é que a jornada é informação sobre o dia daquela pessoa, e o conflito é sobre o dia de
outra.

**Implementação.** Os blocos de **todos** os alvos são apagados antes de qualquer regravação, para o
conjunto não disputar consigo mesmo — a mesma razão de RN-AGD-015, aplicada a um conjunto. Sem isso
seria preciso mover do último para o primeiro ao empurrar para frente, e o inverso ao puxar para
trás.

**Onde é garantida.** `EmpurrarAgendaUseCase`, uma transação · `AgendaJdbc.moveisDe` filtra por
status móvel.
**Rotinas.** RT-AGD-008 · **Teste.** `EmpurrarAgendaIT.tudo_ou_nada`,
`EmpurrarAgendaIT.empurrar_nao_colide_consigo_mesmo`,
`EmpurrarAgendaIT.sai_da_jornada_sem_reclamar`, `EmpurrarAgendaIT.em_atendimento_fica`
**Erro.** `ER-AGD-EMPURRAO_NAO_COUBE` (409) · **Configurável?** Não
**Origem.** 2026-09-04

---

### RN-AGD-022 — Série é parcial; empurrão é tudo ou nada

**Enunciado.** Criar uma recorrência marca as ocorrências que couberem e devolve as que não
couberam. Encerrar uma série, ao contrário, é atômico.

**Motivo.** Cada ocorrência de uma série é um **compromisso independente** que por acaso foi pedido
junto com os outros: a terceira quinta ser feriado é esperado, e recusar as outras onze por causa
dela seria absurdo. Já o empurrão (RN-AGD-021) é **um ato só** — o dia da profissional andou —, e
metade dele é pior do que nada.

O critério que separa os dois: *as partes são consequências de um mesmo fato, ou compromissos
separados?*

**Consequência técnica.** O parcial exige **uma transação por ocorrência**. Numa transação única, a
recusa de uma marcaria tudo como rollback-only — e a violação da exclusion constraint aborta a
transação no PostgreSQL, deixando os comandos seguintes sem efeito. O resultado seria "não marca
nada" com aparência de "marcou três". Por isso `CriarRecorrenciaUseCase` não é `@Transactional`, e
`EncerrarRecorrenciaUseCase` é.

**Onde é garantida.** Ausência deliberada de `@Transactional` em `CriarRecorrenciaUseCase` ·
`RegistroDaSerie` para as escritas que precisam de transação · `@Transactional` em
`EncerrarRecorrenciaUseCase`.
**Rotinas.** RT-AGD-009 · **Teste.** `RecorrenciaIT.parcial_e_o_certo_aqui`,
`RecorrenciaIT.recusa_nao_envenena_a_transacao`, `RecorrenciaIT.encerrar_cancela_o_futuro`
**Configurável?** Não · **Origem.** 2026-09-04

---

### RN-AGD-023 — A fila expira pela janela, não por limpeza

**Enunciado.** Uma espera vale para um intervalo de dias e uma faixa de hora opcional. A busca por
candidatos filtra por essa janela, então uma espera vencida deixa de ser oferecida sozinha — sem
job, sem limpeza. Ela continua visível na fila para quem quiser resolver.

**Motivo.** Uma fila que dependesse de um job para dar a resposta certa daria a resposta errada
exatamente quando o job falhasse. E ninguém percebe um job que não roda até alguém ser chamado para
um horário de duas semanas atrás.

**Corolário — a hora é relógio de parede.** A faixa é comparada em `LocalTime` no fuso do salão, não
em instante: "quinta à tarde" é uma frase sobre o relógio da parede, e comparar instantes faria a
faixa escorregar uma hora na virada do horário de verão. Por isso o banco filtra serviço,
profissional e dias, e o domínio decide a hora.

**Onde é garantida.** `FilaDeEsperaJdbc.candidatosA` (índice) · `EsperaNaFila.serve` (domínio).
**Rotinas.** RT-AGD-010 · **Teste.** `FilaDeEsperaIT.janela_vencida_some_sozinha`,
`EsperaNaFilaTest.hora_de_parede_e_nao_instante`, `EsperaNaFilaTest.faixa_de_hora`
**Configurável?** Por espera, é a própria janela · **Origem.** 2026-09-04

---

### RN-AGD-024 — O painel avisa que mudou; ele não manda o que mudou

**Enunciado.** O painel do balcão recebe, por SSE em processo, um aviso sem carga útil, e responde
pedindo o retrato inteiro de novo. O stream **não** passa pelo outbox.

**Motivo.** Um stream de deltas exige que o cliente aplique todos, na ordem, sem perder nenhum — e
quando ele perde um (reconexão, aba em segundo plano, rede oscilando) a tela fica errada em
silêncio, para sempre, numa parede que ninguém olha de perto. Pedir o retrato é barato e sempre
correto.

E não pelo outbox porque eventos duráveis são **reenviados**: um painel que replayasse os
cancelamentos de ontem ao reconectar mostraria a cliente errada. Perder um aviso enquanto está
desconectado é o comportamento certo aqui.

**Isolamento.** A conexão é registrada sob o estabelecimento de quem a abriu, fixado na abertura; o
evento carrega o seu. Sem o casamento, o painel de um salão receberia o movimento do outro — e
apareceria numa tela pendurada na parede.

**Onde é garantida.** `PainelDeEventos` (`@TransactionalEventListener`, mapa por tenant) ·
`PainelController` fixa o tenant na abertura.
**Rotinas.** RT-AGD-012 · **Teste.** `PainelDeEventosTest.tenant_separa_as_conexoes`,
`PainelDeEventosTest.painel_quebrado_sai_da_lista`
**Configurável?** Não · **Origem.** 2026-09-05
