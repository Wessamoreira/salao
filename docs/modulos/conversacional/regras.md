# Regras de negócio — módulo `conversacional`

---

### RN-CNV-001 — O webhook é autenticado pelo HMAC do corpo, e recusa tudo sem segredo

**Enunciado.** `POST /webhooks/whatsapp` é público e não tem token nem tenant. O que separa uma
entrega da Meta de um POST forjado é o cabeçalho `X-Hub-Signature-256`: HMAC-SHA256 sobre os
**bytes exatos** do corpo, com o segredo do app, comparado em **tempo constante**. Sem segredo
configurado, a rota responde **503** a toda requisição. Assinatura inválida responde **403**, e o
corpo **não é interpretado**.

**Motivo.** É a única rota pública de escrita do sistema, e a Meta não tem como se autenticar de
outra forma — ela não carrega credencial nossa e não sabe de tenant.

Sobre os **bytes**, e não sobre o JSON reserializado: desserializar e serializar de novo muda
espaço, ordem de chaves e escape de unicode, e o HMAC do resultado não é o mesmo. Daí o corpo ser
lido como `byte[]` e a validação vir antes de qualquer parse — o parser é a maior superfície de
ataque da rota, e só deve ver bytes que a Meta assinou.

Em **tempo constante** porque comparação que sai no primeiro byte diferente vaza, pelo tempo de
resposta, quantos bytes iniciais estavam certos; com esse oráculo a assinatura se descobre byte a
byte sem nunca conhecer o segredo. Vale igual para o token do handshake.

**Falha fechada** porque o modo de falha real é o deploy com a variável esquecida. Aceitar por não
ter como validar daria a qualquer um na internet permissão de gravar mensagem em nome de qualquer
salão. 503 e não 403: a recusa é nossa, não de quem chamou.

**403 e não 200** na assinatura inválida: 200 tornaria requisição forjada indistinguível de
legítima para quem lê o log de acesso.

**Onde é garantida.** `AssinaturaDoWebhook` (`MessageDigest.isEqual`) ·
`WebhookDoWhatsAppController.receber` (ordem das verificações) ·
`app.whatsapp.app-secret` sem valor padrão útil.
**Rotinas.** RT-CNV-001 · **Teste.** `AssinaturaDoWebhookTest` (8 casos),
`WebhookDoWhatsAppControllerTest.falha_fechada`, `.assinatura_invalida`, `.sem_assinatura`,
`WebhookDoWhatsAppWebIT.rota_publica`, `.assinatura_invalida_no_fio`
**Configurável?** O segredo é por ambiente · **Origem.** 2026-09-12

---

### RN-CNV-002 — Idempotência por mensagem, não por requisição

**Enunciado.** A chave é o `provedor_id` — o `wamid` que a Meta gera — único por
estabelecimento. Uma mensagem que já chegou não é gravada de novo, **e não publica evento de
novo**. Um lote pode conter mensagens novas e repetidas ao mesmo tempo: as novas entram, as
repetidas são contadas.

**Motivo.** A Meta reenvia o lote quando não recebe 200 a tempo, e o reenvio traz as mensagens
antigas **junto** com as que chegaram depois. Sem isto, um "marca quarta 10h" vira dois
agendamentos — o modo de falha que RT-INF-005 descreve.

A `Idempotencia` de RT-INF-005 **não serve aqui**, e a diferença é de forma: ela é chaveada por um
header que o cliente manda, uma chave por operação. A Meta não manda header nenhum e agrupa várias
mensagens por requisição. Idempotência por requisição descartaria as mensagens novas que vieram na
carona do reenvio.

Quem arbitra é o índice único `uma_mensagem_por_provedor` com `on conflict do nothing`, e não uma
consulta prévia: entre consultar e inserir cabe o reenvio, e duas requisições concorrentes com o
mesmo `wamid` passariam as duas. O evento não sair duas vezes é a metade que mais importa — é ele
que dispara o agente.

**Onde é garantida.** `V50__webhook_whatsapp.sql`, índice `uma_mensagem_por_provedor` ·
`MensagensRecebidasJdbc.inserir` (`on conflict … do nothing returning id`) ·
`GravarMensagemRecebidaUseCase` publica só no `ifPresent`.
**Rotinas.** RT-CNV-001 · **Teste.** `WebhookDoWhatsAppIT.reenvio_nao_duplica`,
`.reenvio_com_mensagem_nova`, `.mesmo_wamid_em_dois_saloes`, `.evento_sai_uma_vez`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-CNV-003 — Um POST não é de um salão: o tenant é por mensagem

**Enunciado.** O tenant de cada mensagem é resolvido a partir do `metadata.phone_number_id` do
**seu próprio** `change`. Um único POST pode carregar mensagens de estabelecimentos diferentes, e
cada uma vai para a caixa do seu.

**Motivo.** A Meta agrupa por **app**, não por número. Um app que serve dez salões pode entregar,
na mesma requisição, mensagem de três deles.

Resolver o tenant uma vez, a partir do primeiro `entry`, é o jeito natural de escrever isso — e
grava a conversa de uma cliente na caixa de outro salão, sem erro, sem log e sem ninguém
desconfiar até alguém ler a conversa errada. É a armadilha central da rotina.

Cada gravação é a sua própria transação, por isso: elas são de tenants diferentes, e
`app.tenant_id` é definido na abertura da transação.

**Onde é garantida.** `CargaDoWhatsApp.mensagens` devolve o `phoneNumberId` em cada mensagem ·
`ReceberLoteDoWhatsAppUseCase` resolve dentro do laço · `GravarMensagemRecebidaUseCase` é classe
separada para que `@Transactional` seja interceptado.
**Rotinas.** RT-CNV-001 · **Teste.** `WebhookDoWhatsAppIT.lote_multi_tenant`,
`CargaDoWhatsAppTest.lote_multi_tenant`, `WebhookDoWhatsAppIT.isolamento`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-CNV-004 — Número sem canal ativo é descartado, contado e nunca em silêncio

**Enunciado.** Mensagem cujo `phone_number_id` não está cadastrado, ou cujo canal está
`ativo = false`, é **descartada** — e a requisição responde **200**. O descarte incrementa a
métrica `conversacional.webhook.mensagens{desfecho="sem_dono"}` e registra WARN com o
`phone_number_id`. Apagar um canal é proibido pelo banco; desliga-se.

**Motivo.** Responder erro faria a Meta reenviar para sempre uma mensagem que nunca vai ter dono,
e cada reenvio traz o lote inteiro — as mensagens boas junto.

