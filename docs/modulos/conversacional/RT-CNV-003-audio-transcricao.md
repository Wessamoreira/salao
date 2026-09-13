---
id: RT-CNV-003
titulo: Mídia de áudio → download → STT → transcrição guardada
modulo: conversacional
fase: 4
perfil: completo
status: implementado
depende_de: [RT-CNV-001, RT-ARQ-001, RT-NOT-001, RT-INF-006]
permissoes: []
eventos: [MensagemDeClienteRecebida]
regras: [RN-CNV-006, RN-CNV-007, RN-CNV-008, RN-CNV-009, RN-ARQ-005, RN-ARQ-006]
atualizado_em: 2026-09-13
---

# RT-CNV-003 — Áudio para transcrição

## 1. Objetivo

A cliente manda uma nota de voz; o sistema guarda o áudio e o transforma em texto, sem perder nada
quando a Meta, a rede ou o provedor falharem — e sem prender o banco enquanto espera.

## 2. O plano dizia M; é G, e o motivo é honesto

Quatro peças que o plano não enxergava:

| Peça | Por que foi necessária |
|---|---|
| a primeira chamada HTTP do sistema para fora | não existia cliente HTTP de saída no projeto |
| uma fila com worker | o listener do outbox prende conexão (seção 3) |
| áudio em `arquivos` | a lista branca era de anexo, e o dono `MENSAGEM` não existia |
| um *port* de STT sem provedor | a pergunta 14 (orçamento) está aberta |

## 3. Uma fila, e não o trabalho dentro do listener — RN-CNV-006

`@ApplicationModuleListener` abre transação antes do corpo do método. Com o
`TenantAwareTransactionManager`, isso pega uma conexão **na hora**. Baixar o áudio e transcrever ali
dentro seguraria uma conexão por dezenas de segundos a cada nota de voz: o gargalo de R-18,
provocado justamente pelo recurso mais usado do agente.

O `EnfileiradorDeAudios` só grava uma linha e sai. O `ProcessadorDeAudios` faz a rede fora de
transação:

```
reserva      → um comando (conexão de manutenção)
download     → sem transação
guardar      → transação curta, no escopo do salão
transcrição  → sem transação
concluir     → transação curta, no escopo do salão
```

Sendo o primeiro consumidor de `MensagemDeClienteRecebida`, é daqui que o outbox passa a guardar a
publicação — e a pendência de RT-CNV-001 fecha, com teste (`audio_entra_pelo_outbox`).

## 4. Duas etapas, duas credenciais

| Status | Falta | Credencial |
|---|---|---|
| `PENDENTE` | baixar | `WHATSAPP_TOKEN_ACESSO` |
| `BAIXADA` | transcrever | provedor de STT |
| `CONCLUIDA` / `FALHOU` | nada | — |

Sem a credencial de uma etapa, as linhas dela **esperam sem gastar tentativa**. Tratar credencial
ausente como falha gastaria as cinco tentativas em minutos e mataria justamente os áudios que
chegaram antes de alguém configurar.

As tentativas voltam a zero ao passar para `BAIXADA`: uma rede ruim no download não pode ter gasto
o orçamento do provedor de STT.

## 5. A primeira chamada para fora — RN-CNV-007

A Graph API responde o metadado com uma URL de conteúdo, e **exige o token também nela**. O risco
está aí: a URL vem na resposta, e se apontasse para outro host o token do app — que lê e manda
mensagem por todos os salões — iria junto.

| Defesa | Contra o quê |
|---|---|
| host por sufixo **com ponto** | `fbsbx.com.atacante.net`, `atacantefbsbx.com` |
| https obrigatório | token em texto claro |
| redirecionamento nunca seguido | um 302 leva o cabeçalho para onde a lista não olhou |
| id da mídia `[A-Za-z0-9_-]` | `../` montando outra rota com o token |
| teto no metadado **e** na leitura | o tamanho declarado é só uma afirmação |
| sha256 conferido | áudio adulterado virando transcrição, e agendamento |

A política entra pelo construtor e não por propriedade. O teste mais importante sobe **dois**
servidores HTTP — a "Meta" e um "host estranho" — e prova que o estranho recebe zero pedidos.

Cliente HTTP do próprio JDK, sem dependência nova.

## 6. Reserva atômica — RN-CNV-008

Um único `UPDATE ... RETURNING`, com `for update skip locked` na subconsulta, reserva e soma a
tentativa no mesmo comando. `reservada_ate` vence: a instância que morre no meio não prende a linha.

### O defeito que esta rotina encontrou em outro lugar

Ao estudar o `DespachanteDeMensagens` (RT-NOT-001) para copiar o padrão, a leitura mostrou que ele
faz `SELECT ... FOR UPDATE SKIP LOCKED` pela conexão de manutenção **em autocommit**. Em autocommit
o lock é solto no fim do próprio `SELECT`, antes de a mensagem sair: duas instâncias pegam a mesma
linha e a cliente recebe o lembrete duas vezes — exatamente o que o javadoc de lá diz impedir.
Registrado como tarefa à parte; esta rotina não repete o defeito.

## 7. O áudio é guardado, e a nossa cópia é a fonte — RN-CNV-009, RN-ARQ-005, RN-ARQ-006

O áudio entra em `arquivos` com dono `MENSAGEM`, e o tipo sai dos bytes: a Meta declara
`audio/ogg`, mas quem decide é `OggS` no começo do arquivo. Um PNG servido como áudio vira arquivo
`RECUSADO` e linha `FALHOU` no mesmo commit — sem exceção, pela lição de RN-ARQ-003.

