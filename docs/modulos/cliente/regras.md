# Regras de negócio — módulo `cliente`

---

### RN-CLI-001 — Telefone é gravado em E.164, só dígitos

**Enunciado.** Todo telefone é normalizado para E.164 sem `+` (ex.: `5511987654321`) antes de ser
gravado. A entrada aceita máscara, espaço, ponto, traço, prefixo `+55` e prefixo `0055`.

**Motivo.** O mesmo número chega de sete formas diferentes, porque quem digita está com o telefone
na orelha e a cliente na frente. Se cada forma virar uma linha, a busca por telefone (R-UX-03)
deixa de funcionar exatamente para quem mais precisa dela. Gravar formatado faria a busca por
sufixo depender da máscara que cada pessoa escolheu.

**Onde é garantida.** Domínio: `Telefone.de`.
**Rotinas.** RT-CLI-001 · **Teste.** `TelefoneTest.normaliza_todas_as_formas`
**Erro.** `ER-CLI-TELEFONE_INVALIDO` (422) · **Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-002 — Telefone sem DDD é completado com o do estabelecimento

**Enunciado.** Número com 8 ou 9 dígitos recebe o `ddd_padrao` do estabelecimento. Um DDD já
informado é respeitado, nunca sobrescrito. Sem DDD padrão configurado, o cadastro é **recusado**.

**Motivo.** Quem atende anota `98765-4321`: o DDD é óbvio para quem está ali, e exigi-lo é fricção
que faz a recepção inventar dado. Sobrescrever um DDD informado quebraria a cliente de outro
estado, que existe. E chutar um DDD quando não há padrão daria um número que parece certo e não
completa a chamada — recusar é honesto.

**Onde é garantida.** Domínio: `Telefone.de` · Configuração: `estabelecimento.ddd_padrao` (V12).
**Rotinas.** RT-CLI-001 · **Teste.** `TelefoneTest.completa_o_ddd`,
`CadastroDeClienteIT.sem_ddd_configurado_recusa`
**Erro.** `ER-CLI-TELEFONE_INVALIDO` (422) · **Configurável?** Sim, por estabelecimento
**Origem.** 2026-08-30

---

### RN-CLI-003 — O nono dígito nunca é acrescentado automaticamente

**Enunciado.** Número de 8 dígitos é aceito como fixo. O sistema não converte `3333-4444` em
`93333-4444`.

**Motivo.** Adivinhar transformaria um número certo em errado sem ninguém perceber, e o sintoma
apareceria só no dia em que alguém tentasse ligar para a cliente e não completasse. Um fixo de oito
dígitos é um telefone válido, e salão de bairro ainda tem cliente que só usa fixo.

**Onde é garantida.** Domínio: `Telefone.de`.
**Rotinas.** RT-CLI-001 · **Teste.** `TelefoneTest.nao_inventa_o_nono_digito`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-004 — Só o nome é obrigatório

**Enunciado.** Telefone, e-mail, apelido e observação são opcionais. Nome vazio ou só com espaços é
recusado, com o campo apontado no erro.

**Motivo.** O cadastro nasce no meio de um agendamento, com a cliente esperando. Exigir e-mail para
marcar um corte é a razão nº 1 de sistema de salão virar caderno na recepção. Exigir telefone faria
a recepção digitar `00000-0000`, que é pior que o vazio: parece dado e não é.

**Onde é garantida.** Caso de uso: `CadastrarClienteUseCase` · Banco: `cliente_nome_nao_vazio`.
**Rotinas.** RT-CLI-001 · **Teste.** `CadastroDeClienteIT.cliente_sem_telefone`,
`CadastroDeClienteIT.nome_vazio_recusado`
**Erro.** `ER-CLI-NOME_OBRIGATORIO` (422) · **Configurável?** Não
**Origem.** 2026-08-30 · **UX.** R-UX-04

---

### RN-CLI-005 — Dois clientes podem ter o mesmo telefone

**Enunciado.** Não existe restrição de unicidade sobre `telefone`. O índice é de busca, não de
unicidade.

**Motivo.** Mãe e filha compartilham o número o tempo todo num salão; marido e esposa também. Uma
`unique` recusaria cadastro legítimo e ensinaria a recepção a inventar um número falso para
contornar — pior que o duplicado que se queria evitar. Duplicado é problema de **detecção**
(RT-CLI-003), não de proibição.

**Onde é garantida.** Banco: `idx_cliente_telefone` é índice comum, não único (V12).
**Rotinas.** RT-CLI-001, RT-CLI-003 · **Teste.**
`CadastroDeClienteIT.telefone_repetido_e_permitido`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-006 — Nome tem espaços normalizados na gravação

**Enunciado.** Espaços das pontas são removidos e os do meio colapsados: `"Maria  Silva "` é
gravado como `"Maria Silva"`.

**Motivo.** Quem digita com a cliente na frente não olha para o espaço a mais. Guardar as duas
formas faria a busca (RT-CLI-002) e a detecção de duplicado (RT-CLI-003) tratarem a mesma pessoa
como duas.

**Onde é garantida.** Domínio: `Nomes.normalizar`.
**Rotinas.** RT-CLI-001 · **Teste.** `CadastroDeClienteIT.normaliza_espacos_do_nome`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-007 — Auditoria de cliente registra apenas o nome

**Enunciado.** A trilha de auditoria do cadastro grava o nome. Telefone, e-mail e observação não
entram.

**Motivo.** Auditoria é retida por prazo longo e consultada por muita gente. Copiar dado pessoal
para dentro dela criaria uma segunda base fora do alcance da anonimização da LGPD (RT-CLI-005), que
só sabe limpar a tabela `cliente` — e o titular que pedisse exclusão continuaria com o telefone
gravado num lugar que ninguém lembraria de olhar.

**Onde é garantida.** Caso de uso: `CadastrarClienteUseCase.resumoParaAuditoria`.
**Rotinas.** RT-CLI-001, RT-CLI-005 · **Teste.** — (coberto por revisão; ver pendência de
RT-CLI-005)
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-008 — Anonimizar preserva a linha

**Enunciado.** A exclusão de dados pessoais preenche `anonimizado_em` e limpa os campos pessoais;
a linha não é removida. Cliente anonimizado não aparece em consulta nem em busca.

**Motivo.** O histórico financeiro e a comissão do profissional apontam para o cliente. Apagar a
linha quebraria o rastro que sustenta o extrato de quem trabalhou — e o direito do titular é sobre
o dado pessoal, não sobre o registro contábil do salão.

**Onde é garantida.** Banco: coluna `anonimizado_em` (V12) · Consultas filtram
`anonimizado_em is null`.
**Rotinas.** RT-CLI-001 (estrutura), RT-CLI-005 (operação) · **Teste.** — (RT-CLI-005)
**Configurável?** Não
**Origem.** 2026-08-30