O descarte precisa ser **visível** porque este é o pior modo de falha da integração: o número está
apontado para o app, não está cadastrado aqui, e toda mensagem se perde sem erro nenhum. A cliente
manda, ninguém responde, e a primeira notícia vem da reclamação dela. A métrica é o que transforma
isso em algo que dá para alertar.

`revoke delete` no canal existe pela mesma razão: apagar a linha faz as mensagens seguintes
chegarem sem dono e serem descartadas. Com `ativo = false` o descarte é deliberado, o cadastro
continua lá e religar não exige redigitar nada.

**Onde é garantida.** `CanaisJdbc.estabelecimentoAtivoDe` (filtra `ativo`) ·
`ReceberLoteDoWhatsAppUseCase` (contador `sem_dono`) · `V50`, `revoke delete on canal_whatsapp`.
**Rotinas.** RT-CNV-001 · **Teste.** `WebhookDoWhatsAppIT.numero_sem_dono`, `.canal_inativo`,
`.canal_nao_se_apaga`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-CNV-005 — O pedido da cliente é prova: guardado como chegou, e não se edita

**Enunciado.** A aplicação pode gravar uma mensagem recebida e marcá-la como processada. **Não
pode** alterar `texto`, `de_telefone`, `tipo` ou qualquer outra coluna, e não pode apagar. Tipo que
o sistema não trata é guardado como `NAO_SUPORTADO`, sem conteúdo — não descartado.

**Motivo.** O consumidor desta tabela vai ser um LLM (RT-CNV-004), e o padrão inegociável do plano
é que ele nunca escreva direto no domínio. A garantia disso não é o prompt: é a permissão de
coluna. Reescrever `texto` seria reescrever o que a cliente pediu, e é justamente o registro que
resolve a discussão de "eu pedi quarta, não quinta".

Guardar o tipo não tratado, em vez de descartá-lo, é o que torna *"a cliente mandou e ninguém
respondeu"* distinguível de *"a cliente não mandou nada"* — que é a primeira pergunta do suporte.
Sem conteúdo porque não sabemos interpretá-lo, e o log registra apenas **qual** tipo novo apareceu.

`recebida_em` é informada pela aplicação e não pelo banco: a fila de processamento compara esse
valor com o port `Relogio` (RN-INF-006), e um `default now()` poria dois relógios na mesma
comparação.

**Onde é garantida.** `V50__webhook_whatsapp.sql` —
`revoke update, delete` + `grant update (processada_em)`; `check mensagem_coerente_com_o_tipo` ·
`CargaDoWhatsApp` traduz o desconhecido para `NAO_SUPORTADO`.
**Rotinas.** RT-CNV-001 · **Teste.** `WebhookDoWhatsAppIT.grant_de_coluna`,
`.tipo_nao_suportado`, `CargaDoWhatsAppTest.tipo_desconhecido`, `.incompletos`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-CNV-006 — Áudio vai para uma fila; rede nunca dentro de transação

**Enunciado.** O consumidor de `MensagemDeClienteRecebida` só **enfileira** o áudio numa linha de
`transcricao_de_audio`. Baixar e transcrever é trabalho do processador, fora de transação, em duas
etapas com credenciais próprias: `PENDENTE` precisa do token da Meta, `BAIXADA` precisa do provedor
de STT. Sem a credencial de uma etapa, as linhas dela **esperam sem gastar tentativa**.

**Motivo.** `@ApplicationModuleListener` abre transação antes do corpo do método, e com o
`TenantAwareTransactionManager` isso pega uma conexão na hora. Baixar e transcrever ali seguraria
uma conexão do pool por dezenas de segundos a cada nota de voz — o gargalo de R-18, provocado pelo
recurso mais usado do agente. A fila é o padrão de RT-NOT-001: gravar primeiro transforma falha de
rede em linha para tentar de novo.

Credencial ausente não é falha. Tratar como falha gastaria as cinco tentativas em minutos e mataria
os áudios que chegaram antes de alguém configurar o token — exatamente os que o salão vai querer
quando configurar.

**Onde é garantida.** `EnfileiradorDeAudios` · `ProcessadorDeAudios.executar` (confere
`configurado()` antes de reservar) · `V52`.
**Rotinas.** RT-CNV-003 · **Teste.** `TranscricaoDeAudioIT.audio_entra_pelo_outbox`,
`.texto_nao_entra`, `.aviso_repetido`, `.caminho_completo`, `.sem_token_espera`
**Configurável?** O intervalo (`app.whatsapp.audio.intervalo`) · **Origem.** 2026-09-13

---

### RN-CNV-007 — O token do app não sai do perímetro da Meta

**Enunciado.** O download de mídia manda o token do app nos dois pedidos, como a Meta exige. Antes
do segundo — cuja URL vem na resposta — o destino precisa ser **https**, num host da lista da Meta
casado por **sufixo com ponto** (`.fbsbx.com`, `.fbcdn.net`, `.whatsapp.net`). Redirecionamento
**nunca** é seguido. O id da mídia é conferido contra `[A-Za-z0-9_-]` antes de compor a URL. O
tamanho tem teto de 16 MB no metadado **e** na leitura, e o sha256 declarado é conferido contra os
bytes.

**Motivo.** O token lê e manda mensagem por todos os salões. Uma URL de conteúdo apontando para
outro host levaria o `Authorization` junto — e quem controlasse aquele host teria o WhatsApp de
todos os clientes do sistema. `fbsbx.com.atacante.net` e `atacantefbsbx.com` são as duas formas de
enganar um casamento por sufixo mal feito; o ponto é o que as recusa. Um 302 levaria o cabeçalho
para onde a lista não olhou.

A política entra pelo construtor, e não por propriedade: uma chave "hosts extras" seria a chave que
alguém vira em produção para destravar um teste.

O id vai no caminho: `../` montaria outra rota da Graph API com o token do app. O teto na leitura
existe porque o tamanho do metadado é uma afirmação. E o sha256 impede que um áudio adulterado no
caminho vire transcrição — e agendamento.

**Onde é garantida.** `MidiaDoWhatsAppGraph` (`PoliticaDeUrl.daMeta`, `Redirect.NEVER`,
`ID_DE_MIDIA`, `conferirIntegridade`).
**Rotinas.** RT-CNV-003 · **Teste.** `MidiaDoWhatsAppGraphTest.token_nao_sai_do_perimetro` (o host
estranho recebe ZERO pedidos), `.politica_de_producao`, `.redirecionamento`, `.id_com_caminho`,
`.tamanho_declarado`, `.teto_na_leitura`, `.sha_adulterado`
**Configurável?** Não · **Origem.** 2026-09-13

