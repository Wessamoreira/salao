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

---

### RN-CAT-009 — Recurso é contável, e a contagem nunca é zero

**Enunciado.** Cada recurso tem `quantidade` de unidades intercambiáveis, sempre ≥ 1. Um recurso
que o salão não tem mais é **inativo**, não é quantidade zero.

**Motivo.** O salão tem "três lavatórios", não "Lavatório A, B e C" — nomear as unidades criaria
uma escolha que a recepção não sabe fazer e um dado que ninguém consulta. E zero unidades diria a
mesma coisa que `ativo = false`: duas formas de dizer a mesma coisa é o começo de um relatório que
não fecha, exatamente como em RN-CAT-003.

**Onde é garantida.** `check (quantidade between 1 and 99)` (V22) · construtor de `Recurso`.
**Rotinas.** RT-CAT-003 · **Teste.** `RecursoTest.quantidade_zero_nao_existe`,
`RecursosDoSalaoIT.quantidade_zero_e_422`, `RecursosDoSalaoIT.edicao_para_zero_e_422`
**Erro.** `ER-CAT-DADOS_INVALIDOS` (422) · **Configurável?** A quantidade, por recurso
**Origem.** 2026-09-01

---

### RN-CAT-010 — O recurso é exigido pelo bloco, não pelo serviço

**Enunciado.** A exigência de recurso é gravada por bloco. Um bloco `PAUSA` pode exigir recurso.

**Motivo.** A coloração ocupa o lavatório só na lavagem — 30 de 100 minutos. Pendurar a exigência
no serviço reservaria o equipamento pelos 100, que é o mesmo desperdício que os blocos ATIVO/PAUSA
existem para eliminar, movido para a outra coisa escassa. E o inverso importa tanto quanto: durante
os 40 minutos da química a profissional está livre e a **cadeira não** — um modelo que não saiba
dizer isso vende a mesma cadeira duas vezes.

**Onde é garantida.** Tabela `bloco_exige_recurso`, com chave em `bloco_id` (V22) ·
`BlocoDoServico.recursosExigidos`. A gravação acontece dentro de `ServicosJdbc.gravarBlocos`, junto
com os blocos: numa tabela escrita à parte, o "apaga e regrava" da receita destruiria as exigências
em silêncio a cada edição do serviço.
**Rotinas.** RT-CAT-003, RT-AGD-001 · **Teste.** `SequenciaDeBlocosTest.recurso_mora_no_bloco`,
`SequenciaDeBlocosTest.pausa_tambem_ocupa`, `RecursosDoSalaoIT.exigencia_mora_no_bloco`,
`RecursosDoSalaoIT.pausa_tambem_ocupa`
**Configurável?** Por serviço, é a própria receita
**Origem.** 2026-09-01

---

### RN-CAT-011 — Recurso exigido é conferido pelo caso de uso, porque a chave estrangeira não separa salões

**Enunciado.** Antes de gravar a receita, o caso de uso confere que todo recurso exigido existe,
está ativo e pertence a este salão — com uma consulta comum, sujeita à política de tenant.

**Motivo.** No PostgreSQL as checagens de integridade referencial rodam como dono da tabela e
**não passam pela RLS**. A chave estrangeira de `bloco_exige_recurso` aceita, sem reclamar, um
recurso de outro salão. É contraintuitivo o bastante para merecer um teste que demonstre o
comportamento, e não só um que teste a defesa.

Vale para **toda** FK entre tabelas de negócio, não só esta — ver a quinta armadilha em
[05-seguranca-multitenancy-lgpd.md](../../05-seguranca-multitenancy-lgpd.md).

**Onde é garantida.** `CadastrarServicoUseCase.conferirRecursos` · `RecursosJdbc.idsAtivos`.
**Rotinas.** RT-CAT-003 · **Teste.** `RecursosDoSalaoIT.recurso_de_outro_salao_nao_serve`,
`RecursosDoSalaoIT.a_fk_sozinha_nao_e_barreira_de_tenant` (documenta a armadilha),
`RecursosDoSalaoIT.recurso_inativo_nao_serve`
**Erro.** `ER-CAT-RECURSO_NAO_ENCONTRADO` (422) · **Configurável?** Não
**Origem.** 2026-09-01

---

### RN-CAT-012 — Não se desativa recurso que uma receita ativa exige

**Enunciado.** Desativar um recurso é recusado enquanto algum serviço **ativo** o exigir, e o erro
lista os serviços. Diminuir a quantidade é sempre permitido.

**Motivo.** Se passasse, todo serviço que depende dele ficaria impossível de marcar: a agenda
procuraria um lavatório livre, não acharia nenhum, e deixaria de oferecer horário — sem erro, sem
aviso, e sem ninguém conseguindo explicar por que a coloração sumiu da agenda. Listar os serviços é
a diferença entre "não pode" e "não pode; conserte estes três".

