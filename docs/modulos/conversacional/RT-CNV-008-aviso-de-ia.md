---
id: RT-CNV-008
titulo: "Aviso de IA ao cliente na primeira interação (LGPD)"
modulo: conversacional
fase: 4
perfil: completo
status: implementado
depende_de: [RT-CNV-002, RT-CNV-006, RT-CFG-001]
permissoes: [config:manage]
eventos: [MensagemDeClienteRecebida]
regras: [RN-CNV-027, RN-CNV-028, RN-CNV-029, RN-IAM-023]
atualizado_em: 2026-09-19
---

# RT-CNV-008 — O aviso a quem não é da equipe

## 1. Objetivo

Quem não é da equipe e escreve para o WhatsApp do salão recebe, **uma vez**, uma mensagem fixa: este
número é atendido por um assistente de inteligência artificial que trabalha só para a equipe, ninguém
lê o que chega aqui, e é assim que se fala com o salão.

É a exigência de transparência sobre IA de `docs/05` (LGPD), no formato que a v1 permite: a cliente
não conversa com o agente (pergunta 8), então a "primeira interação com o agente" é a primeira
mensagem dela para este número — e até aqui a resposta era silêncio.

## 2. As decisões — do dono, em 19/09/2026

| Pergunta | Decisão |
|---|---|
| O que é o aviso? | **Resposta fixa, uma vez.** Sem modelo, igual para todo mundo |
| Para onde ele encaminha? | **Contato configurado pelo salão** — telefone ou link. Sem ele, a mensagem diz só que o número é da equipe e que ninguém lê o que chega |
| Com que frequência? | **Uma vez por número, por salão.** Guarda-se só uma impressão do número, nunca o número |

## 3. O caminho

1. A mensagem chega e é gravada (RT-CNV-001); `MensagemDeClienteRecebida` sai pelo outbox.
2. `AvisarQuemNaoEDaEquipe` ouve o evento — **qualquer tipo**: texto, áudio, imagem. Áudio conta: a
   primeira mensagem de muita cliente é um áudio, e ele esperaria um provedor de transcrição que não
   existe.
3. Resolve a identidade pelo telefone, agora (RN-IAM-019). Só `TELEFONE_DESCONHECIDO` segue.
4. Numa transação: grava a impressão em `aviso_de_ia` (`on conflict do nothing`); se gravou agora,
   enfileira o aviso na saída do número (`mensagem_para_equipe`, motivo `AVISO_DE_IA`), com a
   mensagem de origem como `origem_id`.
5. O despachante envia como **resposta**: a cliente acabou de escrever, então a janela de 24h da Meta
   está aberta e não é preciso modelo aprovado.

**Não depende do agente.** O processador de conversas não reserva nada sem LLM configurado — que é a
situação de hoje. O aviso não pode esperar por isso, e por isso nasce na chegada da mensagem, não no
processador.

## 4. Só para quem não é da equipe — RN-CNV-028

- **Só `TELEFONE_DESCONHECIDO`.** Número de conta inativa, bloqueada, de perfil que exige segundo
  fator ou de dispositivo **é da equipe**, e o aviso ("não atende clientes") não se aplica. O motivo da
  recusa continua não sendo dito a ninguém: o aviso é igual para todo desconhecido, e só o dono do
  número consegue escrever dele.
- **Toque de botão não conta.** Botão só existe em mensagem que o salão mandou — é resposta ao pedido
  de aceite (RT-CNV-006), e quem toca é a profissional, não uma cliente puxando conversa.
- **Sem modelo.** O texto é montado por `AvisoDeIa`, com o nome do salão e o contato — nada do que a
  pessoa escreveu entra nele.

## 5. Uma vez, pela impressão — RN-CNV-029

`aviso_de_ia` guarda `(estabelecimento_id, impressao)` único. A impressão é um **HMAC-SHA256 com a
chave do cofre de campo** (`CofreDeCampo.impressao`), sobre o salão junto com o número — não um hash
simples: telefone brasileiro tem algumas centenas de milhões de valores possíveis, e um SHA-256 sem
chave se desfaz por força bruta em minutos. Sem a chave, a tabela não diz quem escreveu; com o salão na
conta, a mesma pessoa não deixa o mesmo rastro em dois salões.