---

### RN-CNV-008 — Reserva atômica, que vence; a tentativa conta na reserva

**Enunciado.** O processador reserva linhas com um único `UPDATE ... RETURNING` cuja subconsulta usa
`for update skip locked`, marcando `reservada_ate` e somando uma tentativa no mesmo comando. Linha
reservada por outra instância não é tocada; reserva vencida é retomada. Cada etapa tem cinco
tentativas com espera dobrando (`EsperaEntreTentativas`); falha permanente encerra na primeira.

**Motivo.** Mais de uma instância roda o processador. O padrão que parece equivalente —
`SELECT ... FOR UPDATE SKIP LOCKED` solto, e depois o trabalho — **não protege nada em autocommit**:
o lock é solto no fim do próprio `SELECT`. Foi assim que esta rotina encontrou o mesmo defeito no
despachante de mensagens (registrado como tarefa à parte). Lock e marcação no mesmo comando valem
mesmo sem transação.

A reserva vence para que a instância que morreu no meio não prenda a linha para sempre. E a
tentativa é contada na reserva, e não na falha: um áudio que derruba o processo a cada vez gastaria
tentativa nenhuma e ficaria em laço.

**Onde é garantida.** `ProcessadorDeAudios.RESERVAR` · `GuardarAudioBaixado` trava a linha dentro
da própria transação antes de guardar.
**Rotinas.** RT-CNV-003 · **Teste.** `TranscricaoDeAudioIT.reserva`, `.transitoria_adia`,
`.desiste_na_quinta`, `.permanente_falha_de_vez`
**Configurável?** Não · **Origem.** 2026-09-13

---

### RN-CNV-009 — O áudio guardado é a fonte, e o que foi entendido não é reescrito

**Enunciado.** O áudio baixado é guardado em `arquivos`, validado pelos bytes, com dono `MENSAGEM`.
A partir daí, toda tentativa de transcrição lê a **nossa cópia**, não a Meta. Transcrição
`CONCLUIDA` não é sobrescrita, e a aplicação não apaga linha da fila. Bytes que não são áudio viram
arquivo `RECUSADO` e linha `FALHOU` no mesmo commit, sem exceção.

**Motivo.** A mídia da Meta expira. Uma retentativa de STT que dependesse dela morreria junto — e o
áudio que chegou antes de alguém escolher o provedor seria perdido no dia da escolha.

E o áudio é a prova. Quando a transcrição errar "quinta" por "quarta" e o agendamento sair errado, o
que resolve a discussão é o que a cliente disse, não o que o provedor entendeu.

A recusa sem exceção é a lição de RN-ARQ-003: lançar de dentro da transação desfaria o registro da
própria recusa.

**Onde é garantida.** `ProcessadorDeAudios.transcrever` (lê por `IngestaoDeArquivoApi.conteudo`) ·
`TranscricoesJdbc.concluir` (`where status = 'BAIXADA'`) · `GuardarAudioBaixado` · `V52`
(`revoke delete`).
**Rotinas.** RT-CNV-003 · **Teste.** `TranscricaoDeAudioIT.retentativa_usa_a_copia`,
`.concluida_nao_e_reescrita`, `.bytes_que_nao_sao_audio`, `.nao_apaga`, `.isolamento`
**Configurável?** Não · **Origem.** 2026-09-13

---

### RN-CNV-010 — O modelo é tratado como comprometido: três trancas, nenhuma no prompt

**Enunciado.** O modelo só vê as ferramentas permitidas ao usuário efetivo — com TODAS as permissões
declaradas presentes. Qualquer pedido fora delas é recusado pelo orquestrador, com o motivo
registrado: `INEXISTENTE` ou `FORA_DA_LISTA`. O que passa é executado **em nome do** usuário
efetivo, e o `@PreAuthorize` do caso de uso confere de novo (`SEM_PERMISSAO`). Escrita nunca é
executada pelo orquestrador, nem com permissão: vira `PROPOSTA` (RN-CNV-014).

> **Revisada em RT-CNV-005.** Até ali a escrita era recusada num `if`, com o desfecho
> `ESCRITA_EXIGE_CONFIRMACAO`. O contrato foi selado em leitura e escrita, e o orquestrador deixou de
> ter como gravar — não há mais o que recusar.

**Motivo.** docs/05: prompt injection "tem que esbarrar em permissão, não em prompt". A pergunta de
projeto não é como impedir o modelo de ser enganado — é o que acontece quando ele for. Resposta:
nada além do que a pessoa já pode pela tela.

Três trancas porque cada uma pode falhar sozinha. A lista é calculada da declaração da ferramenta, e
a declaração pode estar errada; o `@PreAuthorize` não depende dela. A recusa volta ao modelo como
erro e fica no rastro: o ataque aparece como RECUSADO, e não como silêncio.

Nenhuma frase de segurança no prompt, de propósito: "não faça X" é uma instrução que a mensagem
seguinte revoga.

**Onde é garantida.** `CatalogoDeFerramentas.permitidasPara` · `Orquestrador.avaliar` ·
`ProcessadorDeInteracoes` (executor por `UsuarioEfetivoApi.emNomeDe`).
**Rotinas.** RT-CNV-004, RT-CNV-005 · **Teste.** `OrquestradorTest.modelo_comprometido`,
`.so_ve_a_lista`, `.escrita_vira_proposta`, `AgenteIT.modelo_comprometido`,
`CatalogoDeFerramentasTest.escrita_com_permissao`, `.sem_declaracao`
**Configurável?** Não · **Origem.** 2026-09-13

---

### RN-CNV-011 — O teto de custo é reservado antes de cada chamada, e sem preço o agente não roda

**Enunciado.** Antes de cada chamada ao modelo, o pior caso — tokens estimados de entrada e o máximo
de saída — é reservado no teto do salão para o dia civil no fuso do salão. Não coube: a conversa
para ali, sem chamar. Depois da chamada, a reserva é trocada pelo custo real; se o modelo falhar, a
reserva é solta com custo zero. Sem preço configurado, o agente não roda.

**Motivo.** R-17: custo de LLM sem teto consome o orçamento sem ninguém ver. O custo só é conhecido
quando a chamada volta, e "conferir antes, gravar depois" deixaria chamadas concorrentes passarem
juntas pelo mesmo saldo. A reserva é um comando só (`insert ... on conflict do update ... where
cabe`), e a linha do dia travada pelo conflito é o que serializa as reservas.

