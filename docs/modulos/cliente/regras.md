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

### RN-CLI-007 — A auditoria de cliente não registra dado pessoal nenhum

**Enunciado.** A trilha do cadastro grava apenas o `entidade_id`. Nome, apelido, telefone, e-mail
e observação não entram.

**Motivo.** Auditoria é retida por prazo longo, consultada por muita gente e **a aplicação não
pode alterá-la** (append-only por permissão). Qualquer dado pessoal ali é uma segunda base fora do
alcance da anonimização (RT-CLI-005), que só sabe limpar a tabela `cliente` — o titular pediria
exclusão e continuaria nomeado num lugar que ninguém lembraria de olhar. E `entidade_id` já
responde de quem se trata: a trilha existe para dizer *quem fez o quê*, não para descrever a
pessoa.

**Corrigida em 30/08/2026, e vale registrar o erro.** A primeira versão desta regra proibia
telefone e e-mail com este mesmo argumento e **deixava o nome passar** — a mesma falha, com a
mesma razão contra, aplicada pela metade. Só apareceu ao escrever RT-CLI-005, quando ficou
impossível chamar de anonimizada uma ficha que a auditoria sabia nomear. As linhas já gravadas
foram redigidas em V16.

**Onde é garantida.** Caso de uso: `CadastrarClienteUseCase` registra `Map.of()`.
**Rotinas.** RT-CLI-001, RT-CLI-005 · **Teste.** `AnonimizacaoIT.trilha_nao_guarda_nome`
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

---

### RN-CLI-018 — A base legal é da finalidade, e define o padrão sem registro

**Enunciado.** Cada finalidade carrega sua base legal. `LEMBRETE_DE_AGENDAMENTO` é execução de
contrato e o padrão é **pode**; `MARKETING` e `PESQUISA_DE_SATISFACAO` exigem consentimento e o
padrão é **não pode**. "Sem registro" é um estado próprio, distinto de "recusado".

**Motivo.** Atender a cliente não depende de consentimento, depende de contrato (art. 7, V): pedir
consentimento para avisar sobre um horário que ela mesma marcou sugeriria que ela pode recusar e
ser atendida igual, e criaria uma revogação que o salão não poderia honrar. Guardar a base legal
por linha permitiria que duas linhas discordassem sobre um fato que é o mesmo para todo mundo. E
tratar "sem registro" como "recusado" faria o lembrete parar de sair para toda cliente nunca
perguntada — que são todas, no primeiro dia.

**Onde é garantida.** Domínio: `Finalidade` e `BaseLegal` · Sem coluna `base_legal` no banco (V15).
**Rotinas.** RT-CLI-004 · **Teste.** `ConsentimentoIT.padrao_depende_da_base_legal`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-019 — Revogar usa exatamente o mesmo caminho que consentir

**Enunciado.** Conceder e revogar são a mesma rota, o mesmo caso de uso e a mesma permissão,
mudando só o campo `concedido`.

**Motivo.** A LGPD exige que revogar seja tão fácil quanto consentir (art. 8 §5). A forma confiável
de cumprir isso não é lembrar de facilitar o "não": é não ter dois caminhos. Um endpoint separado
para revogação seria inevitavelmente o mais estreito dos dois — menos usado, menos testado, e o
primeiro a quebrar sem ninguém perceber.

**Onde é garantida.** `RegistrarConsentimentoUseCase`, uma operação só ·
`PUT /clientes/{id}/consentimentos/{finalidade}`.
**Rotinas.** RT-CLI-004 · **Teste.** `ConsentimentoIT.consentir_e_revogar`,
`ClientesWebIT.consentimento_pelo_http`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-020 — O histórico de consentimento é append-only

**Enunciado.** Cada decisão é uma linha nova; nada é alterado ou removido. A decisão vigente é a
mais recente por finalidade.

**Motivo.** A pergunta a responder não é "o que vale hoje", é "o que valia no dia em que a mensagem
foi enviada?". Uma linha editável só sabe responder a primeira — a resposta inútil num
questionamento. Como na auditoria, a garantia é permissão de banco e não convenção: convenção
depende de ninguém escrever o `UPDATE`.

**Onde é garantida.** Banco: `revoke update, delete on cliente_consentimento from salao_app` (V15).
**Rotinas.** RT-CLI-004 · **Teste.** `ConsentimentoIT.historico_e_append_only`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-021 — A mesclagem não ressuscita consentimento revogado

**Enunciado.** A decisão vigente considera também as fichas absorvidas pela sobrevivente; vence a
mais recente, venha de qual ficha vier.

**Motivo.** Se a pessoa revogou numa ficha e a outra ainda dizia "sim", mesclar não pode fazer o
salão voltar a mandar promoção. É a única leitura defensável — e sai de graça porque RT-CLI-003
mantém as cadeias de mesclagem com um salto só, então a mesclagem não precisa saber que
consentimento existe.

