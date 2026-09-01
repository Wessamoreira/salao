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