Reserva presa por queda de processo fica até o fim do dia: erra para o lado de gastar menos.

Sem preço, não há custo; sem custo, não há teto. Um preço padrão no código seria uma tabela
inventada, desatualizada no dia em que o provedor mudasse — e o teto viraria ficção.

**Onde é garantida.** `ConsumoDeIaJdbc.reservar` · `Orquestrador.conduzir` (reserva por iteração,
liquida inclusive na falha) · `PrecoDoModelo.configurado` · `V53`.
**Rotinas.** RT-CNV-004 · **Teste.** `AgenteIT.teto_de_custo`, `.falha_transitoria`,
`OrquestradorTest.teto_antes`, `.teto_no_meio`, `.falha_solta_reserva`
**Configurável?** O valor do teto (`app.agente.teto-diario-usd`) · **Origem.** 2026-09-13

---

### RN-CNV-012 — Encerrar sem gastar: identidade, limite, teto — nessa ordem

**Enunciado.** Antes de chamar o modelo, a conversa é recusada sem custo quando o remetente não é
usuário efetivo (motivo da identidade), quando o telefone passou do limite por hora
(`LIMITE_POR_TELEFONE`), ou quando o teto do dia não comporta a chamada (`TETO_DE_CUSTO`). A
recusa é registrada e nada é respondido. Há também um teto de quatro iterações por conversa.

**Motivo.** docs/05: "telefone não cadastrado → o bot responde como desconhecido e não executa
nada". Chamar o modelo para decidir que não vai atender quem não é da equipe gastaria dinheiro com
texto de estranho — inclusive com texto de ataque.

O limite por telefone protege o teto de um número em laço. É por instância, como o limite por IP; o
teto, que protege o dinheiro, é global, no banco. O limite de iterações existe porque um modelo que
pede ferramenta sem fim consumiria o dia do salão numa conversa só.

**Onde é garantida.** `ProcessadorDeInteracoes.conduzir` · `LimitePorTelefone` ·
`Orquestrador.MAXIMO_DE_ITERACOES`.
**Rotinas.** RT-CNV-004 · **Teste.** `AgenteIT.desconhecido`, `.dono_nao_conversa`,
`.limite_por_telefone`, `.sem_modelo`, `OrquestradorTest.laco_infinito`
**Configurável?** O limite (`app.agente.limite-por-telefone-por-hora`) · **Origem.** 2026-09-13

---

### RN-CNV-013 — Ferramenta é contribuída pelo módulo dono, e declara o que exige

**Enunciado.** O contrato `Ferramenta` mora em `shared.agente`. Cada módulo publica as suas como
beans, chamando os PRÓPRIOS casos de uso. Toda ferramenta declara nome (`[a-zA-Z0-9_-]{1,64}`) e
permissões exigidas, e tem uma de duas formas — leitura ou escrita (RN-CNV-014); sem permissão
declarada, nunca é oferecida.
Nome repetido ou fora do formato derruba a aplicação na subida.

**Motivo.** A segunda tranca de RN-CNV-010 só existe se a ferramenta chamar o caso de uso com o
`@PreAuthorize`. A fronteira proíbe `conversacional` de chamar caso de uso interno de outro módulo —
chamando só a API pública, a tranca some. E pôr o contrato em `conversacional` faria os módulos de
negócio dependerem dele. Em `shared`, que é aberto e não depende de ninguém, os dois problemas
somem.

Ferramenta que não diz o que exige é tratada como perigosa. E dois beans com o mesmo nome fariam o
modelo pedir um e o sistema executar o outro, sem erro nenhum — por isso a falha é na subida.

**Onde é garantida.** `shared.agente.Ferramenta` · `CatalogoDeFerramentas` (construtor) ·
`FerramentaListarServicos` (a primeira, do `catalogo`) · desde RT-CNV-005, `FerramentaBuscarClientes`
(`cliente`), `FerramentaConsultarDisponibilidade` e `FerramentaAgendar` (`agenda`).
**Rotinas.** RT-CNV-004 · **Teste.** `CatalogoDeFerramentasTest.nome_repetido`, `.nome_invalido`,
`.todas_as_permissoes`, `AgenteIT.recepcao_conversa`
**Configurável?** Não · **Origem.** 2026-09-13

---

### RN-CNV-014 — A escrita pelo agente não tem `executar`: o modelo propõe, a pessoa grava

**Enunciado.** Ferramenta tem duas formas, seladas: leitura (`executar`) e escrita (`simular` e
`confirmar`). O que o modelo alcança de uma escrita é `simular`, que produz uma **intenção pendente**
com resumo do servidor, comando e código. `confirmar` só é chamado por `EfetivarIntencao`, que só é
alcançada pela mensagem da própria pessoa com o código. Leitura e simulação rodam dentro de uma
transação somente leitura: o banco recusa escrita vinda delas.

**Motivo.** O padrão inegociável do plano: toda escrita passa por simular → confirmação humana
explícita → confirmar, e o LLM nunca escreve no domínio. Com `confirmar` como ferramenta do modelo,
quem decidiria que houve confirmação seria o modelo — tratado como comprometido (RN-CNV-010). Um ataque
que o fizesse simular e confirmar na mesma volta gravaria sem ninguém confirmar nada.

Selada porque a forma proibida — escrita com `executar` — passa a não compilar; o `if` que a recusava
em RT-CNV-004 dependia de alguém declarar a natureza certa. A regra de arquitetura cobre o que o tipo
não cobre: outra classe chamando `confirmar`.

A transação somente leitura existe porque a forma é declarada por quem escreve a ferramenta, e pode
estar errada. Uma "leitura" que grava, ou uma simulação que grava, esbarra no banco.

**Onde é garantida.** `shared.agente.Ferramenta` (`sealed`) · `ExecutorDeFerramenta` (não tem método
que grave) · `TransacaoSomenteLeitura` · `CriarAgendamentoUseCase.simular` (`readOnly`) ·
`ArquiteturaTest.so_a_confirmacao_da_pessoa_grava_pelo_agente`.
**Rotinas.** RT-CNV-005 · **Teste.** `OrquestradorTest.escrita_vira_proposta`,
`.modelo_comprometido`, `AgenteIT.leitura_que_grava`, `IntencaoPendenteIT.o_modelo_nao_confirma`,
`.caminho_completo`, `FerramentasDaAgendaIT.simular_nao_grava`
**Configurável?** Não · **Origem.** 2026-09-13

---

