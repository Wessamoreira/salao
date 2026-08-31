# Regras de negócio — módulo `equipe`

---

### RN-EQP-001 — Documento é validado por dígito verificador, e repetidos são recusados

**Enunciado.** CPF e CNPJ têm o dígito verificador conferido pelo módulo 11. Documento com todos os
dígitos iguais é recusado mesmo passando no cálculo. Guardado só com dígitos.

**Motivo.** Documento errado só aparece quando alguém precisa dele — emitir nota, pagar comissão,
responder a fiscalização —, e aí está no cadastro há meses sem ninguém lembrar do que digitou.
`111.111.111-11` passa no mod-11 e é o que se digita sem ter o documento em mãos; aceitá-lo enche o
cadastro de números que ninguém pode usar.

**Onde é garantida.** Domínio: `Documento.de`.
**Rotinas.** RT-EQP-001 · **Teste.** `DocumentoTest.recusa_digito_errado`,
`DocumentoTest.recusa_repetidos`
**Erro.** `ER-EQP-DOCUMENTO_INVALIDO` (422) · **Configurável?** Não
**Origem.** 2026-08-30

---

### RN-EQP-002 — O vínculo determina o tipo de documento

**Enunciado.** `PARCEIRO` exige CNPJ; `EMPREGADO` e `AUTONOMO` exigem CPF. Vale no caso de uso e
como `check constraint` no banco.

**Motivo.** A Lei 13.352/2016 exige que o profissional-parceiro seja pessoa jurídica. Um "parceiro"
com CPF descreve exatamente o vínculo empregatício disfarçado que a lei existe para evitar — e que
uma fiscalização procura. A restrição no banco cobre o dia em que alguém inserir por script ou
correção manual, que é onde a regra costuma ser burlada sem ninguém perceber.

**Onde é garantida.** `CadastrarProfissionalUseCase.exigirDocumentoCompativel` · Banco:
`profissional_documento_do_vinculo` (V17).
**Rotinas.** RT-EQP-001 · **Teste.** `CadastroDeProfissionalIT.parceiro_exige_cnpj`,
`CadastroDeProfissionalIT.banco_recusa_vinculo_incoerente`
**Erro.** `ER-EQP-DOCUMENTO_NAO_COMBINA_COM_VINCULO` (422) · **Configurável?** Não
**Origem.** 2026-08-30

---

### RN-EQP-003 — Parceria sem contrato escrito é registrada, não bloqueada

**Enunciado.** O cadastro de `PARCEIRO` sem `contrato_assinado_em` é aceito, e a ficha passa a
responder `contratoPendente() == true`.

**Motivo.** A parceria sem contrato escrito pode ser reclassificada como emprego, com encargos
retroativos — é o maior risco do modelo. Mas bloquear o cadastro faria o salão não cadastrar o
profissional, e aí perde-se o profissional **e** continua-se sem o contrato. Registrar a ausência é
o que permite avisar. Mesmo raciocínio de RN-CLI-016 e RN-CLI-005.

**Onde é garantida.** `ProfissionalResumo.contratoPendente` · coluna anulável (V17).
**Rotinas.** RT-EQP-001 · **Teste.**
`CadastroDeProfissionalIT.parceiro_sem_contrato_avisa_e_nao_bloqueia`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-EQP-004 — Documento é único por salão, e só por salão

**Enunciado.** `(estabelecimento_id, documento)` é único. O mesmo documento pode existir em salões
diferentes.

**Motivo.** É o oposto de RN-CLI-005, e por um motivo concreto: duas pessoas não compartilham CPF, e
duas fichas com o mesmo CNPJ dividiriam a comissão de um só em dois extratos. Já profissional que
atende em dois salões é comum, e cada salão tem o próprio cadastro — a unicidade global recusaria o
segundo.

**Onde é garantida.** Banco: `idx_profissional_documento` (V17).
**Rotinas.** RT-EQP-001 · **Teste.** `CadastroDeProfissionalIT.documento_e_unico_no_salao`,
`CadastroDeProfissionalIT.documento_repetido_entre_saloes`
**Erro.** `ER-EQP-DOCUMENTO_JA_CADASTRADO` (409) · **Configurável?** Não
**Origem.** 2026-08-30

