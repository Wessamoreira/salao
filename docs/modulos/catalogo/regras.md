# Regras de negócio — módulo `catalogo`

---

### RN-CAT-001 — Serviço é uma sequência de blocos ATIVO e PAUSA

**Enunciado.** Cada serviço tem uma lista ordenada de blocos com tipo e duração. `ATIVO` ocupa o
profissional; `PAUSA` não — a cliente espera, e o profissional pode atender outra pessoa.

**Motivo.** É a peça que distingue um sistema de salão de uma agenda genérica. Coloração é aplicar
(30, ocupada), a química agir (40, **livre**) e finalizar (30, ocupada). Tratar isso como um bloco
de 100 minutos joga fora 40 minutos de trabalho por coloração — num sábado, o horário inteiro de
outra cliente.

**Onde é garantida.** Tabela `bloco_do_servico` com `ordem` e `tipo` (V20) ·
`SequenciaDeBlocos.minutosDoProfissional`.
**Rotinas.** RT-CAT-001, RT-AGD-001 · **Teste.**
`SequenciaDeBlocosTest.separa_tempo_da_cliente_do_tempo_do_profissional`,
`CatalogoDeServicosIT.coloracao_com_pausa`
**Configurável?** Por serviço, é a própria receita
**Origem.** 2026-08-30

---

### RN-CAT-002 — O serviço começa e termina com bloco ATIVO

**Enunciado.** O primeiro e o último bloco são sempre `ATIVO`.

**Motivo.** Começar em pausa faria a cliente chegar e esperar sem ninguém ter feito nada — não é
serviço, é atraso. Terminar em pausa encerraria o atendimento com a química agindo: a agenda
marcaria a próxima cliente e a primeira ficaria sentada com tinta no cabelo.

**Onde é garantida.** Domínio: construtor de `SequenciaDeBlocos`.
**Rotinas.** RT-CAT-001 · **Teste.** `SequenciaDeBlocosTest.nao_termina_em_pausa`
**Erro.** `ER-CAT-SEQUENCIA_INVALIDA` (422) · **Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CAT-003 — Blocos do mesmo tipo não se sucedem

**Enunciado.** `ATIVO` e `PAUSA` alternam. Dois blocos do mesmo tipo em sequência são recusados.

**Motivo.** Dois `ATIVO` seguidos são um `ATIVO` com a soma das durações. Permitir as duas formas
deixaria a mesma receita ser escrita de várias maneiras — e duas fichas do mesmo serviço com blocos
diferentes é o começo de um relatório que não fecha.

**Onde é garantida.** Domínio: construtor de `SequenciaDeBlocos`.
**Rotinas.** RT-CAT-001 · **Teste.** `SequenciaDeBlocosTest.nao_repete_o_tipo`
**Erro.** `ER-CAT-SEQUENCIA_INVALIDA` (422) · **Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CAT-004 — Alterar o cardápio é do dono; ler é de quem marca

**Enunciado.** Cadastrar, alterar e desativar serviço exigem `config:manage`. Listar exige
`agenda:read:*` ou `config:manage`.

**Motivo.** Mudar a duração de um serviço muda a agenda de todo mundo — o encaixe, a
disponibilidade, o horário já prometido à cliente. Não é operação de balcão. Mas a recepção precisa
ver o cardápio para marcar, e esconder isso impediria o trabalho.

**Onde é garantida.** `@PreAuthorize` nos casos de uso do módulo.
**Rotinas.** RT-CAT-001 · **Teste.** `CatalogoDeServicosIT.recepcao_le_mas_nao_altera`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CAT-005 — Preço tem data de início e nunca data de fim

**Enunciado.** Cada preço é uma linha com `vigente_de`. O preço numa data é o de maior `vigente_de`
que já começou. Duas linhas com a mesma data são recusadas.

