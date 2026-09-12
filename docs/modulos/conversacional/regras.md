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