### RN-CNV-015 — Confirmação é a mensagem inteira "SIM + código", reconhecida antes do modelo

**Enunciado.** Mensagem cujo texto INTEIRO é "SIM" seguido do código de quatro dígitos é reconhecida
pelo processador **antes** de qualquer chamada ao modelo, e decidida sem ele, com resposta escrita pelo
servidor. Com qualquer outra coisa na mensagem, não é confirmação. Cada telefone tem no máximo UMA
intenção pendente: a nova substitui a anterior. A intenção vale por dez minutos. O código não repete
os do mesmo telefone nas últimas 24 horas e não é enviado ao modelo.

**Motivo.** "Sim" responde a qualquer pergunta — a "quer ver outro horário?" também. O código amarra a
resposta a UM resumo: quem responde "SIM 4821" confirmou aquele texto, e não o que estiver pendente
quando a mensagem for processada. A mensagem inteira, porque "sim 4821 e cancela o resto" com o
reconhecimento parcial seria uma confirmação carregando um pedido; assim, vai ao modelo, que não grava.

O código não é segredo — quem manda mensagem daquele número é a pessoa, atestada pela Meta
(RN-CNV-001). É vínculo e intenção. Não vai ao modelo porque ele não tem o que fazer com o código, e um
código no texto do modelo competiria com o pedido de confirmação do servidor.

Uma pendente por telefone porque duas fariam a pessoa confirmar uma olhando para a outra. A validade
porque um "SIM" de amanhã confirma um resumo que já não descreve a agenda de hoje.

**Onde é garantida.** `CodigoDeConfirmacao` (expressão sobre o texto inteiro, sorteio, pedido) ·
`ProcessadorDeInteracoes.conduzir` (antes do orçamento e do modelo) · `ProporIntencao` (encerra a
anterior no mesmo commit) · `V54`, índice `uma_intencao_pendente_por_telefone`.
**Rotinas.** RT-CNV-005 · **Teste.** `CodigoDeConfirmacaoTest` (reconhece, não reconhece, sorteio,
pedido), `IntencaoPendenteIT.caminho_completo` (o modelo não é chamado), `.codigo_errado`, `.vencida`,
`.nova_proposta_substitui`
**Configurável?** A validade (`app.agente.intencao.validade`) · **Origem.** 2026-09-13

---

### RN-CNV-016 — Confirmar grava exatamente uma vez: intenção e gravação no mesmo commit

**Enunciado.** A intenção passa a `CONFIRMADA` na MESMA transação em que a ferramenta grava, sob trava
da linha da intenção. Recusa da gravação desfaz a transação inteira, e é registrada numa transação
própria (`RECUSADA`, com o código do erro). O que foi proposto — `comando`, `resumo`,
`pedido_de_confirmacao`, `codigo` — não pode ser alterado nem apagado pela aplicação.

**Motivo.** "No máximo uma vez" sem chave de idempotência: se o processo cair entre gravar e marcar,
não houve gravação; duas confirmações se enfileiram na trava e a segunda encontra `CONFIRMADA`; a
mensagem que volta à fila depois de uma queda responde "já estava confirmado". Uma chave de
idempotência repetiria a mesma garantia em outra tabela.

A recusa fora da transação é a lição de RN-ARQ-003: registrada dentro, seria desfeita junto com a
gravação que ela recusou.

A imutabilidade é o que dá sentido à confirmação. Se o comando pudesse mudar depois do "SIM", a pessoa
teria confirmado um texto e o sistema executado outro.

**Onde é garantida.** `EfetivarIntencao` (`@Transactional`, `IntencoesJdbc.travar`) ·
`ConfirmarPeloCodigo` (registro da recusa) · `V54` (`revoke update, delete` e `grant update` só nas
colunas da decisão).
**Rotinas.** RT-CNV-005 · **Teste.** `IntencaoPendenteIT.confirmacoes_simultaneas`,
`.confirmacao_repetida`, `.horario_tomado_no_meio`, `.proposto_e_imutavel`
**Configurável?** Não · **Origem.** 2026-09-13

---

### RN-CNV-017 — Na confirmação, as trancas são resolvidas de novo

**Enunciado.** Ao confirmar, o usuário efetivo é resolvido pelo telefone NAQUELE instante e precisa ser
o mesmo que propôs, ter as permissões que a ferramenta declara e passar pelo `@PreAuthorize` do caso de
uso. Intenção de outra pessoa ou de outro salão é tratada como inexistente.

**Motivo.** docs/05: permissões são resolvidas na hora da ação, nunca guardadas com a mensagem. Entre o
resumo e o "SIM" a recepcionista pode ter sido desativada, mudado de perfil, ou o número pode ter
passado a outra pessoa. Nada do momento da proposta autoriza a gravação.

"Não encontrei", e não "é de outra pessoa": confirmar a existência de um pedido alheio seria dizer a
quem não é dono que existe algo ali.

**Onde é garantida.** `EfetivarIntencao` (`emNomeDe`, `CatalogoDeFerramentas.podeUsar`, comparação
com `usuario_efetivo_id`) · `ConfirmarPeloCodigo` (filtra pelo usuário) · RLS de `intencao_pendente`.
**Rotinas.** RT-CNV-005 · **Teste.** `IntencaoPendenteIT.permissao_retirada_no_meio`,
`.telefone_trocou_de_dono` (a comparação com o dono — a busca por telefone não a cobre),
`.outra_pessoa_nao_confirma`, `.outro_salao`, `FerramentasDaAgendaIT.confirmar_confere_de_novo`
**Configurável?** Não · **Origem.** 2026-09-13

---

### RN-CNV-018 — Fora da jornada, quem vai atender aceita; o SIM de quem pediu não grava

**Enunciado.** Quando a única recusa da simulação é a jornada, a intenção nasce pedindo o aceite de
quem vai atender, pelo telefone do cadastro. O SIM de quem pediu passa a intenção a
`AGUARDANDO_ACEITE` e envia o pedido com botões — sem gravar. O toque "Aceitar" grava, em nome de
quem PEDIU, resolvido de novo, e a ferramenta só força a jornada com a prova do aceite. Intenção que
exigia aceite só pode ser `CONFIRMADA` com o aceite registrado.

**Motivo.** A tela deixa forçar a jornada com uma justificativa; pelo agente, a justificativa honesta é
a própria profissional dizendo sim. Recusar de cara faria a recepção largar o agente e ligar para ela.

Em nome de quem pediu porque aceitar o horário não é permissão de marcar: a profissional nem tem conta,
e se tivesse, o aceite dela não pode ampliar o que a recepção pode. As trancas de RN-CNV-017 valem de
novo no toque.

