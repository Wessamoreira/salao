---
id: RT-CNV-006
titulo: "IntencaoPendente: negociação com o profissional, TTL, botões"
modulo: conversacional
fase: 4
perfil: completo
status: implementado
depende_de: [RT-CNV-005, RT-NOT-001, RT-AGD-002, RT-EQP-001]
permissoes: [agenda:write:all]
eventos: [MensagemDeClienteRecebida]
regras: [RN-CNV-018, RN-CNV-019, RN-CNV-020, RN-CNV-021, RN-AGD-002, RN-EQP-016]
atualizado_em: 2026-09-14
---

# RT-CNV-006 — O aceite de quem vai atender, e a saída para a equipe

## 1. Objetivo

A recepção pede pelo agente um horário **fora da jornada** da Ana. Em vez de "não dá", o sistema
pergunta à Ana, pelo WhatsApp, com botões. Ela aceita e a marcação acontece; recusa, ou não responde a
tempo, e a recepção fica sabendo. E tudo o que o agente tem a dizer — respostas, pedidos de
confirmação, desfechos — passa a ter por onde sair.

## 2. As três decisões que definiram a rotina

A linha do plano diz só "negociação com o profissional, TTL, botões". Foram três perguntas ao dono do
produto, em 14/09/2026:

| Pergunta | Resposta | Consequência |
|---|---|---|
| Quando a profissional precisa aceitar? | **Só o que foge da regra** | Na prática, fora da jornada: encaixe na pausa o banco já aceita, e conflito de horário não se força |
| Como falar com ela? | **Telefone do cadastro** | Sem exigir conta de usuário. O aceite vale pelo botão e pelo número |
| Entregar também as respostas do agente? | **Sim, no mesmo canal** | A saída para a equipe nasce aqui, e serve a RT-CNV-004 e 005 |

## 3. O caminho

```
Bia: "marca corte da Marilda com a Ana às 19h"
  → agendar.simular: a simulação informa "fora da jornada" → aceite de Ana, pelo telefone do cadastro
  → intenção PENDENTE com aceite · pedido: "Confirma? ... Depois do seu SIM, Ana ainda precisa aceitar"

Bia: "SIM 4821"
  → AGUARDANDO_ACEITE, prazo de 2h · NADA gravado
  → saída: para Ana, "Bia pediu um horário fora da jornada: ... Você aceita?" [Aceitar] [Recusar]
  → para Bia: "Pedi a Ana para aceitar (fora da jornada). Aviso aqui assim que houver resposta."

Ana toca [Aceitar]
  → reconhecido pelo id do botão + o número de Ana · sem modelo, sem conta de usuário
  → em nome de BIA, resolvida agora: CriarAgendamentoUseCase.executar, forçando a jornada
    com a justificativa "Fora da jornada, aceito por Ana pelo WhatsApp (final 4321) em 20/09 às 14:32"
  → CONFIRMADA no mesmo commit · para Bia: "Ana aceitou. Marcado: ..." · para Ana: "Obrigado!"
```

## 4. Fora da jornada não é "não": é "pergunte a ela" — RN-CNV-018

`FerramentaAgendar.simular` só pede aceite quando o **único** problema é a jornada.
`CriarAgendamentoUseCase.simular` passou a **informar** a jornada (`AgendamentoSimulado.foraDaJornada`)
em vez de recusá-la, e todo o resto continua valendo: conflito, recurso, passado. Horário tomado fora
da jornada continua sendo recusado (`FerramentasDaAgendaIT.simular_fora_da_jornada_confere_o_resto`).

### Um defeito meu, que só o teste de ponta a ponta mostrou

A primeira versão fazia o óbvio: simular; se a recusa fosse de jornada, pegar a exceção e simular de
novo forçando. `FerramentasDaAgendaIT` passou. `AceiteDaProfissionalIT` falhou em 11 de 13 — e o log
que RT-CNV-005 acrescentou disse por quê, onze vezes: `Ferramenta agendar falhou
(UnexpectedRollbackException)`.