Só os ativos contam: serviço fora do cardápio não pode ser marcado, e a ordem "tira o serviço,
depois o equipamento" é a ordem em que as coisas acontecem na vida.

É a mesma família de RN-CAT-008 e RT-EQP-003 — *apaga-se, ou aposenta-se, o que não sustenta nada*.

**Onde é garantida.** `DefinirAtivacaoDoRecursoUseCase` · `RecursosJdbc.servicosAtivosQueExigem`.
**Rotinas.** RT-CAT-003 · **Teste.** `RecursosDoSalaoIT.nao_desativa_recurso_em_uso`,
`RecursosDoSalaoIT.desativa_quando_ninguem_mais_exige`
**Erro.** `ER-CAT-RECURSO_EM_USO` (422) · **Configurável?** Não
**Origem.** 2026-09-01

---

### RN-CAT-013 — A duração por profissional encolhe o trabalho, nunca a pausa

**Enunciado.** Um profissional pode ter duração própria num serviço, em minutos **totais**. Ao
reescalar, os blocos `PAUSA` ficam intactos e só os `ATIVO` mudam, na proporção original. Duração
que não deixe ao menos um minuto por bloco ativo depois das pausas é recusada.

**Motivo.** Se a Ana faz coloração em 90 minutos em vez de 100, ela é mais rápida **aplicando e
finalizando**. A química continua levando 40 minutos, porque é reação e não habilidade. Encolher a
pausa junto mandaria lavar o cabelo antes da hora, e quem paga o resultado é a cliente.

Pelo mesmo motivo há um piso: dizer que alguém faz a coloração em 40 minutos é dizer que ela
aplica, espera a química agir e finaliza no tempo em que só a química acontece.

A distribuição é determinística — um minuto de base por bloco ativo, o resto proporcional, e os
minutos do arredondamento para as maiores frações. Sem regra fixa, a mesma entrada produziria
receitas diferentes conforme a ordem de iteração, e a agenda mostraria horários que mudam sozinhos.

**Onde é garantida.** `SequenciaDeBlocos.comDuracaoTotal` ·
`DefinirHabilidadesUseCase.conferirQueAsDuracoesCabem` traduz.
**Rotinas.** RT-CAT-004, RT-AGD-001 · **Teste.** `SequenciaDeBlocosTest.pausa_nao_encolhe`,
`SequenciaDeBlocosTest.duracao_menor_que_a_pausa`, `SequenciaDeBlocosTest.bloco_ativo_nunca_some`,
`DuracaoPorProfissionalIT.a_pausa_nao_encolhe`
**Erro.** `ER-EQP-DURACAO_NAO_CABE` (422) · **Configurável?** Por profissional e serviço
**Origem.** 2026-09-04

---

### RN-CAT-014 — Duração própria vive na habilidade, e a agenda pergunta uma vez só

**Enunciado.** A duração é coluna de `habilidade`, e só existe para serviço que o profissional
executa. Quem precisa da receita de alguém usa `ProfissionalApi.receitaDe`, que devolve os blocos já
reescalados — ou vazio, se a pessoa não executa o serviço.

**Motivo.** Duas razões, uma conceitual e uma mecânica.

Conceitual: duração e habilidade são a mesma relação (profissional × serviço). Tabelas separadas
permitiriam "a Ana leva 30 minutos numa coloração que ela não faz".

Mecânica: `substituirHabilidades` apaga e regrava as linhas a cada edição. Numa tabela irmã, toda
edição de habilidade apagaria as durações **em silêncio** — o mesmo defeito que RN-CAT-010 evitou
gravando o recurso exigido junto com o bloco. É a segunda aparição da mesma lição.

E `receitaDe` é uma pergunta só porque duas seriam um convite: quem esquecesse de compor usaria a
duração do catálogo para alguém que leva quinze minutos a mais, e nada acusaria — a agenda apenas
ficaria errada.

**Onde é garantida.** Coluna `habilidade.duracao_minutos` (V24) · invariante do construtor de
`Habilidades` · `ProfissionaisJdbc.receitaDe`.
**Rotinas.** RT-CAT-004, RT-AGD-001, RT-AGD-002 · **Teste.**
`HabilidadesTest.duracao_exige_habilidade`, `DuracaoPorProfissionalIT.duracao_sobrevive_a_edicao`,
`DuracaoPorProfissionalIT.sem_habilidade_sem_receita`
**Erro.** `ER-EQP-DURACAO_SEM_HABILIDADE` (422) · **Configurável?** Não
**Origem.** 2026-09-04
