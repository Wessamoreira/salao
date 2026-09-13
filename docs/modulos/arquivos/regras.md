# Regras de negócio — módulo `arquivos`

---

### RN-ARQ-001 — O tipo sai dos bytes, e é ele que sai na resposta

**Enunciado.** O tipo de um arquivo é determinado pelos primeiros bytes, na confirmação, contra uma
lista fechada (JPEG, PNG, HEIC, WEBP, PDF; e desde RT-CNV-003, OGG, MP3, M4A e AMR — só para dono
`MENSAGEM`, ver RN-ARQ-005). Extensão e `Content-Type` enviados são ignorados. O
download **serve** com o `Content-Type` detectado e `Content-Disposition: attachment`.

**Motivo.** Extensão e `Content-Type` são escolhidos por quem envia: `foto.jpg` com `image/jpeg` é
HTML com script sempre que o autor quiser. `05-seguranca` já exigia não confiar em nenhum dos dois.

As duas metades são obrigatórias. Magic bytes sozinhos não param arquivo poliglota — um binário que
é JPEG válido *e* HTML válido passa na detecção e executa se o navegador o receber como
`text/html`. A detecção diz o que entrou; o cabeçalho de saída decide o que o navegador faz com
isso.

SVG é recusado apesar de ser imagem: é XML com `<script>` dentro, e servido de um domínio do
sistema executa naquele domínio.

**Onde é garantida.** `MagicBytes.detectar` · `arquivo.tipo` preenchido só em `confirmar` ·
`TipoDeArquivo.contentType`.
**Rotinas.** RT-ARQ-001 · **Teste.** `MagicBytesTest` (8) ·
`UploadDeArquivoIT.caminho_completo`, `UploadDeArquivoIT.html_disfarcado_e_recusado`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ARQ-002 — A chave é gerada, nunca derivada do nome enviado

**Enunciado.** A chave no armazenamento é `{estabelecimento}/{dono}/{uuid}`, gerada pelo sistema. O
nome enviado é guardado em `nome_original`, só para exibir, e nunca compõe caminho nem decide tipo.

**Motivo.** Nome de arquivo vem do cliente e aceita `../`: derivar a chave dele faria o objeto de um
salão apontar para fora do prefixo daquele salão. E como a chave carrega o tenant, dois salões que
subam `foto.jpg` no mesmo segundo nunca colidem — o que unicidade por nome não garantiria.

**Onde é garantida.** `UploadDeArquivoUseCase.autorizar` · `arquivo.chave unique` ·
`ArmazenamentoLocal.caminhoDe` recusa caminho que escape da raiz ·
`ConteudoLocalController` recusa chave que não comece pelo tenant atual.
**Rotinas.** RT-ARQ-001 · **Teste.** `UploadDeArquivoIT.chave_nao_vem_do_nome`,
`UploadDeArquivoIT.chave_por_salao`, `ArquivosWebIT.chave_de_outro_salao`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ARQ-003 — Recusa vira registro e apaga os bytes — e não lança

**Enunciado.** Arquivo que não passa na validação recebe status `RECUSADO` com o motivo, e o objeto
é apagado do armazenamento. `confirmar` **devolve** o arquivo recusado; não lança exceção.

**Motivo.** Duas coisas, e a segunda foi um defeito real.

O registro existe porque *"por que a foto não subiu?"* precisa de resposta, e porque tentativa de
subir conteúdo estranho é o que a auditoria vai querer olhar. Os bytes somem porque deixá-los
manteria no bucket um objeto que o sistema já sabe que não deveria existir, com a URL funcionando.

E não lança porque **lançar desfazia o registro**: `confirmar` é `@Transactional`, e a exceção fazia
rollback do próprio `UPDATE` que gravava a recusa — os bytes eram apagados e a linha voltava a
`AGUARDANDO`. Gravar a recusa e lançar são incompatíveis na mesma transação; entre as duas saídas, a
que informa é a que devolve.

É a mesma forma de `SerieCriada.naoCouberam` (RN-AGD-012) e de `BLOQUEADA` (RN-NOT-001): o sistema
fez o que devia, e a resposta é "não".

**Onde é garantida.** `UploadDeArquivoUseCase.recusar` · `ArquivosController.confirmar` responde
200 também na recusa.
**Rotinas.** RT-ARQ-001 · **Teste.** `UploadDeArquivoIT.html_disfarcado_e_recusado`,
`ArquivosWebIT.recusa_responde_200`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ARQ-004 — Nasce aguardando, e o que nunca é confirmado não vale