O agente simula dentro de uma transação somente leitura. A recusa atravessando o
`@Transactional` do caso de uso marca essa transação para rollback; pegar a exceção e seguir não
desmarca; o commit do fim estoura. O teste da agenda chamava a ferramenta sem transação por fora, e
cada simulação era a sua própria.

É a lição de RN-ARQ-003 de novo, por outro caminho: **recusa que quem chama precisa tratar volta como
dado, não como exceção.** A reserva de horário devolve o trecho fora da jornada, e só `montar` decide
se isso é recusa. Ganhou teste que reproduz a transação por fora
(`simular_fora_da_jornada_dentro_de_transacao`).

O contrato de escrita ganhou duas coisas:

- `Simulacao.aceite()` — quem, por qual número, por quê;
- `confirmar(..., Optional<AceiteRegistrado>)` — a prova do aceite. `agendar` só força a jornada com
  ela: sem a prova, recusa (`confirmar_fora_da_jornada_sem_aceite`).

**O SIM de quem pediu não grava.** A gravação acontece no toque de Ana — e **em nome de Bia**,
resolvida de novo naquele instante. Aceitar o horário não dá a ninguém permissão de marcar: se Bia
perdeu a permissão entre o SIM e o toque, nada grava (`permissao_retirada_de_quem_pediu`).

E o banco fecha a porta dos fundos: intenção que exigia aceite **só pode ser CONFIRMADA com o aceite
registrado** (`intencao_com_aceite_so_confirma_aceita`).

A justificativa que a tela exige para forçar a jornada (RN-AGD-002) vira o próprio aceite, e vai para a
trilha com os quatro últimos dígitos do número — nunca o número inteiro.

## 5. O botão vale pelo número, não pela identidade — RN-CNV-019

Ana não tem conta de usuário, e é o caso normal. Então o toque não passa pela identidade de RT-CNV-002.
O que ele precisa ter:

| Conferido | Como |
|---|---|
| o id do botão é um botão de aceite | `BotaoDeAceite.de` — formato exato, token de 16 a 64 caracteres |
| o token é o da intenção | comparação em tempo constante |
| o toque veio do número para onde o pedido foi | `aceite_de_telefone`, gravado na simulação |
| a intenção ainda espera aceite, e está no prazo | trava da linha |

Qualquer falha é "Não encontrei esse pedido" — sem dizer se a intenção existe. Outro número, o número
de quem pediu, token forjado: todos testados.

O token não é o que autentica — quem autentica é o número, atestado pela Meta. Ele existe para que um
id de botão copiado de um print não sirva sozinho, e para que a intenção não seja adivinhável.

**Reconhecido, nunca interpretado.** O toque não chega ao modelo, não conta custo, e **não entra no
histórico da conversa**: o id carrega o token (`SaidaParaEquipeIT.botao_fora_do_historico`).

A profissional mandando "SIM + código" não confirma nada: para o agente ela é um número desconhecido
(`profissional_mandando_o_codigo`).

## 6. A saída para a equipe — RN-CNV-020

`mensagem_para_equipe` é uma fila, com o padrão de RT-NOT-001 (gravar antes de enviar) e o de RN-CNV-008
(reserva atômica que vence):

- **Enfileirada no mesmo commit de quem a produziu.** `ConcluirExecucao` encerra a conversa e põe a
  resposta na saída juntas; `EfetivarIntencao` decide a intenção e avisa juntas. Separadas, uma queda
  deixaria a conversa encerrada e ninguém avisado.
- **A resposta antes do pedido de confirmação.** O pedido é lido ao encerrar a conversa — e só se ela
  terminou. Conversa que parou no teto não entrega pedido a quem não recebeu resposta
  (`teto_nao_entrega_pedido`); isso fecha uma pendência de RT-CNV-005.
- **Recusa não responde nada a ninguém** (RN-CNV-012).
- **Na ordem, por destino.** Uma mensagem só é reservada quando não há outra pendente, mais antiga,
  para o mesmo número.
- **Reserva atômica.** `UPDATE ... RETURNING` com a subconsulta travada — não o
  `SELECT ... FOR UPDATE SKIP LOCKED` solto do despachante da notificação, que em autocommit solta o
  lock no fim do próprio SELECT (`reserva_atomica`, com duas instâncias).