A constraint existe para que nenhum caminho da aplicação — nem um erro futuro — grave "confirmada"
pulando quem precisava aceitar.

**Onde é garantida.** `FerramentaAgendar` (lê `foraDaJornada` da simulação; `confirmar` exige a prova) ·
`EfetivarIntencao.executar` (SIM → `AGUARDANDO_ACEITE`) e `.decidirAceite` · `V55`,
`intencao_com_aceite_so_confirma_aceita`.
**Rotinas.** RT-CNV-006 · **Teste.** `AceiteDaProfissionalIT.caminho_completo`, `.recusa`,
`.confirmada_sem_aceite_e_recusada_pelo_banco`, `.permissao_retirada_de_quem_pediu`, `.horario_tomado_antes_do_aceite`, `.sem_telefone`,
`FerramentasDaAgendaIT.simular_fora_da_jornada_pede_aceite`, `.simular_fora_da_jornada_confere_o_resto`,
`.confirmar_fora_da_jornada_sem_aceite`, `.confirmar_com_aceite`
**Configurável?** Não · **Origem.** 2026-09-14, decisão do dono: "só o que foge da regra"

---

### RN-CNV-019 — O aceite vale pelo botão e pelo número, e não passa pelo modelo

**Enunciado.** O toque num botão de aceite vale quando o id tem o formato do botão, o token confere em
tempo constante, o toque veio do número para onde o pedido foi mandado, e a intenção ainda espera
aceite no prazo. Não exige conta de usuário. Qualquer falha responde "Não encontrei esse pedido". O
toque não chega ao modelo, não conta custo e não entra no histórico da conversa.

**Motivo.** Quem aceita é a profissional, que normalmente não tem conta — então a identidade de
RT-CNV-002 não serve. O que a Meta atesta é o NÚMERO de onde veio o toque (RN-CNV-001), e é ele que
autentica. O token não autentica: impede que um id de botão copiado de um print sirva sozinho, e que
a intenção seja adivinhável.

"Não encontrei" para toda falha, porque dizer "esse pedido é de outro número" confirmaria a quem não é
dono que o pedido existe.

Fora do histórico porque o id carrega o token — no histórico, iria para o provedor de LLM.

**Onde é garantida.** `BotaoDeAceite` · `EfetivarIntencao.valeParaEsteBotao` ·
`ProcessadorDeInteracoes.conduzirBotao` · `ExecucoesJdbc.historico` (`tipo = 'CONVERSA'`).
**Rotinas.** RT-CNV-006 · **Teste.** `BotaoDeAceiteTest`, `AceiteDaProfissionalIT.outro_numero`,
`.quem_pediu_nao_aceita_por_ela`, `.token_forjado`, `.profissional_mandando_o_codigo`,
`SaidaParaEquipeIT.botao_fora_do_historico`, `CargaDoWhatsAppTest.botao_interativo`, `.botao_de_modelo`
**Configurável?** Não · **Origem.** 2026-09-14, decisão do dono: "telefone do cadastro"

---

### RN-CNV-020 — O que o agente diz à equipe sai por uma fila, enfileirado junto com o fato

**Enunciado.** Resposta, pedido de confirmação, pedido de aceite e desfecho do aceite são enfileirados
em `mensagem_para_equipe` na MESMA transação que os produziu. O pedido de confirmação só sai ao
encerrar a conversa, depois da resposta. Conversa recusada não enfileira nada. O despachante reserva
de forma atômica, manda na ordem por destino e, sem provedor configurado, não reserva nada. A aplicação
só insere na fila.

**Motivo.** Separar o fato do aviso deixaria, numa queda, a conversa encerrada e ninguém avisado — e
nenhuma rodada seguinte a encontraria, porque já não está pendente. O índice único
(`motivo`, `origem_id`, `destino`) torna a volta à fila inofensiva.

A resposta antes do pedido porque o contrário é o "SIM + código" chegando antes do "preparei, confirme
pelo código". E só ao encerrar porque conversa que parou no teto não deve entregar pedido a quem não
recebeu resposta.

Reserva atômica porque o `SELECT ... FOR UPDATE SKIP LOCKED` em autocommit solta o lock no fim do
próprio SELECT — o defeito do despachante da notificação. Sem provedor, esperar é o honesto: marcar
enviada uma mensagem que não saiu é a pior mentira desta fila.

**Onde é garantida.** `ConcluirExecucao` · `EfetivarIntencao` · `MensagensParaEquipeJdbc` ·
`DespachanteParaEquipe` · `EnviadorNaoConfigurado` · `V55` (`revoke update, delete`).
**Rotinas.** RT-CNV-006 · **Teste.** `SaidaParaEquipeIT` — resposta na saída, pedido depois da
resposta, recusada não responde, teto não entrega pedido, em ordem por destino, sem provedor espera,
falhas, canal desligado, reserva atômica, enfileirado imutável
**Configurável?** O intervalo (`app.whatsapp.saida.intervalo`) · **Origem.** 2026-09-14, decisão do
dono: "sim, no mesmo canal"

---

### RN-CNV-021 — O aceite vence por um varredor, que avisa quem pediu no mesmo comando

**Enunciado.** O aceite vale duas horas a partir do SIM de quem pediu. Um varredor passa a cada minuto
e, num único comando, vence as intenções no prazo e enfileira o aviso a quem pediu. O toque depois do
prazo, antes do varredor, vence e avisa — uma vez só.

**Motivo.** A validade do código de confirmação se confere na hora do SIM. A do aceite não pode ser só
na hora do toque: se a profissional nunca tocar, a recepção nunca saberia.

Num comando só — `UPDATE` num `WITH` que alimenta o `INSERT` — porque em dois, uma queda entre eles
deixaria a intenção vencida e ninguém avisado, sem rodada que a reencontrasse. Duas horas porque fora
da jornada ela, por definição, não está trabalhando.

**Onde é garantida.** `ExpiradorDeAceites` · `EfetivarIntencao.decidirAceite` (toque vencido) · índice
`uma_mensagem_por_origem`.
**Rotinas.** RT-CNV-006 · **Teste.** `AceiteDaProfissionalIT.vencido_pelo_expirador`,
`.toque_depois_do_prazo`
**Configurável?** A validade (`app.agente.aceite.validade`) e o intervalo · **Origem.** 2026-09-14

### RN-CNV-022 — Toda conversa encerrada deixa uma linha na trilha, no mesmo commit do encerramento