---

### RN-EQP-005 — Profissional é desativado, nunca apagado

**Enunciado.** A ficha permanece; `ativo` passa a falso. Some da lista de quem pode atender e
continua visível para quem administra. Repetir a operação não é erro.

**Motivo.** O histórico aponta para quem executou cada serviço e cada comanda. Apagar quebraria o
extrato de comissão, inclusive o de meses já pagos — que é exatamente o que alguém vai querer
conferir quando houver dúvida. Mesma decisão de RT-IAM-007 para usuários.

**Onde é garantida.** `DefinirAtivacaoDoProfissionalUseCase` · `ProfissionaisJdbc.ativos`.
**Rotinas.** RT-EQP-001 · **Teste.** `CadastroDeProfissionalIT.desativar_mantem_a_ficha`,
`CadastroDeProfissionalIT.desativar_e_idempotente`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-EQP-006 — Jornada é uma lista de intervalos por dia; almoço é ausência de intervalo

**Enunciado.** Cada dia da semana tem zero ou mais intervalos de trabalho. Não existe campo de
almoço, de pausa ou de turno.

**Motivo.** O modelo `inicio, fim, almoco_inicio, almoco_fim` quebra na primeira profissional com
três blocos, com dois intervalos, ou que trabalha só de manhã num dia — e a saída de sempre é
acrescentar `almoco2_inicio`, que transforma o modelo em folclore. Tratando o almoço como a
ausência de intervalo, "9h-13h e 14h-19h" é o caso normal e não uma exceção nomeada.

**Onde é garantida.** Uma linha por intervalo em `jornada_profissional` (V18) ·
`JornadaSemanal` / `IntervaloDeTrabalho`.
**Rotinas.** RT-EQP-002 · **Teste.** `JornadaSemanalTest.almoco_e_ausencia_de_intervalo`,
`JornadaSemanalTest.tres_blocos_cabem`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-EQP-007 — Intervalos do mesmo dia não se sobrepõem, e o banco é quem garante

**Enunciado.** Dois intervalos do mesmo profissional no mesmo dia não podem se cruzar. Encostar
não é sobrepor: 13h fecha um e abre o outro.

**Motivo.** Duas instâncias salvando ao mesmo tempo passam por qualquer verificação em Java — as
duas leem, nenhuma vê conflito, as duas gravam. O `EXCLUDE` do Postgres não tem esse buraco
(inviolável nº 8). A verificação no caso de uso existe para a mensagem dizer **quais** horários
colidem, que é o que a tela precisa destacar; um erro de constraint diria só que algo colidiu.

**Onde é garantida.** Banco: `jornada_sem_sobreposicao` (`EXCLUDE USING gist`, V18) ·
`DefinirJornadaUseCase.exigirSemSobreposicao` para a mensagem.
**Rotinas.** RT-EQP-002 · **Teste.** `JornadaIT.banco_impede_sobreposicao`,
`JornadaIT.encostar_nao_e_sobrepor`
**Erro.** `ER-EQP-JORNADA_SOBREPOSTA` (422) · **Configurável?** Não
**Origem.** 2026-08-30

---

### RN-EQP-008 — Sem jornada cadastrada, o profissional não atende

**Enunciado.** Jornada vazia significa indisponível, nunca disponibilidade total.

**Motivo.** O padrão precisa ser o que não marca horário para quem não trabalha. Assumir
disponibilidade na ausência de dado encheria a agenda de horários que ninguém vai cumprir, e o erro
só apareceria com a cliente na porta.

**Onde é garantida.** `JornadaSemanal.trabalhaEm` sobre mapa vazio · `ProfissionalApi.jornadaDe`
devolve `JornadaSemanal.vazia()`.
**Rotinas.** RT-EQP-002, RT-AGD-001 · **Teste.** `JornadaIT.sem_jornada_e_vazia`
**Configurável?** Não
**Origem.** 2026-08-30