**Onde é garantida.** Infra: `ClientesJdbc.CONSENTIMENTOS_VIGENTES` aceita
`cliente_id in (select id from cliente where mesclado_para = :id)`.
**Rotinas.** RT-CLI-004, RT-CLI-003 · **Teste.**
`ConsentimentoIT.revogacao_sobrevive_a_mesclagem`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-022 — Anonimizar apaga o dado pessoal e preserva a linha

**Enunciado.** Nome vira um rótulo fixo; apelido, telefone, e-mail e observação são apagados;
`anonimizado_em` é preenchido. A linha e o `id` permanecem.

**Motivo.** O histórico financeiro aponta para o cliente e a legislação fiscal exige guardá-lo.
Apagar a linha quebraria o extrato de comissão de quem trabalhou — o direito do titular é sobre o
dado pessoal, não sobre o registro contábil do salão. O rótulo é fixo e sem número: "Cliente 7"
convidaria alguém a descobrir quem era o sétimo.

**Nota de honestidade.** Como o `id` permanece, isto é pseudonimização no sentido estrito do art.
12. O que torna a operação defensável é que todo dado que permitiria voltar à pessoa é destruído —
inclusive na auditoria (RN-CLI-007).

**Onde é garantida.** Infra: `ClientesJdbc.anonimizar`.
**Rotinas.** RT-CLI-005 · **Teste.** `AnonimizacaoIT.apaga_o_dado_e_mantem_a_linha`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-023 — A anonimização alcança as fichas absorvidas

**Enunciado.** Anonimizar uma ficha anonimiza também todas as que foram mescladas nela.

**Motivo.** A ficha absorvida continua guardando o nome e o telefone com que foi cadastrada, e é a
mesma pessoa. Anonimizar só a sobrevivente deixaria o dado intacto na ficha ao lado — exatamente
onde ninguém pensaria em olhar.

**Onde é garantida.** Infra: `where (id = :id or mesclado_para = :id)`, correto porque RT-CLI-003
mantém as cadeias planas.
**Rotinas.** RT-CLI-005, RT-CLI-003 · **Teste.**
`AnonimizacaoIT.alcanca_as_fichas_absorvidas`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-024 — Cliente anonimizada não recebe mensagem nenhuma

**Enunciado.** `podeReceber` devolve `false` para toda finalidade, inclusive as permitidas por
padrão pela base legal.

**Motivo.** Mandar lembrete para quem pediu para ser esquecida seria o pedido atendido no banco e
ignorado na prática — a pior combinação possível, porque o sistema pareceria conforme.

**Onde é garantida.** `ClientesJdbc.podeReceber` falha fechada quando `existe` é falso, e `existe`
exclui anonimizadas.
**Rotinas.** RT-CLI-005, RT-CLI-004 · **Teste.** `AnonimizacaoIT.nao_recebe_mais_mensagem`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-025 — Anonimizar exige permissão própria, separada de `cliente:write`

**Enunciado.** `cliente:anonymize`, concedida apenas ao `ADMIN` — nem o `GERENTE` a tem.

**Motivo.** A operação é irreversível. A recepção cadastra e corrige o dia inteiro; apagar o dado
de alguém para sempre é outro tipo de ato, e o pedido do titular chega a quem responde pelo salão,
não a quem está atendendo o balcão.

**Onde é garantida.** `AnonimizarClienteUseCase` com `@PreAuthorize` · `MapaDePermissoes`.
**Rotinas.** RT-CLI-005 · **Teste.** `AnonimizacaoIT.exige_permissao_propria`,
`ClientesWebIT.recepcao_nao_anonimiza`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-026 — A tela mostra a interpretação do SERVIDOR, nunca um palpite próprio

**Enunciado.** A resposta da busca carrega o que o servidor entendeu do termo (`TELEFONE`, `NOME`
ou `INSUFICIENTE`) e o texto do que falta. A tela exibe isso; não reimplementa a regra.

**Motivo.** É a mesma armadilha que RN-CLI-011 recusou no banco. Uma cópia de "dígito significa
telefone" em TypeScript divergiria em silêncio, e a tela passaria a dizer uma coisa enquanto a
consulta fazia outra — o pior tipo de erro, porque parece funcionar. Pelo mesmo motivo "informe ao
menos 2 letras" vem do domínio: reescrevê-lo no front criaria dois textos para uma regra só.

