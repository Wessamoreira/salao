---
id: RT-ARQ-001
titulo: Upload por presigned URL + validação de magic bytes
modulo: arquivos
fase: 1
perfil: completo
status: implementado
depende_de: [RT-INF-002]
permissoes: [cliente:escrever, usuario:gerenciar]
eventos: []
regras: [RN-ARQ-001, RN-ARQ-002, RN-ARQ-003, RN-ARQ-004, RN-INF-014]
atualizado_em: 2026-09-05
---

# RT-ARQ-001 — Upload de arquivo

## 1. Objetivo

Deixar entrar a foto do cabelo e o PDF do contrato — e **só** eles.

## 2. Dois passos, porque os bytes não passam pela aplicação

Com presigned URL o navegador escreve direto no armazenamento. É o ponto: uma foto de celular tem
alguns megabytes, e proxiá-la queima memória e prende uma thread por upload — num salão em que
cinco pessoas mandam foto ao mesmo tempo, isso é a aplicação inteira parada por causa de anexo.

O preço é perder o controle do momento da escrita, e é a **confirmação** que o devolve:

| Passo | O que faz |
|---|---|
| `autorizar` | Cria a linha, gera a chave, devolve a URL de escrita (15 min) |
| *(navegador)* | Manda os bytes direto para o armazenamento |
| `confirmar` | Lê os primeiros bytes, decide o tipo, e aceita ou recusa |

A linha existe **antes** do arquivo de propósito. Sem ela, o que chegasse ao bucket seria um objeto
órfão: ninguém saberia de quem é, nem se alguém chegou a pedir para enviá-lo.

## 3. O tipo sai dos bytes

`05-seguranca` já exigia: *"não confiar em extensão nem em `Content-Type`"*. Os dois são escolhidos
por quem envia — `foto.jpg` com `image/jpeg` pode ser HTML com script.

Os primeiros bytes, não. São o formato de verdade, porque é por eles que qualquer programa decide
como ler o arquivo. `MagicBytes` confere JPEG, PNG, PDF, WEBP e HEIC, e devolve vazio para
qualquer outra coisa.

Dois detalhes que separam a conferência de verdade da conferência de fachada:

- **WEBP não é só `RIFF`.** A marca `WEBP` está no byte 8. Conferir só o começo aceitaria um WAV
  como imagem, porque WAV também é RIFF.
- **HEIC tem várias marcas.** `heic`, `heix`, `mif1`, `msf1` — aceitar só `heic` recusaria metade
  das fotos que chegam de iPhone, e a recusa seria inexplicável para quem enviou.

E **SVG é recusado**. É a exceção que parece errada: SVG é imagem em toda tela onde aparece, mas é
XML com `<script>` dentro. Servido de um domínio do sistema, ele executa naquele domínio.

## 4. O que magic bytes **não** resolvem

Arquivo poliglota: dá para construir um arquivo que é JPEG válido *e* HTML válido. Os primeiros
bytes passam, e o navegador que o receba como `text/html` executa o que vem depois.

A defesa contra isso não está na detecção — está em **servir** o arquivo com o `Content-Type`
**detectado** e `Content-Disposition: attachment`. Magic bytes dizem o que entrou; o cabeçalho de
saída decide o que o navegador faz com isso. Precisa dos dois, e é por isso que `tipo` é gravado na
confirmação e é ele que o download usa.

## 5. A chave é gerada, nunca derivada do nome

`nome_original` existe só para exibir. A chave é `{tenant}/{dono}/{uuid}`.

Nome de arquivo vem do cliente e aceita `../`: derivar a chave dele faria o objeto do salão A
apontar para fora do prefixo do salão A. E como a chave carrega o tenant, dois salões que subam
`foto.jpg` no mesmo segundo nunca colidem — o que a unicidade por nome não garantiria.

`ArmazenamentoLocal` ainda normaliza o caminho e recusa qualquer um que escape da raiz. Cinto e
suspensório: a chave já é segura por construção, mas quem escreve no disco não deveria confiar
nisso.

## 6. Recusa é resultado, não exceção

`confirmar` devolve o arquivo com status `RECUSADO` e o motivo. Não lança.

Isso começou como bug: a primeira versão gravava a recusa e lançava `ErroDeDominio` na linha
seguinte. Como `confirmar` é `@Transactional`, a exceção fazia **rollback do próprio registro da
recusa** — os bytes eram apagados do disco e a linha voltava a `AGUARDANDO`. O sistema fazia a
coisa certa e não sobrava nenhum vestígio de que tinha feito.

Gravar a recusa e lançar são incompatíveis na mesma transação. E, entre as duas saídas, a que
informa é a que devolve — mesma forma de `SerieCriada.naoCouberam` (RT-AGD-009) e de `BLOQUEADA`
(RT-NOT-001): **o sistema fez o que devia, e a resposta é "não"**.

Recusado, o arquivo é **apagado** do armazenamento. Deixá-lo lá manteria no bucket do salão um
objeto que o sistema já sabe que não deveria existir — com a URL dele funcionando.

## 7. "Nada chegou" não é recusa

Pedir o link e não enviar nada é upload que **não aconteceu**, não upload rejeitado: a linha
continua `AGUARDANDO` e `confirmar` responde `ER-ARQ-NADA_FOI_ENVIADO`.

A distinção não é preciosismo. `RECUSADO` significa "vimos e não serve" e é o que a auditoria olha
quando alguém pergunta se tentaram subir coisa estranha. Marcar abandono como recusa encheria essa
lista de gente que só trocou de ideia.