Três mudanças em `arquivos`, e cada uma fecha uma porta que áudio abriria:

- **tipo por dono** (`TiposPorDono`): sem isso, um OGG legítimo subiria como foto de cliente;
- **ingestão pelo servidor** (`IngestaoDeArquivoApi`): um passo só, com as mesmas garantias, e sem
  porta HTTP — o teste de arquitetura reprova controller que a use;
- **`Armazenamento.ler`**: a retentativa do STT lê a nossa cópia, não a Meta, cuja mídia expira.

E o áudio é a prova: quando a transcrição errar "quinta" por "quarta", é ele que resolve.

## 8. Sem provedor de STT, de propósito

A pergunta 14 de `13-perguntas-em-aberto` — orçamento, com "STT de áudio ficou fora da conta
original e não é barato" — não foi respondida. Escolher fornecedor é decisão do dono.

`Transcritor` é um *port*; `TranscritorNaoConfigurado` diz a verdade (`configurado() = false`). Os
áudios ficam guardados em `BAIXADA`, e o dia em que um provedor entrar como `@Primary`, eles fluem.

## 9. O que foi extraído

`EsperaEntreTentativas` saiu de `notificacao` para `shared.tempo` (histórico preservado). É o
precedente de `Telefone` e `UsuarioAtual`: o segundo lugar precisou da mesma resposta.

## 10. Testes obrigatórios

- [x] `MidiaDoWhatsAppGraphTest` — 14, contra servidores HTTP de verdade: baixa com o token nos dois
  pedidos · **host estranho recebe zero pedidos** · política de produção (sufixo com ponto, https) ·
  **id com caminho recusado antes de qualquer pedido** · expirada é permanente · instável e fora do
  ar são transitórios · tamanho declarado acima do teto nem baixa · **teto vale na leitura** ·
  sha256 adulterado · sha256 em Base64 · **redirecionamento não seguido** · metadado ilegível · sem
  token.
- [x] `TranscricaoDeAudioIT` — 14: **entra na fila pelo outbox** · texto não entra · aviso repetido
  não duplica · **baixa, guarda como arquivo validado, e só depois transcreve** · sem token espera
  sem gastar tentativa · transitória adia com espera e solta a reserva · desiste na quinta ·
  permanente falha de vez · **bytes que não são áudio: recusados e registrados** · **retentativa usa
  a cópia guardada** · **reserva de outra instância não é tocada; vencida é retomada** · concluída
  não é reescrita · isolamento · aplicação não apaga.
- [x] `MagicBytesTest` — +2: áudio reconhecido · AAC cru não é MP3, MP4 genérico não é M4A.
- [x] `UploadDeArquivoIT` — +1: **áudio enviado como foto de cliente é recusado**.
- [x] `ArquiteturaTest.ingestao_de_arquivo_nao_tem_porta_http`.

### Mutações verificadas

Todos os testes passaram na primeira execução — e verde de primeira não prova que guardam alguma
coisa. Seis mutações aplicadas juntas, cada uma afetando um teste diferente, numa execução só.
Ficaram vermelhos **exatamente** os seis esperados, e nenhum outro:

| Linha quebrada de propósito | Teste que ficou vermelho |
|---|---|
| `PoliticaDeUrl.permite` aceitando qualquer URL | `token_nao_sai_do_perimetro`, `politica_de_producao` |
| `Redirect.NEVER` → `NORMAL` | `redirecionamento` |
| reserva sem `reservada_ate is null or reservada_ate < :agora` | `reserva` |
| recusa de áudio lançando `FalhaPermanente` dentro da transação | `bytes_que_nao_sao_audio` |
| `TiposPorDono.aceita` desligado no upload | `audio_nao_e_anexo_de_cliente` |

A quarta é a que mais importa lembrar: com a exceção, a linha continua `FALHOU` — o processador pega
a exceção e grava a falha noutra transação —, mas o arquivo `RECUSADO` some no rollback. Olhando só
a fila, pareceria certo.

## 11. Pendências

- [ ] **Escolher o provedor de STT.** É decisão sua (pergunta 14). Até lá, áudio é guardado e não
      transcrito.
- [ ] **Teto de custo por salão por dia (R-17).** Não existe. A cobrança é por minuto de áudio e o
      único limite hoje é o tamanho (16 MB de Opus são horas). O corte automático entra com a
      orquestração (RT-CNV-004), mas o custo nasce aqui — e sem provedor não há tabela de preço para
      calcular.
- [ ] **Retenção.** Nem o áudio nem a transcrição têm prazo. `04-modelo` prevê 90 dias para conversa
      e anonimização da transcrição de `acao_ia` aos 90 — e voz é dado pessoal.
- [ ] **A anonimização de cliente não alcança o áudio.** O dono é `MENSAGEM`, não `CLIENTE`, e
      RN-CLI-007 procura por cliente. A voz de uma cliente anonimizada continuaria guardada.
- [ ] **Os hosts da Meta foram tirados da documentação**, não de uma chamada real. Confirmar no
      primeiro uso com token de verdade — se a Meta servir de outro CDN, o download falha de vez, e
      a lista é o lugar a mudar.
- [ ] **Versão da Graph API (`v23.0`).** A Meta desliga versões em cerca de dois anos; está no
      runbook.
- [ ] **Codificação do sha256.** A documentação não diz; aceitam-se hexadecimal e Base64.
- [ ] **`FALHOU` não se reprocessa por tela.** O runbook tem o SQL.
- [x] ~~O texto ainda não chega ao agente.~~ Fechada em RT-CNV-004: `ConcluirTranscricao` conclui e
      enfileira a conversa no mesmo commit.