**Onde é garantida.** `ResultadoDaBusca` (api) · `Clientes.tsx` só desenha o que recebeu.
**Rotinas.** RT-CLI-006, RT-CLI-002 · **Teste.**
`BuscaDeClienteIT.resposta_diz_o_que_entendeu`, `Clientes.test.tsx` ("mostra o que o SERVIDOR
entendeu")
**Configurável?** Não · **UX.** R-UX-03
**Origem.** 2026-08-30

---

### RN-CLI-027 — A tela só oferece juntar fichas que podem ser a mesma pessoa

**Enunciado.** `PROVAVEL` e `POSSIVEL` são acionáveis para mesclagem. `MESMO_TELEFONE` aparece
como contexto, sem botão e sem destaque de alerta.

**Motivo.** Todo o RT-CLI-003 existe para distinguir a mesma pessoa cadastrada duas vezes de mãe e
filha dividindo o número. Oferecer "juntar" no segundo caso desfaz essa distinção na interface e
convida a fundir duas pessoas diferentes — o estrago que o domínio evita, entregue em um clique.

**Onde é garantida.** `AvisoDeDuplicado` só torna clicável quem não é `MESMO_TELEFONE`.
**Rotinas.** RT-CLI-006, RT-CLI-003 · **Teste.** `Ficha.test.tsx` ("mesmo telefone com nome
diferente NÃO é apresentado como duplicidade")
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-028 — A tela separa o efeito do registro no consentimento

**Enunciado.** Cada finalidade mostra duas coisas distintas: o que vale hoje ("pode receber" /
"não recebe") e o que a pessoa disse ("Sem registro", "Aceitou em 12/03/2026"). Aceitar e recusar
têm o mesmo peso visual.

**Motivo.** "Sem registro" não é "não pode" (RN-CLI-018). Um interruptor tem dois estados e
mostraria "desligado" para uma cliente que o salão pode e deve avisar sobre o horário dela. E o
peso igual entre aceitar e recusar é o que cumpre o espírito do art. 8 §5, não só a letra — um
botão menor para revogar seria a lei atendida no papel.

**Onde é garantida.** `Consentimentos.tsx`.
**Rotinas.** RT-CLI-006, RT-CLI-004 · **Teste.** `Ficha.test.tsx` ("separa o EFEITO do REGISTRO",
"recusar tem o mesmo peso visual que aceitar")
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-029 — Corrigir a ficha exige reapresentar a versão

**Enunciado.** O `PUT` carrega a `versao` lida na abertura. Se ela não bate, a gravação é recusada
com `ER-INF-VERSAO_DESATUALIZADA` e nada é alterado.

**Motivo.** Duas recepções abrem a mesma ficha; uma corrige o telefone, a outra corrige o nome e
salva depois. Sem a trava, a segunda gravação devolve o telefone antigo — e ninguém descobre até a
cliente não atender. Trava otimista e não bloqueio: o caso é raro, e segurar linha por um caso raro
custa em todos os outros.

**Onde é garantida.** Infra: `where versao = :versao` no `update`.
**Rotinas.** RT-CLI-007 · **Teste.** `EdicaoDeClienteIT.versao_vencida_e_recusada`
**Erro.** `ER-INF-VERSAO_DESATUALIZADA` (409) · **Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-030 — A trilha de alteração registra quais campos mudaram, nunca os valores

**Enunciado.** A auditoria de `CLIENTE_ALTERADO` grava a lista de campos alterados. Valores antigos
e novos não entram.

**Motivo.** Consequência direta de RN-CLI-007: a auditoria é append-only e a anonimização não a
alcança. Guardar ali o telefone antigo criaria a segunda base de dado pessoal que RT-CLI-005 teve
de sair caçando. O custo é declarado — a trilha responde "quem alterou o telefone e quando", não
"qual era" — e é a perda que a LGPD impõe.

**Onde é garantida.** `AtualizarClienteUseCase.camposAlterados`.
**Rotinas.** RT-CLI-007, RT-CLI-005 · **Teste.** `EdicaoDeClienteIT.trilha_sem_dado_pessoal`
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-CLI-031 — Comprovante é execução de contrato, não consentimento

**Enunciado.** `Finalidade.COMPROVANTE_DE_ATENDIMENTO` tem base legal `EXECUCAO_DE_CONTRATO`, e
portanto é permitida sem registro de decisão da cliente.

**Motivo.** A cliente **pediu** o recibo do serviço que acabou de pagar. Exigir opt-in para
entregar o comprovante do que ela comprou transformaria a LGPD em obstáculo ao próprio contrato — e
o efeito prático seria a recepção mandando pelo WhatsApp pessoal, fora de qualquer registro, o que
é pior para a cliente em todos os sentidos.

O que continua valendo é o resto: sem telefone não sai, a anonimização (RT-CLI-005) alcança isto
como alcança tudo, e quem decide se pode enviar continua sendo o `notificacao`, contra o
`ClienteApi` — a rotina do comprovante não repete a pergunta.

**Onde é garantida.** `Finalidade.COMPROVANTE_DE_ATENDIMENTO(BaseLegal.EXECUCAO_DE_CONTRATO)` ·
V44 estende o `check` de `cliente_consentimento` **e** o de `mensagem`.
**Rotinas.** RT-FIN-008 · **Teste.** `ConsentimentoIT` percorre `Finalidade.values()` contra o
`check` do banco · `ComprovanteIT.envio_e_idempotente`
**Configurável?** Não · **Origem.** 2026-09-12