## 8. Permissão: a do dono, não uma de "upload"

Subir foto de cliente exige poder escrever na ficha dela. Uma permissão própria separaria o arquivo
do dado a que ele pertence — e **foto de química é dado de saúde** (RT-CLI-004), não anexo
genérico. Quem não pode ver a ficha não pode anexar nela.

## 9. A porta: três pedidos, e a recusa responde 200

| | Pedido | Resposta |
|---|---|---|
| 1 | `POST /api/v1/arquivos` | 201 com `arquivoId` e `urlDeUpload` |
| 2 | *(para onde o passo 1 mandou)* | 204 |
| 3 | `POST /api/v1/arquivos/{id}/confirmacao` | 200 com `status`, `tipo` e `motivoRecusa` |

**Recusa responde 200.** O pedido estava certo; o que não serve é o conteúdo. Vem com
`status: RECUSADO` e o motivo no corpo de sempre — consequência direta da §6, porque transformar a
recusa em erro HTTP exigiria lançar, e lançar desfaria o registro.

**A resposta não devolve a `chave`.** Ela é endereço de armazenamento e não expira; o que a tela
precisa é URL de leitura com validade.

**O passo 2 é a porta que vai deixar de existir.** Com bucket, o navegador escreve direto no
provedor — é o ponto inteiro do presigned URL. Enquanto não há, `ConteudoLocalController` recebe os
bytes, e sai com `app.arquivos.modo=objeto` no dia em que houver. Ela confere que a chave começa
pelo tenant de quem está pedindo — autenticado o usuário está; o que ele não tem é aquele
salão.

Foi isso que fez `guardar` sair do port `Armazenamento` para um `EscritaDeConteudo` próprio. A
alternativa era um `guardar` no port principal que a implementação de bucket teria de existir só
para recusar — e o teste de arquitetura, que proíbe `..web..` depender de `..infra..`, apontou o
mesmo lugar por outro caminho.

## 10. O mesmo `wasNull` de novo — e o que se fez desta vez

Ler `tamanho_bytes` aqui repetiu, letra por letra, o defeito de RT-CAT-004: `getLong` seguido de
`wasNull()` na posição errada, com `wasNull` falando da coluna anterior. O comentário que explicava
o problema estava escrito quinze arquivos ao lado, e não impediu nada.

Um comentário não impede o erro; uma função que não tem como ser usada errado, sim. Virou
`Colunas.inteiroOuNulo` / `Colunas.longoOuNulo` — com as duas leituras já existentes migradas — e
RN-INF-014, com teste de arquitetura que proíbe `ResultSet.wasNull` fora de `Colunas`.

## 11. Testes

- [x] `MagicBytesTest` — 8: reconhece os cinco permitidos · **as marcas de HEIC do iPhone** ·
      **um WAV não passa por WEBP só porque começa com RIFF** · HTML disfarçado de foto ·
      **SVG recusado** · ZIP recusado · vazio e curto demais · o Content-Type devolvido é o detectado
- [x] `UploadDeArquivoIT` — 7: caminho completo e o tipo saindo dos bytes · **a chave é gerada, não
      derivada do nome** · **HTML com nome de foto é recusado E apagado** · confirmar sem ter
      enviado não é recusa · confirmar duas vezes · **um salão não vê o arquivo do outro** ·
      a chave carrega o salão, então dois salões nunca colidem
- [x] `ArquivosWebIT` — 6: **os três pedidos se encaixam** · **recusa responde 200 com o motivo** ·
      a resposta não devolve a chave · **escrever numa chave de outro salão é recusado** · dono de
      tipo desconhecido para na borda · sem token não entra em nenhuma das duas rotas

## 12. Pendências

- [ ] **O armazenamento é o disco local.** `ArmazenamentoLocal` cumpre o port inteiro, mas
      `urlDeUpload` devolve um caminho, não uma URL assinada de verdade. Trocar por S3/R2 é
      implementar o port — e é onde a assinatura passa a valer de fato
- [ ] **Ninguém expurga o que ficou `AGUARDANDO`.** O índice parcial existe para isso e o job não.
      Hoje, todo link pedido e não usado vira linha permanente
- [ ] **O download não existe.** `urlDeLeitura` está no port e não tem chamador — e é ele que
      precisa servir com o `Content-Type` detectado e `attachment` (seção 4). **Enquanto o download
      não for escrito, a defesa contra poliglota está pela metade**
- [ ] **A anonimização não alcança os arquivos.** RN-CLI-007 apaga o dado da cliente; a foto dela
      continua no bucket. O índice por dono existe justamente para essa varredura
- [ ] **Antivírus não passa aqui.** Magic bytes dizem o formato, não dizem se o PDF tem payload
- [ ] **`app.arquivos.modo` é uma chave que alguém precisa lembrar de virar.** O bean de
      armazenamento e a porta local de escrita são desligados por caminhos diferentes; esquecer o
      segundo deixa uma rota de escrita aberta que nada mais usa. Merece teste de arquitetura
      quando o provedor real entrar
- [ ] **Não há tela.** O front não tem componente de upload; a rotina está completa do lado do
      servidor e não aparece em lugar nenhum ainda
- [ ] **O limite de 20 MB é conferido duas vezes e tarde.** A porta local barra na escrita e a
      confirmação barra de novo; com bucket, só a segunda sobra — e aí o arquivo já subiu. A
      presigned URL de verdade permite limitar na origem
