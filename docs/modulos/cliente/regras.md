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

---

### RN-CLI-009 — Dígito no termo significa busca por telefone

**Enunciado.** Um único campo atende à busca. Se o termo digitado contém qualquer dígito, é busca
por telefone (pelos dígitos, sufixo); caso contrário, é busca por nome e apelido.

**Motivo.** O caso mais comum do balcão é o telefone tocando, e os quatro últimos dígitos resolvem
onde o nome falha (oito Marias, apelidos, grafia). Mas obrigar a pessoa a escolher num seletor
entre "por nome" e "por telefone" é pedir uma decisão a quem está com o telefone na outra mão.
Nome não tem dígito — a regra não tem exceção prática.

**Onde é garantida.** Domínio: `TermoDeBusca.de`.
**Rotinas.** RT-CLI-002 · **Teste.** `TermoDeBuscaTest.digito_significa_telefone`
**Configurável?** Não · **UX.** R-UX-03
**Origem.** 2026-08-30

---

### RN-CLI-010 — Termo curto devolve lista vazia, nunca erro

**Enunciado.** Menos de 2 letras (nome) ou 3 dígitos (telefone) devolve lista vazia com HTTP 200.

**Motivo.** É o estado normal enquanto alguém digita a primeira letra. Um 422 por tecla faria a
tela piscar um erro durante a digitação inteira. O limite por tipo é diferente porque dois dígitos
casariam com metade do salão, enquanto duas letras já estreitam bastante.

**Onde é garantida.** Domínio: `TermoDeBusca.Insuficiente` · Caso de uso: `BuscarClientesUseCase`.
**Rotinas.** RT-CLI-002 · **Teste.** `TermoDeBuscaTest.curto_demais_nao_e_erro`,
`ClientesWebIT.termo_curto_devolve_vazio`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-011 — A normalização da busca existe em um único lugar

**Enunciado.** A coluna `busca` é gerada pelo banco (minúsculas, sem acento) e o termo digitado
passa pela mesma função `imutavel_unaccent(lower(...))` na consulta.

**Motivo.** A normalização do que está guardado e a do que foi digitado precisam ser idênticas.
Mantê-las em dois lugares — uma em Java, outra em SQL — garante que um dia divirjam, e no dia em
que divergirem a busca para de achar gente **sem erro nenhum**: silencioso e plausível, o pior tipo
de defeito. Foi por isso que normalizar em Java foi recusado, mesmo sendo mais testável em unidade.

**Onde é garantida.** Banco: coluna gerada + `imutavel_unaccent` (V13).
**Rotinas.** RT-CLI-002 · **Teste.** `BuscaIndexadaIT.busca_e_coluna_gerada`,
`BuscaDeClienteIT.busca_ignora_acento`
**Configurável?** Não · **Runbook.** [reindexar-busca](../../runbook/reindexar-busca.md)
**Origem.** 2026-08-30

---

### RN-CLI-012 — Curinga de `LIKE` é neutralizado no termo