- **Por salão.** Quem foi avisado pelo salão A e escreve para o salão B é avisado de novo — são
  números diferentes, e ela não sabe que os dois usam o mesmo sistema.
- **Duas mensagens juntas** do mesmo número novo disputam a mesma linha: uma grava, a outra não, e sai
  um aviso só.
- **A chave do cofre trocou?** As impressões antigas deixam de casar, e cada pessoa é avisada mais uma
  vez. Aceitável — é o lado inofensivo de errar.

## 6. O texto

Com contato:

> Mensagem automática do **Studio Marilda**: este WhatsApp é atendido por um assistente de
> inteligência artificial que trabalha só para a equipe do salão. Ele não atende clientes, e ninguém
> lê as mensagens enviadas para cá.
>
> Para falar com o salão: (11) 3456-7890

Sem contato, a última linha não existe. O link sai como foi cadastrado (`https://…`); o telefone,
formatado.

## 7. O contato para clientes — RN-IAM-023

Novo campo da configuração do salão (RT-CFG-001), **opcional**: *"Como clientes falam com o salão"*.

- **Telefone**, normalizado como qualquer telefone do sistema (com o DDD padrão do salão).
- **Ou link `https://`** — página de agendamento, perfil, `wa.me` de outro número. `http://` é
  recusado: é o link que a cliente toca, e ele não deveria poder ser trocado no caminho.
- Vazio é permitido: o aviso sai mesmo assim, sem a última linha.

## 8. Contrato de API

Só muda a configuração, que ganha um campo:

```http
PUT /api/v1/configuracao
Authorization: Bearer <token-com-mfa>
Content-Type: application/json

{ "nome": "Studio Marilda", "documento": null, "fusoIana": "America/Sao_Paulo", "moeda": "BRL",
  "baseComissao": "BRUTO", "descontoAfetaComissao": false, "periodicidadeDeFechamento": "MENSAL",
  "dddPadrao": "11", "contatoParaClientes": "(11) 3456-7890" }
```

**200** devolve a configuração com `"contatoParaClientes": "551134567890"`.
**422** `ER-IAM-DADOS_INVALIDOS` para `http://…` ou para algo que não é telefone nem link.

## 9. Dados

- `V57`: `estabelecimento.contato_para_clientes`; tabela `aviso_de_ia` com RLS, só `insert` e
  `select` para a aplicação; motivo `AVISO_DE_IA` na saída.

## 10. Observabilidade

`conversacional_aviso_de_ia_total{desfecho}` — `enfileirado`, `ja_avisado`, `equipe`. Enfileirado, e não
enviado: quem manda é o despachante da saída, e a entrega aparece nas métricas dele. Nada de telefone
em log.

## 11. Testes obrigatórios

**`AvisoDeIaIT`** (8), pelo caminho real do WhatsApp e com o listener assíncrono de verdade. Para
afirmar que alguém **não** foi avisado, espera a publicação do outbox daquele salão ser concluída — olhar
antes provaria só que o listener ainda não tinha rodado:
`avisado_uma_vez_sem_modelo`, `audio_avisa`, `equipe_nao_recebe` (recepção e dona, que o canal recusa
por segundo fator), `botao_nao_avisa`, `contato_por_salao` (e a impressão difere entre salões),
`guarda_so_a_impressao` (nem o número, nem SHA-256 simples), `aplicacao_nao_apaga`,
`sai_como_resposta`.

**Unidade** — `AvisoDeIaTest` (4), `CofreDeCampoTest` (5, os primeiros do cofre),
`DadosDoEstabelecimentoTest` (+5, o contato). **Configuração** — `ConfiguracaoIT.contato_para_clientes`.
**Front** — `Configuracao.test.tsx` (+3): o campo explica onde aparece, o telefone gravado volta
formatado, vazio vai como nulo.