**Enunciado.** Concluída, recusada ou que falhou; conversa ou toque de botão: ao encerrar, a execução
grava uma linha em `acao_ia` na mesma transação. Nenhum caminho encerra uma execução sem passar por
`ConcluirExecucao`.

**Motivo.** A tentativa que as trancas recusaram é justamente a que mais importa revisar — o número
desconhecido pedindo para cancelar tudo não pode sumir por ter sido recusado cedo. E separado do
encerramento, uma queda entre os dois deixaria conversa encerrada sem rastro, sem rodada que a
reencontrasse.

**Onde é garantida.** `ConcluirExecucao` (`conduzida`, `semModelo`, `recusada`, `falhou`) ·
`AcoesDoAgenteJdbc.registrar` com `Propagation.MANDATORY` · `unique (execucao_id)`.
**Rotinas.** RT-CNV-007 · **Teste.** `AcaoIaIT.conversa_vira_acao`, `.desconhecido_na_trilha`,
`.falhou_na_trilha`, `.audio_na_trilha`
**Configurável?** Não · **Origem.** 2026-09-14

### RN-CNV-023 — A trilha é um retrato: imutável, e com "gravou" e "bloqueada" decididos ao registrar

**Enunciado.** A aplicação só insere e lê `acao_ia`. `bloqueada` é verdadeiro quando a conversa foi
recusada ou quando alguma chamada parou numa tranca (`INEXISTENTE`, `FORA_DA_LISTA`,
`SEM_PERMISSAO`); virar proposta ou esbarrar em regra do domínio não é bloqueio. `gravou` é verdadeiro
quando foi esta conversa que confirmou — o SIM de uma intenção sem aceite, ou o toque de aceite.

**Motivo.** A trilha vale 12 meses e as tabelas de onde ela vem, menos: calcular na leitura
dependeria do que o expurgo já apagou. E trilha que a aplicação pode reescrever não prova nada
(RN-IAM-017).

**Onde é garantida.** `REGISTRAR` em `AcoesDoAgenteJdbc` · `V56` (`revoke update, delete`).
**Rotinas.** RT-CNV-007 · **Teste.** `AcaoIaIT.cada_tranca_bloqueia`, `.confirmacao_gravou`,
`.trilha_imutavel`, `AceiteDaProfissionalIT.caminho_completo`
**Configurável?** Não · **Origem.** 2026-09-14

### RN-CNV-024 — O token do aceite não entra na trilha

**Enunciado.** No toque de botão, `pedido` é "Aceitar", "Recusar" ou "Botão" — nunca o id do botão.

**Motivo.** O id carrega o token que, junto com o número, decide o aceite (RN-CNV-019). A trilha é lida
por mais gente, e por mais tempo, do que a fila que o usa.

**Onde é garantida.** `REGISTRAR` em `AcoesDoAgenteJdbc`.
**Rotinas.** RT-CNV-007 · **Teste.** `AcaoIaIT.botao_sem_token`
**Configurável?** Não · **Origem.** 2026-09-14

### RN-CNV-025 — Revisar é do dono e da gerência, só leitura, no dia do salão, com teto de 92 dias

**Enunciado.** O painel exige `agente:review` (ADMIN e GERENTE). Os dias são civis no fuso do salão;
sem período, os últimos sete, e no máximo 92. Quem pediu aparece pelo nome de hoje; sem usuário, só os
quatro últimos dígitos do telefone — com usuário, nem isso. Nada no painel altera a trilha.

**Motivo.** A tela mostra mensagens de quem não é da equipe e o que o agente consultou em nome de cada
pessoa. Um ano inteiro de trilha numa consulta é relatório, e relatório é outra rotina.

**Onde é garantida.** `ConsultarAcoesDoAgenteUseCase` (`@PreAuthorize`, `periodo`, `acao`) ·
`MapaDePermissoes`.
**Rotinas.** RT-CNV-007 · **Teste.** `AcaoIaIT.sem_permissao`, `.periodo`,
`.recortes_pessoa_e_resumo`, `.isolamento`, `AcoesDoAgenteWebIT`
**Configurável?** Não · **Origem.** 2026-09-14

### RN-CNV-026 — Aos 90 dias o conteúdo sai; aos 12 meses, a linha

**Enunciado.** Um expurgo diário anonimiza as linhas com mais de 90 dias — pedido, resposta, telefone,
argumentos e resumos — e apaga as com mais de 12 meses. O esqueleto que fica responde "o agente fez
isso?" sem guardar a conversa.

**Motivo.** Minimização (LGPD): o conteúdo só é útil enquanto uma reclamação ainda pode chegar. A
conexão de manutenção só tem `update` nas colunas de conteúdo — o expurgo pode apagar o que foi dito,
não reescrever o que aconteceu.

**Onde é garantida.** `PurgadorDeAcoesDoAgente` · `V56` (política e grants de `salao_manutencao`,
constraint `acao_anonimizada_sem_conteudo`).
**Rotinas.** RT-CNV-007 · **Teste.** `AcaoIaIT.retencao`
**Configurável?** Os prazos (`app.agente.acao-ia.anonimizar-apos`, `apagar-apos`) · **Origem.** 2026-09-14

### RN-CNV-027 — Quem não é da equipe recebe, uma vez, um aviso fixo: é IA, não atende clientes, e o contato do salão

**Enunciado.** A primeira mensagem de um número desconhecido — texto, áudio ou qualquer outro tipo,
menos toque de botão — faz enfileirar uma mensagem fixa: o número é atendido por um assistente de
inteligência artificial que trabalha só para a equipe, ele não atende clientes, ninguém lê o que chega
ali, e o contato que o salão cadastrou. Nasce na chegada da mensagem, e não no processador de conversas.

**Motivo.** Transparência sobre IA (LGPD, docs/05). Na v1 a cliente não conversa com o agente
(pergunta 8), e até aqui a resposta a ela era silêncio — sem saber que ninguém lia. Na chegada porque o
processador não roda sem modelo configurado, e o aviso não pode esperar um LLM para dizer que ali atende
um; e porque áudio esperaria um provedor de transcrição.

**Onde é garantida.** `AvisarQuemNaoEDaEquipe` · `AvisoDeIa` · motivo `AVISO_DE_IA` na saída.
**Rotinas.** RT-CNV-008 · **Teste.** `AvisoDeIaIT.avisado_uma_vez_sem_modelo`, `.audio_avisa`,
`.sai_como_resposta`, `AvisoDeIaTest`
**Configurável?** O contato (RN-IAM-023) · **Origem.** 2026-09-19, decisão do dono