**Motivo.** O modelo com `vigente_ate` obriga a manter duas linhas coerentes a cada alteração: uma
falha no meio disso deixa dois preços vigentes ou nenhum, e a consulta passa a depender de qual
linha o banco devolve primeiro. Sem data de fim, a coerência é consequência da consulta e não de
disciplina de escrita — e preço futuro funciona sem tratamento especial.

**Onde é garantida.** Tabela `preco_do_servico` sem `vigente_ate`, com
`unique (servico_id, vigente_de)` (V21) · `ServicosJdbc.precoEm`.
**Rotinas.** RT-CAT-002 · **Teste.** `PrecoDoServicoIT.aumento_nao_reescreve_o_passado`,
`PrecoDoServicoIT.mesma_data_e_ambiguidade`
**Erro.** `ER-CAT-DADOS_INVALIDOS` (422) · **Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CAT-006 — O atendimento guarda o preço que usou

**Enunciado.** Ao registrar um serviço numa comanda, `atendimento` grava o valor **no item**, e o
cálculo lê dali — nunca da tabela de preços.

**Motivo.** Sem essa cópia, qualquer alteração de preço alcança o passado: um preço retroativo, ou
mesmo uma correção legítima, muda o que um atendimento de dois meses atrás custou, e a comissão já
paga deixa de bater. O preço com vigência protege a *leitura por data*; só a cópia protege o
registro do que de fato foi cobrado.

**Obrigação da Fase 2**, registrada aqui porque o módulo ainda não existe. Enquanto ela não existir,
a marcação de retroativo na trilha (RN-CAT-008) é a única defesa.

**Onde é garantida.** Hoje: em lugar nenhum — é uma promessa. Na Fase 2: coluna de valor no item da
comanda (`RT-ATD-*`).
**Rotinas.** RT-CAT-002, RT-ATD-* · **Teste.** — (Fase 2)
**Configurável?** Não · **Ver também.** RN-IAM-017, que tem a mesma forma para a base de comissão
**Origem.** 2026-08-30

---

### RN-CAT-007 — Serviço sem preço devolve vazio, nunca zero

**Enunciado.** `precoEm` devolve `Optional.empty()` quando não há preço definido até a data.

**Motivo.** Zero é um preço. Um serviço sem preço num relatório financeiro precisa aparecer como
pendência de cadastro, não como brinde — devolver zero faria a soma fechar com o número errado, que
é pior do que não fechar. Pelo mesmo motivo a consulta sempre exige a data: pedir "o preço" sem
dizer quando é a pergunta que produz o relatório errado.

**Onde é garantida.** `ServicoApi.precoEm` devolve `Optional<Money>`.
**Rotinas.** RT-CAT-002 · **Teste.** `PrecoDoServicoIT.sem_preco_e_vazio`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CAT-008 — Preço retroativo é permitido e marcado; preço vigente não se apaga

**Enunciado.** Definir preço com `vigente_de` no passado é aceito e registrado na trilha com
`retroativo: true`. Remover só é possível enquanto o preço não começou a valer.

**Motivo.** Recusar retroativo obrigaria o salão a conviver com um erro de digitação. Mas apagar um
preço já vigente faria o serviço voltar ao valor anterior retroativamente, e o relatório do mês
passado mudaria sozinho — quem errou corrige cadastrando outro, o que deixa rastro. É a mesma
distinção de RT-EQP-003: apaga-se o que não sustenta nada.

**Onde é garantida.** `DefinirPrecoUseCase.trilha` marca retroativo ·
`ServicosJdbc.removerPrecoFuturo` filtra por `vigente_de > hoje`.
**Rotinas.** RT-CAT-002 · **Teste.** `PrecoDoServicoIT.retroativo_e_marcado`,
`PrecoDoServicoIT.nao_apaga_preco_vigente`
**Erro.** `ER-CAT-PRECO_JA_VIGENTE` (422) · **Configurável?** Não
**Origem.** 2026-08-30