**Enunciado.** `%`, `_` e `\` são escapados antes de compor o padrão da busca.

**Motivo.** Não é injeção — o valor viaja como parâmetro — é resultado errado: quem digitasse `%`
veria o salão inteiro, e `_` casaria com qualquer letra. É o tipo de falha que ninguém reporta e
todo mundo acaba usando.

**Onde é garantida.** Infra: `ClientesJdbc.comoPadrao` (fica ali, e não no domínio, porque `LIKE`
é detalhe de como este repositório consulta).
**Rotinas.** RT-CLI-002 · **Teste.** `BuscaDeClienteIT.curinga_nao_lista_todo_mundo`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-013 — Duplicado é decidido pelo primeiro nome, não pelo escore de semelhança

**Enunciado.** Três graus: `PROVAVEL` (mesmo telefone e mesmo primeiro nome), `POSSIVEL` (sem
telefone, mesmo primeiro nome e semelhança ≥ 0,5) e `MESMO_TELEFONE` (mesmo telefone, primeiro nome
diferente). O último **não é aviso de duplicidade**: é contexto de família. A inicial vale como
primeiro nome, com no máximo dois caracteres.

**Motivo.** Medido no Postgres com pares reais: "maria silva"×"mariana silva" dá 0,733 (pessoas
diferentes) e "jose antonio"×"jose antonio da costa" dá 0,591 (mesma pessoa). Nenhum limiar separa
as duas linhas. Mãe e filha compartilham o sobrenome; a mesma pessoa cadastrada duas vezes
compartilha o primeiro nome — é isso que distingue "Maria"/"Júlia" (0,412) de "Ana"/"Ana Paula"
(0,400). O limite de dois caracteres na inicial evita casar "Luís" com "Luísa", que num salão é
com frequência pai e filha.

**Onde é garantida.** Domínio: `Semelhanca.avaliar`.
**Rotinas.** RT-CLI-003 · **Teste.** `SemelhancaTest.mariana_nao_e_maria`,
`SemelhancaTest.mae_e_filha_nao_sao_duplicado`
**Configurável?** Não · **UX.** R-UX-15
**Origem.** 2026-08-30

---

### RN-CLI-014 — Mesclar redireciona; a ficha absorvida não é apagada

**Enunciado.** A ficha absorvida recebe `mesclado_para` e `mesclado_em`, some da busca e continua
resolvendo para a sobrevivente em qualquer leitura por id. As cadeias são mantidas com um salto só.

**Motivo.** Tudo que já guardou o `cliente_id` — agendamento, comanda, comissão — continua achando
a pessoa certa sem ser reescrito. Apagar exigiria repontar todas as referências numa transação só,
e a esquecida viraria registro órfão. O redirecionamento falha do lado seguro. Manter a cadeia
plana evita leitura recursiva, que é onde um ciclo acidental vira laço infinito em produção.

**Onde é garantida.** Banco: `mesclado_para` + checks (V14) · Infra: `ClientesJdbc.redirecionar`
reponta quem apontava para a absorvida.
**Rotinas.** RT-CLI-003 · **Teste.** `DuplicadosEMesclagemIT.absorvida_redireciona`,
`DuplicadosEMesclagemIT.cadeia_fica_plana`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-015 — A mesclagem completa vazios e nunca sobrescreve

**Enunciado.** Campos preenchidos na ficha sobrevivente são preservados; os vazios recebem o valor
da absorvida. A observação é a exceção: as duas são concatenadas.

**Motivo.** O dado que já está lá foi conferido por alguém. Bom substituído por bom não melhora
nada; bom substituído por velho é perda silenciosa. A observação é concatenada porque texto livre
costuma guardar a única anotação existente sobre a pessoa ("alérgica a amônia"), e descartá-la
seria perder informação que ninguém saberia ter existido.

**Onde é garantida.** Infra: `ClientesJdbc.absorverCampos`.
**Rotinas.** RT-CLI-003 · **Teste.** `DuplicadosEMesclagemIT.mesclagem_completa_vazios`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-016 — A detecção de duplicado avisa, nunca impede

**Enunciado.** A detecção vive num endpoint separado, consultado enquanto se digita. O cadastro
nunca é recusado por semelhança com uma ficha existente.

**Motivo.** É a mesma razão de não haver `unique` em telefone (RN-CLI-005): recusar cadastro
legítimo ensina a recepção a contornar, e o contorno é pior — "Maria 2", telefone inventado,
sobrenome trocado. Embutir a checagem no `POST` obrigaria a escolher entre bloquear e ignorar;
consultando antes, quem decide é quem está olhando para a cliente.

**Onde é garantida.** `DetectarDuplicadosUseCase`, separado de `CadastrarClienteUseCase`.
**Rotinas.** RT-CLI-003 · **Teste.** `ClientesWebIT.possiveis_duplicados_trazem_o_grau`
**Configurável?** Não · **UX.** R-UX-04, R-UX-15
**Origem.** 2026-08-30

---

### RN-CLI-017 — Ficha anonimizada não participa de mesclagem

**Enunciado.** Nem como sobrevivente, nem como absorvida.

**Motivo.** Mesclar copia campos entre as fichas. Uma ficha anonimizada receberia de volta dados
que o titular pediu para apagar — desfazendo a anonimização por um caminho lateral, sem que
ninguém percebesse.

**Onde é garantida.** Caso de uso: `MesclarClientesUseCase.ficha`.
**Rotinas.** RT-CLI-003, RT-CLI-005 · **Teste.** — (completo em RT-CLI-005, que cria o estado)
**Erro.** `ER-CLI-MESCLAGEM_INVALIDA` (422) · **Configurável?** Não
**Origem.** 2026-08-30