`SaidaParaEquipeIT.recusada_nao_responde` mudou de sentido e ficou mais preciso: continua provando que
o **agente** não responde a quem foi recusado, e ignora o aviso, que é deste outro caminho.

## 12. Mutações verificadas

Uma rodada, 14 mutações, cada uma sozinha e contra os testes que deveriam matá-la. **14 mortas**, cada
uma por exatamente o teste escrito para ela.

| # | Mutação | Morta por |
|---|---|---|
| N01 | toque de botão avisa | `AvisoDeIaIT.botao_nao_avisa` |
| N02 | toda recusa de identidade avisa, não só o desconhecido | `AvisoDeIaIT.equipe_nao_recebe` |
| N03 | avisa a cada mensagem, não uma vez | `AvisoDeIaIT.avisado_uma_vez_sem_modelo` |
| N04 | impressão sem o salão | `AvisoDeIaIT.contato_por_salao` |
| N05 | aviso sem o contato | `AvisoDeIaIT.contato_por_salao` |
| N06 | aviso despachado como iniciado pelo salão | `AvisoDeIaIT.sai_como_resposta` |
| N07 | a aplicação pode apagar quem já foi avisado | `AvisoDeIaIT.aplicacao_nao_apaga` |
| N08 | `http://` aceito | `DadosDoEstabelecimentoTest.contato_http_recusado` |
| N09 | link sem host de verdade aceito | `DadosDoEstabelecimentoTest.contato_invalido` |
| N10 | contato sem teto de tamanho | `DadosDoEstabelecimentoTest.contato_invalido` |
| N11 | telefone do contato sem o DDD do salão | `DadosDoEstabelecimentoTest.contato_telefone_normalizado` |
| N12 | telefone cru, sem formatar, no aviso | `AvisoDeIaTest.contato_telefone` |
| N13 | o aviso não diz que é IA | `AvisoDeIaTest.diz_as_tres_coisas` |
| N14 | o contato não é gravado | `ConfiguracaoIT.contato_para_clientes` |

Duas lacunas apareceram **ao planejar** a rodada e viraram asserção antes de rodar:

- **N04 sobreviveria.** Sem o salão na impressão, cada salão continuaria avisando uma vez — a unicidade
  é por salão —, e nenhum teste perceberia que a mesma pessoa passou a deixar o mesmo rastro em dois
  salões. `contato_por_salao` agora compara as impressões.
- **N08 sobreviveria.** Sem a recusa própria, `http://…` cairia na leitura de telefone e seria recusado
  do mesmo jeito — com uma mensagem que também contém "https://", que era o que o teste olhava. A
  asserção agora é a mensagem específica.

**Equivalente, e por isso fora da rodada:** `AvisosDeIaJdbc.registrar` com `REQUIRED` em vez de
`MANDATORY`. Hoje ele só é chamado dentro da transação do listener; `MANDATORY` é a defesa contra quem o
chamar fora dela, separando a marca do aviso.

## 13. Pendências

- [ ] **Sem provedor de envio, o aviso espera** na fila, como tudo o que sai (RT-CNV-006). Se esperar
      mais de 24h, a janela da Meta fecha, texto livre deixa de ser aceito, e ele falha depois das
      tentativas. Aceitável: a pessoa escreveu há mais de um dia.
- [ ] **A profissional sem conta que escrever texto** (não tocar botão) recebe o aviso uma vez. O texto
      é verdadeiro para ela também — o assistente não a atende —, e conferir o cadastro da equipe a cada
      desconhecido cruzaria o módulo por um caso raro.
- [ ] **Exclusão a pedido (LGPD):** a impressão não se liga à pessoa sem a chave, mas quem pedir pode
      ter a sua recalculada e apagada. Não há rota para isso ainda.
- [ ] **Trocar a chave do cofre** faz cada número ser avisado mais uma vez.
- [ ] **`aviso_de_ia` não expira**, por decisão ("uma vez por número"). Rever se o volume pedir.
- [ ] **`mensagem_para_equipe` leva agora uma mensagem que não é para a equipe.** O nome ficou do
      começo; renomear é mudança de schema e de código sem ganho de comportamento.