**Enunciado.** A linha é criada **antes** do arquivo existir, com status `AGUARDANDO`, e só passa a
`VALIDADO` na confirmação. Confirmar sem que nada tenha chegado é erro (`ER-ARQ-NADA_FOI_ENVIADO`),
não recusa, e a linha continua aguardando. Confirmar duas vezes é conflito.

**Motivo.** Com upload direto, a aplicação não vê os bytes passarem — a linha criada antes é o que
devolve o controle: ela dá a chave, guarda quem pediu, e existe para ser confirmada. Sem ela, o que
chegasse ao bucket seria objeto órfão.

E abandono não é rejeição. `RECUSADO` significa "vimos e não serve"; marcar como recusa quem só
trocou de ideia encheria de ruído exatamente a lista que a auditoria consulta.

**Onde é garantida.** `arquivo.status default 'AGUARDANDO'` ·
`UploadDeArquivoUseCase.confirmar` · `idx_arquivo_aguardando`.
**Rotinas.** RT-ARQ-001 · **Teste.** `UploadDeArquivoIT.nada_enviado`,
`UploadDeArquivoIT.confirmar_duas_vezes`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ARQ-005 — O tipo tem de caber no dono

**Enunciado.** Reconhecer o formato não basta: ele precisa ser aceito **para aquele dono**. Cliente
e profissional aceitam JPEG, PNG, HEIC, WEBP e PDF. `MENSAGEM` aceita OGG, MP3, M4A e AMR. Dono
desconhecido não aceita nada. O banco faz a mesma pergunta (`arquivo_tipo_coerente_com_o_dono`).

**Motivo.** Com áudio na lista de formatos reconhecidos, uma lista só abriria o upload de **foto de
cliente** para OGG: o áudio seria reconhecido pelos bytes e aceito no lugar errado, numa ficha que a
tela desenha como imagem.

Detecção e aceitação são perguntas diferentes, e é por isso que `MagicBytes` continua dizendo só o
que o arquivo é. MP4 genérico (`isom`, `mp42`) não passa por M4A porque também é vídeo, e AAC cru
não passa por MP3 — a camada `00` do quadro é o que os separa.

**Onde é garantida.** `TiposPorDono` · `UploadDeArquivoUseCase.confirmar` · `IngestaoDeArquivo` ·
`V52`.
**Rotinas.** RT-CNV-003 · **Teste.** `UploadDeArquivoIT.audio_nao_e_anexo_de_cliente`,
`MagicBytesTest.audios`, `MagicBytesTest.nao_confunde_audio`
**Configurável?** Não · **Origem.** 2026-09-13

---

### RN-ARQ-006 — Ingestão pelo servidor: sem permissão, sem porta, e com as mesmas garantias

**Enunciado.** Bytes que o próprio servidor obteve entram por `IngestaoDeArquivoApi.guardar`, num
passo só: tipo pelos bytes, tipo coerente com o dono, teto de 20 MB, e recusa devolvida como arquivo
`RECUSADO` sem lançar. A linha é registrada **antes** dos bytes serem gravados. Não há
`@PreAuthorize` e nenhum controller pode depender dela.

**Motivo.** O upload em dois passos existe porque o navegador escreve direto no armazenamento e a
aplicação não vê os bytes passarem. Aqui o servidor baixou o áudio e os bytes estão em memória —
dois passos seriam cerimônia. O que não pode mudar é o que o módulo existe para garantir.

Registrar antes de gravar: se gravar falhar, a exceção desfaz o registro. A ordem contrária deixaria
bytes órfãos no disco a cada falha de banco, sem linha nenhuma dizendo que eles existem.

Sem permissão porque quem chama é um job, sem usuário. A garantia de que nenhuma tela alcance isto é
o teste de arquitetura: um controller usando a ingestão seria upload sem tranca.

**Onde é garantida.** `IngestaoDeArquivo` ·
`ArquiteturaTest.ingestao_de_arquivo_nao_tem_porta_http`.
**Rotinas.** RT-CNV-003 · **Teste.** `TranscricaoDeAudioIT.caminho_completo`,
`TranscricaoDeAudioIT.bytes_que_nao_sao_audio`
**Configurável?** Não · **Origem.** 2026-09-13