- **Imutável para a aplicação.** Ela só insere; quem muda o estado é o despachante, pela conexão de
  manutenção.

### Sem provedor, espera — e não finge

Não há provedor de envio contratado. `EnviadorNaoConfigurado` diz `configurado() = false`, e a fila
**espera sem gastar tentativa**, como o STT e o modelo.

Na pergunta ao dono, a opção falava em "o envio fica no registro (log)", como o `MensageiroDeRegistro`
da notificação — que registra e marca ENVIADA. Na implementação isso foi trocado, de propósito: uma
resposta marcada como enviada sem ter saído é a pior mentira desta fila. Esperando, a fila mostra o que
falta, e entrega tudo quando o provedor entrar.

### Por que o envio mora no conversacional, e não na notificação

ADR-0006 pôs o adaptador da Cloud API atrás de um port da `notificacao`. Não fecha: o número do salão
(`canal_whatsapp`) mora aqui, e um adaptador lá precisaria ler daqui enquanto este módulo mandaria por
lá — dependência circular. O dono do número é o dono do envio. ADR-0006 recebeu a nota.

## 7. O prazo — RN-CNV-021

Dez minutos para o SIM (RT-CNV-005); **duas horas para o aceite**, configurável, contadas do SIM. Fora
da jornada ela, por definição, não está trabalhando.

A validade do código se confere na hora do SIM. A do aceite não pode ser só assim: se Ana nunca tocar,
Bia nunca saberia. `ExpiradorDeAceites` passa a cada minuto e, **num comando só** — `UPDATE` dentro de
um `WITH` que alimenta o `INSERT` —, vence a intenção e enfileira o aviso. Em dois comandos, uma queda
entre eles deixaria a intenção vencida e ninguém avisado, sem rodada seguinte que a reencontrasse.

O toque depois do prazo, antes do expirador passar, vence e avisa uma vez — o índice único da saída
absorve o aviso em dobro (`toque_depois_do_prazo`).

## 8. O telefone da profissional — RN-EQP-016

A V17 dizia "só dígitos, como o telefone do cliente", e o cadastro gravava como fosse digitado — sem
problema enquanto ninguém lia. Agora é destino de mensagem: `CadastrarProfissionalUseCase` normaliza com
`shared.telefone.Telefone`, e número inválido é recusado apontando o campo.

`agendar` normaliza de novo ao ler, para o que foi cadastrado antes. O que não se normaliza fica sem
aceite possível, e a recusa diz por quê.

## 9. Dados

`V55__canal_para_equipe_e_aceite.sql`:

```
PENDENTE ──SIM──▶ AGUARDANDO_ACEITE ──Aceitar──▶ CONFIRMADA
    │                    ├──Recusar──▶ RECUSADA (RECUSADO_POR_QUEM_ATENDE)
    │                    ├──gravação recusada──▶ RECUSADA (código do erro)
    │                    └──prazo──▶ EXPIRADA
    └── (RT-CNV-005: CONFIRMADA · RECUSADA · SUBSTITUIDA · EXPIRADA)
```

- `mensagem_recebida.botao_id`, tipo `BOTAO`, com a coerência por tipo.
- `execucao_do_agente.tipo` (`CONVERSA` | `BOTAO`): a execução de botão pode terminar sem usuário efetivo.
- `intencao_pendente.aceite_*`: quem, número, motivo e token são gravados na simulação e não mudam;
  prazo e decisão, sim.
- `mensagem_para_equipe`: `sequencia` para a ordem, uma por motivo/origem/destino, RLS, a aplicação só
  insere.

## 10. Testes obrigatórios

Na primeira execução, 11 dos 13 testes do aceite falharam — todos pelo mesmo defeito (§4). Corrigido,
tudo passou; o planejamento das mutações mostrou dois buracos de cobertura, que ganharam teste (§11).
O `verify` completo, no fim: 367 unitários e 979 de integração, verde.

- [x] `CargaDoWhatsAppTest` — 15, quatro novos: **botão interativo vira `BOTAO` sem texto** · resposta
  rápida de modelo também · lista não é botão · id acima do limite da Cloud API não é botão.