### RN-CNV-028 — O aviso é igual para todo desconhecido, não passa pelo modelo, e não vai para a equipe

**Enunciado.** Só `TELEFONE_DESCONHECIDO` recebe o aviso. Conta inativa, bloqueada, com perfil que exige
segundo fator ou de dispositivo é da equipe e não recebe. O texto só tem o nome e o contato do salão —
nada do que a pessoa escreveu, nada sobre o número dela.

**Motivo.** "Não atende clientes" não se aplica a quem é da equipe. E um texto único, montado sem modelo,
não pode ser induzido a dizer nada, nem conta se aquele número tem conta no salão.

**Onde é garantida.** `AvisarQuemNaoEDaEquipe` · `AvisoDeIa`.
**Rotinas.** RT-CNV-008 · **Teste.** `AvisoDeIaIT.equipe_nao_recebe`, `.botao_nao_avisa`,
`AvisoDeIaTest`
**Configurável?** Não · **Origem.** 2026-09-19

### RN-CNV-029 — "Já avisado" é a impressão com chave do salão e do número, nunca o número

**Enunciado.** `aviso_de_ia` guarda, por salão, um HMAC-SHA256 do salão junto com o número, com a chave
do cofre de campo. Único por salão: duas mensagens juntas do mesmo número novo disputam a linha, e sai
um aviso só. A aplicação só insere e lê. Registrar e enfileirar são a mesma transação.

**Motivo.** Um SHA-256 sem chave de telefone brasileiro se desfaz por força bruta em minutos. Com o salão
na conta, a mesma pessoa não deixa o mesmo rastro em dois salões. Na mesma transação, porque separados
uma queda deixaria o número marcado e ninguém avisado.

**Onde é garantida.** `CofreDeCampo.impressao` · `AvisosDeIaJdbc` (`MANDATORY`) · `V57`
(`um_aviso_por_numero`, `revoke update, delete`).
**Rotinas.** RT-CNV-008 · **Teste.** `AvisoDeIaIT.guarda_so_a_impressao`, `.contato_por_salao`,
`.aplicacao_nao_apaga`, `.avisado_uma_vez_sem_modelo`, `CofreDeCampoTest`
**Configurável?** Não · **Origem.** 2026-09-19, decisão do dono

### RN-CNV-030 — Uma volta do modelo por rajada: o telefone é a unidade, não a mensagem

**Enunciado.** As conversas pendentes de um mesmo telefone são reservadas juntas, viram uma entrada só
— as mensagens na ordem em que chegaram, separadas por quebra de linha — e uma volta só do modelo.
Responde a última; as anteriores ficam `JUNTADA` apontando para ela, no mesmo commit. Cabem 20
mensagens numa rajada: o que passar disso forma a rajada seguinte, na ordem. Toque de botão não
agrega: não passa pelo modelo, e dois toques trazem tokens diferentes.

**Motivo.** É assim que se usa WhatsApp: "oi", pausa, "queria marcar com a Ana", áudio. Três execuções
respondiam três vezes, a primeira sem saber que haveria uma segunda — o agente parece que não estava
prestando atenção, que é o que faz alguém desistir de falar com um robô. O custo é o efeito menor.
Uma linha por mensagem continua: concatenar na pendente que já existe derrubaria a unicidade por
`mensagem_id`, que é o que torna o reenvio da Meta inofensivo.

**Onde é garantida.** `ProcessadorDeInteracoes.RESERVAR` (CTEs `rajada`/`madura`/`escolhida`) ·
`ConcluirExecucao.Alvo` · `ExecucoesJdbc.juntar` · `V68` (`JUNTADA`, `respondida_por`).
**Rotinas.** RT-CNV-009 · **Teste.** `JanelaDeAgregacaoIT.rajada_vira_uma_volta_do_modelo`,
`.na_ordem_em_que_chegaram`, `.absorvidas_apontam_para_quem_respondeu`, `.botao_nao_espera`,
`.telefones_diferentes_nao_se_misturam`, `.enxurrada_tem_teto_de_tamanho`
**Configurável?** Não · **Origem.** 2026-09-27

### RN-CNV-031 — O silêncio se mede na caixa de entrada, não na fila de execuções

**Enunciado.** A rajada só é respondida quando nada chegou daquele número dentro da janela
(`app.agente.janela-de-agregacao`, padrão 15s) **e** nenhum áudio recente dele ainda está sendo
baixado ou transcrito. Ambas as perguntas são feitas a `mensagem_recebida`.

**Motivo.** O áudio é o caso difícil: ele já é mensagem recebida e só vira execução quando a
transcrição termina (RT-CNV-003). Medir o silêncio na fila responderia o texto antes de o áudio
existir, e ele viraria uma resposta solta — exatamente o que esta rotina veio corrigir. É por isso que
a role de plataforma passou a ler `mensagem_recebida`, e só as colunas que dizem QUE algo chegou:
`texto` e `midia_id` ficaram fora do grant.

**Onde é garantida.** `ProcessadorDeInteracoes.RESERVAR` (CTE `madura`) · `V68` (política
`manutencao` e grant por coluna em `mensagem_recebida`).
**Rotinas.** RT-CNV-009 · **Teste.** `JanelaDeAgregacaoIT.espera_o_audio_virar_texto`,
`.sozinha_nao_espera_alem_da_janela`
**Configurável?** A janela · **Origem.** 2026-09-27

### RN-CNV-032 — Nenhuma espera é infinita: o teto vence a janela

**Enunciado.** Contado de quando a rajada começou, `app.agente.teto-de-espera` (padrão 2min) torna a
rajada respondível mesmo sem silêncio e mesmo com áudio em transcrição. Um áudio parado há mais que o
teto deixa de ser considerado: ele não cala o agente para sempre.

**Motivo.** Três modos de falha com o mesmo desfecho — transcrição travada, provedor fora do ar, e
quem não para de escrever — deixariam o agente mudo. Responder com o que se tem é pior que responder
completo e muito melhor que não responder; a transcrição que chegar depois vira uma execução nova, que
é o comportamento anterior a esta rotina.

**Onde é garantida.** `ProcessadorDeInteracoes.RESERVAR` (`r.comecou_em <= :vencido_em`).
**Rotinas.** RT-CNV-009 · **Teste.** `JanelaDeAgregacaoIT.transcricao_travada_nao_segura_alem_do_teto`,
`.quem_nao_para_de_escrever_recebe_no_teto`
**Configurável?** O teto · **Origem.** 2026-09-27