- [x] `BotaoDeAceiteTest` — 3: o id vai e volta · o token tem 24 caracteres e não se repete · **dez
  formatos quase certos recusados** (maiúsculas, espaço, sufixo, token curto ou longo).
- [x] `CodigoDeConfirmacaoTest` — 23: o pedido com aceite diz que o SIM ainda não marca.
- [x] `FerramentasDaAgendaIT` — 18, sete novos: **fora da jornada pede aceite com o telefone
  normalizado** · **fora da jornada dentro de uma transação somente leitura não a envenena** · dentro da
  jornada, sem aceite · sem telefone, recusa dizendo por quê · fora da jornada ainda confere o resto ·
  **confirmar sem a prova é recusado** · confirmar com a prova força e deixa o aceite na trilha.
- [x] `CadastroDeProfissionalIT` — 18: telefone normalizado · telefone inválido aponta o campo.
- [x] `AceiteDaProfissionalIT` — 14, Ana sem conta de usuário: **caminho completo** (o SIM não grava, o
  pedido com botões, o toque grava em nome de quem pediu, a trilha, os dois avisos) · dentro da jornada
  · sem telefone · recusa · toque repetido · **outro número** · **quem pediu tocando por ela** · **token
  forjado** · **a profissional mandando o código** · vencido pelo expirador, uma vez · **toque depois do
  prazo** · **o banco recusa CONFIRMADA sem aceite** · horário tomado antes do aceite · permissão
  retirada de quem pediu.
- [x] `SaidaParaEquipeIT` — 11: resposta na saída · **pedido depois da resposta** · recusada não
  responde · **teto não entrega pedido** · botão fora do histórico · **em ordem por destino** · sem
  provedor espera · falhas transitória e permanente · canal desligado · **reserva atômica com duas
  instâncias** · enfileirado imutável.
- [x] `IntencaoPendenteIT` (15), `AgenteIT` (13), `WebhookDoWhatsAppIT` (21), `TranscricaoDeAudioIT`
  (14), `SchemaIT` (3) — verdes com a V55; e `CriarAgendamentoIT`, `ReagendarIT`, `EmpurrarAgendaIT`,
  `RecorrenciaIT`, `AgendamentoConcorrenteIT`, verdes com a reserva de horário devolvendo a jornada.

## 11. Mutações verificadas

**O planejamento das mutações achou dois buracos de cobertura antes de rodar qualquer uma.** Nenhum
teste conferia que a resposta ao toque *sai* para a profissional — só que ela ficava registrada na
execução —, e a constraint `intencao_com_aceite_so_confirma_aceita` não tinha teste. Os dois ganharam
asserção antes das rodadas, e a execução sem mutação foi verde.

**Rodada A** — dezessete linhas quebradas juntas. Dezesseis derrubaram exatamente os testes esperados, e
nenhum outro ficou vermelho:

| Linha quebrada de propósito | Testes que ficaram vermelhos |
|---|---|
| o toque vale de qualquer número | `AceiteDaProfissionalIT.outro_numero`, `.quem_pediu_nao_aceita_por_ela` |
| o token do botão não conferido | `AceiteDaProfissionalIT.token_forjado` |
| `agendar` força a jornada sem a prova do aceite | `FerramentasDaAgendaIT.confirmar_fora_da_jornada_sem_aceite` |
| a constraint de aceite tirada da V55 | `AceiteDaProfissionalIT.confirmada_sem_aceite_e_recusada_pelo_banco` |
| a resposta sem modelo não vai para a saída | `AceiteDaProfissionalIT.caminho_completo` |
| o despachante sem a ordem por destino | `SaidaParaEquipeIT.em_ordem_por_destino` |
| a reserva sem marcar `reservada_ate` | `SaidaParaEquipeIT.reserva_atomica` |
| o despachante reservando sem provedor | `SaidaParaEquipeIT.sem_provedor_espera` |
| o expirador sem o aviso | `AceiteDaProfissionalIT.vencido_pelo_expirador` |
| o toque de botão no histórico do modelo | `SaidaParaEquipeIT.botao_fora_do_historico` |
| o telefone da profissional guardado como digitado | `CadastroDeProfissionalIT.telefone_normalizado`, `.telefone_invalido` |
| o id do botão aceito sem distinguir maiúsculas | `BotaoDeAceiteTest.nao_reconhece` |
| o prazo não conferido no toque | `AceiteDaProfissionalIT.toque_depois_do_prazo` |
| a recusa sem aviso a quem pediu | `AceiteDaProfissionalIT.recusa` |
| a falha na gravação sem registro | `AceiteDaProfissionalIT.horario_tomado_antes_do_aceite`, `.permissao_retirada_de_quem_pediu` |
| o `revoke` da saída tirado da V55 | `SaidaParaEquipeIT.enfileirado_e_imutavel` |
| a checagem do tipo `button_reply` trocada | *nenhum* — ver abaixo |

**A que sobreviveu não mudava comportamento.** Troquei a checagem do tipo interativo, mas o id continuou
sendo lido de `button_reply` — que numa resposta de lista não existe. A lista seguia `NAO_SUPORTADO`
pelo caminho do id. Não é buraco de teste: é mutação equivalente. Refeita como a quebra real — ler
também o id de `list_reply` — na rodada C.

**Rodada B** — separada porque se sobrepõe à A: o SIM de quem pediu **gravando direto, sem pedir aceite**.
Ficaram vermelhos os onze testes que dependem da intenção esperando a profissional, e nenhum dos três que
não dependem (dentro da jornada, sem telefone, a profissional mandando o código). Sem o
`AGUARDANDO_ACEITE`, a gravação ainda é recusada — a ferramenta não força a jornada sem a prova —, mas
ninguém é perguntado: a falha é fechada.

**Rodada C** — a mutação equivalente da A, refeita como a quebra real: a resposta de lista lida como
botão, pelo id de `list_reply`. Ficou vermelho `CargaDoWhatsAppTest.lista_nao_e_botao`, e só ele.

## 12. Pendências

- [ ] **Nada sai, ainda.** Não há provedor de envio. A fila guarda tudo na ordem e entrega quando o
      adaptador da Cloud API entrar — com o token que RT-CNV-003 já usa para baixar mídia. **Sem isso,
      o agente não fala com ninguém em produção.**
- [ ] **Modelo aprovado para o pedido de aceite.** Fora da janela de 24h a Meta só entrega modelo
      aprovado, com os dois botões de resposta rápida. A aprovação leva dias (R-03); o adaptador
      precisa mapear `iniciadaPeloSalao` para esse modelo.
- [ ] **Só a jornada se negocia.** Encaixe manual na pausa (pergunta 7) e outras exceções continuam
      fora. O contrato comporta: é outra origem de `Aceite` na ferramenta.
- [ ] **Bloqueio de jornada conta como fora da jornada.** Férias e curso entram em `intervalosEm`: a
      profissional de férias recebe o pedido e pode aceitar. É o que a regra diz; confirmar com o dono.
- [ ] **O aceite só vale do número do cadastro.** Celular trocado exige corrigir o cadastro e pedir de
      novo — de propósito (RN-CNV-019).
- [ ] **A profissional não conversa com o agente.** Ela aceita e recusa; perguntar "o que tenho
      amanhã?" exige conta de usuário e o vínculo usuário↔profissional (RT-AGD-002).
- [ ] **Uma mensagem por destino por rodada.** A ordem custa uma rodada (5 s) entre a resposta e o
      pedido de confirmação. Medir antes de despachar em lote por destino.
- [ ] **Canal religado não reenvia o que falhou** com o canal desligado.
- [ ] **Retenção.** `mensagem_para_equipe` e `intencao_pendente` guardam nomes e horários, e os jobs de
      retenção de docs/04 não existem.
- [ ] **Sem modelo configurado, os toques esperam** junto com as confirmações: o processador não
      reserva nada sem chave e preço. Os aceites vencem pelo expirador, que roda de qualquer jeito.
- [ ] **O despachante da notificação continua com o defeito da reserva** (tarefa à parte). Esta fila
      não o herdou; aquela ainda o tem.
