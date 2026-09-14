---
id: RT-CNV-005
titulo: "Tools de agenda: consultarDisponibilidade, simular, confirmar"
modulo: conversacional
fase: 4
perfil: completo
status: implementado
depende_de: [RT-CNV-004, RT-AGD-001, RT-AGD-002, RT-CLI-002]
permissoes: [agenda:read:all, agenda:write:all, cliente:read]
eventos: []
regras: [RN-CNV-014, RN-CNV-015, RN-CNV-016, RN-CNV-017, RN-AGD-013]
atualizado_em: 2026-09-13
---

# RT-CNV-005 — Marcar pelo agente: consultar, simular, confirmar

## 1. Objetivo

A recepção escreve pelo WhatsApp *"marca corte da Marilda com a Ana quinta às 13h"*. O agente
encontra a cliente, confere o horário e prepara a marcação. **Quem grava é a pessoa**, respondendo
com um código — nunca o modelo.

## 2. O padrão inegociável pede estado

O plano diz: *simular → confirmação humana explícita → confirmar; o LLM nunca escreve direto no
domínio*.

A leitura ingênua é o modelo simular, perguntar "confirma?", a pessoa dizer "sim" e o modelo chamar
`confirmar`. Nela, **quem decide que houve confirmação é o modelo** — que RN-CNV-010 trata como
comprometido. Um ataque que o fizesse chamar simular e confirmar na mesma volta gravaria sem ninguém
ter confirmado nada. "Não confirme sem perguntar" no prompt seria a frase que a mensagem seguinte
revoga.

Então a confirmação não pode ser ferramenta do modelo: precisa ser **reconhecida fora dele**. E
reconhecer uma confirmação exige estado — o que foi proposto, por quem, com qual código e até quando.
É a `intencao_pendente`.

### O que a implementação corrigiu no plano

- A linha do plano lista `confirmar` como ferramenta. Ele existe — `FerramentaDeEscrita.confirmar` —,
  mas **não é oferecido ao modelo**. A única porta até ele é a mensagem da pessoa com o código.
- A entidade `IntencaoPendente`, prevista para RT-CNV-006, **nasceu aqui**, com validade e máquina de
  estados: sem ela não há confirmação humana que se sustente. RT-CNV-006 continua com o que é dela —
  negociar com o profissional e botões.
- `buscar_clientes` não estava na linha, e sem ela a rotina não existe: `agendar` pede o id da
  cliente, e o modelo não pode inventar um.

## 3. As ferramentas

| Ferramenta | Módulo | Forma | Exige | Chama |
|---|---|---|---|---|
| `listar_servicos` | catalogo | leitura | `agenda:read:all` | `ListarServicosUseCase` (RT-CNV-004) |
| `buscar_clientes` | cliente | leitura | `cliente:read` | `BuscarClientesUseCase` |
| `consultar_disponibilidade` | agenda | leitura | `agenda:read:all` | `ConsultarDisponibilidadeUseCase` |
| `agendar` | agenda | **escrita** | `agenda:write:all` | `CriarAgendamentoUseCase.simular` / `.executar` |

Todas chamam o **caso de uso** do módulo dono, com o `@PreAuthorize` dele (RN-CNV-013).

## 4. Um contrato selado: a escrita não tem `executar` — RN-CNV-014

`Ferramenta` virou uma interface **selada** com duas formas, e só duas:

| Forma | Métodos | Quem chama |
|---|---|---|
| `FerramentaDeLeitura` | `executar` | o orquestrador, em nome do usuário, em transação somente leitura |
| `FerramentaDeEscrita` | `simular` | o orquestrador, em nome do usuário, em transação somente leitura |
| | `confirmar` | **só** `EfetivarIntencao`, alcançada pela mensagem da pessoa com o código |

Até RT-CNV-004 a escrita era uma `Natureza` que o orquestrador recusava num `if`
(`ESCRITA_EXIGE_CONFIRMACAO`). Agora não há o que recusar: o `ExecutorDeFerramenta` **não tem método
que grave**, e uma "escrita com `executar`" não compila.

O teste de arquitetura `so_a_confirmacao_da_pessoa_grava_pelo_agente` fecha a última porta: nenhuma
classe além de `EfetivarIntencao` chama `confirmar` — nem pela interface, nem pelo tipo concreto.

## 5. O caminho

```
"marca corte da Marilda com a Ana quinta 13h"
  → modelo pede buscar_clientes, consultar_disponibilidade, agendar
  → agendar.simular            em nome da Bia · transação somente leitura
  → intencao_pendente           resumo + comando + código 4821 + vale até 13:52
  → o modelo recebe o resumo    SEM o código · responde "preparei, confirme pelo código"

"SIM 4821"
  → reconhecido antes do modelo (CodigoDeConfirmacao) — o modelo nem é chamado
  → EfetivarIntencao            trava a linha · dono, validade, permissão resolvidos AGORA
  → agendar.confirmar           CriarAgendamentoUseCase.executar, com @PreAuthorize
  → CONFIRMADA                  no mesmo commit do agendamento
  → resposta do servidor        "Marcado: Corte para Marilda com Ana, quinta-feira, 17/09, das 13:00 às 13:30."
```

## 6. Por que um código — RN-CNV-015

- **"sim" responde a qualquer pergunta.** Se o agente perguntou "quer ver outro horário?", um "sim"
  não pode virar marcação.
- **O código amarra a resposta a UM resumo.** "SIM 4821" confirma aquele texto, e não o que estiver
  pendente quando a mensagem for processada.
- **A mensagem inteira precisa ser a confirmação.** "sim 4821 e cancela o resto" não é: vai para o
  modelo, que não tem como gravar.

O código **não é segredo** — quem manda mensagem daquele número é a pessoa, atestada pela Meta
(RN-CNV-001). É vínculo e intenção, e quatro dígitos bastam. Não repete os códigos do mesmo telefone
nas últimas 24 horas, e **o servidor não o manda ao modelo**: ele não tem o que fazer com o código, e
um código repetido no texto do modelo competiria com o pedido do servidor.

Códigos antigos aparecem, sim, no histórico que o modelo lê — é o "SIM 4821" que a própria pessoa
escreveu. Não importa: confirmação só vem de mensagem recebida pelo webhook, e o modelo não tem como
produzir uma.

**Tentar códigos em sequência esbarra no limite por telefone.** A confirmação é reconhecida depois da
identidade e do limite (RN-CNV-012): cada tentativa é uma conversa contada.

**Uma pendente por telefone.** A proposta nova encerra a anterior (`SUBSTITUIDA`). Duas ao mesmo tempo
fariam a pessoa confirmar uma olhando para a outra — e "SIM" com o código antigo responde que o
pedido foi trocado.

**Validade de dez minutos**, configurável. Depois disso nada grava: a pessoa pede de novo, e o horário
é conferido de novo.

**Áudio.** A confirmação é reconhecida sobre a entrada da conversa, que para áudio é a transcrição.
Uma transcrição que seja exatamente "sim, 4821" confirma — o que torna a confirmação explícita é o
código, não o meio. Uma que venha "sim quatro oito dois um" não é reconhecida e vai ao modelo, que não
grava: falha fechada.

## 7. Exatamente uma vez, sem chave de idempotência — RN-CNV-016

A intenção vira `CONFIRMADA` **na mesma transação** em que `agendar.confirmar` grava o agendamento, e
sob trava da linha (`select ... for update`):

- se o processo cair entre a gravação e a marcação, não houve gravação;
- duas confirmações da mesma intenção se enfileiram na trava — a segunda encontra `CONFIRMADA` e
  responde "já estava confirmado";
- se cair depois do commit e antes de a conversa ser encerrada, a mensagem volta à fila e responde a
  mesma coisa.

Recusa de domínio sai como exceção e desfaz tudo — inclusive a gravação que tivesse começado. O
registro da recusa (`RECUSADA`, com o código do erro) é feito **fora**, numa transação própria: feito
dentro, seria desfeito junto. É a lição de RN-ARQ-003.

## 8. As trancas de novo, na hora de gravar — RN-CNV-017

Nada do momento da proposta autoriza a gravação:

| Conferido na confirmação | Se falhar |
|---|---|
| a identidade do telefone, resolvida agora (RT-CNV-002) | a conversa é recusada antes de chegar aqui |
| o usuário efetivo é o mesmo que propôs | "não encontrei" — o número mudou de dono |
| a validade | `EXPIRADA` |
| as permissões que a ferramenta declara | `RECUSADA`, `SEM_PERMISSAO` |
| o `@PreAuthorize` do caso de uso | `RECUSADA`, `SEM_PERMISSAO` |
| todas as regras da agenda, de novo | `RECUSADA`, com o código do erro |

Quem perdeu a permissão entre o resumo e o "SIM" não grava. O horário tomado entre os dois é recusado
pela gravação — a simulação nunca foi garantia.

## 9. Nem a leitura grava

Tudo o que o modelo dispara — leitura ou simulação — roda dentro de uma **transação somente leitura**
(`TransacaoSomenteLeitura`) aberta antes da ferramenta. Os casos de uso chamados herdam a transação,
e o Postgres recusa qualquer escrita.

A declaração da ferramenta não é confiada: `AgenteIT.leitura_que_grava` usa uma ferramenta que se diz
leitura e faz `UPDATE` — e a gravação não acontece. `CriarAgendamentoUseCase.simular` é `readOnly`
também, por conta própria: são duas camadas, uma de cada módulo.

## 10. O que a pessoa lê é do servidor

- **O resumo sai do cadastro.** Nomes vêm de `ClienteApi` e `ProfissionalApi`, horário do caso de uso
  — nunca dos argumentos. Um modelo que dissesse "Maria às 10h" mandando o id da Joana e 15h produziria
  um resumo que diz *Joana às 15h*, e é esse texto que a pessoa confirma.
- **O pedido de confirmação é mensagem separada.** Fica em `intencao_pendente.pedido_de_confirmacao`,
  e não é concatenado à resposta do modelo. Um modelo que imitasse o bloco de confirmação no próprio
  texto não teria o código.
- **Hora do salão, nos dois sentidos.** `consultar_disponibilidade` devolve `2026-09-17T13:00`, e
  `agendar` recebe no mesmo formato. Com `Z` ou deslocamento é recusado: o modelo convertendo fuso é a
  marcação três horas errada.
- **Erro em texto de gente.** A mensagem técnica da agenda traz ids e UTC; `FerramentaAgendar.legivel`
  a troca por "quem vai atender já tem atendimento nesse horário." O código fica igual, e é ele que vai
  para o rastro.

## 11. Simular é criar sem gravar

`CriarAgendamentoUseCase.simular` e `.executar` passam pelo mesmo `preparar` — serviço, cliente,
habilidade, jornada, passado e unidades de recurso. Duas cópias da validação divergiriam, e a
divergente seria a da simulação: é ela que se mostra à pessoa, e ninguém a vê falhar.

A simulação tem **uma pergunta a mais**: o conflito com outro atendimento do profissional. Na gravação,
quem decide é a exclusion constraint (RN-AGD-004); sem o insert ela não fala. A pergunta olha só os
blocos que ocupam o profissional — o encaixe na pausa de uma coloração continua sendo oferecido
(`FerramentasDaAgendaIT.simular_encaixe_na_pausa`).

A gravação não ganhou essa pré-checagem: lá o banco é o árbitro, e `AgendamentoConcorrenteIT`
continua provando que a perdedora perde pelo banco.

## 12. A origem AGENTE — RN-AGD-013

Quem marca pelo agente é alguém da **equipe** que confirmou o resumo: nasce `CONFIRMADO`, como no
balcão. `WHATSAPP` diria o contrário — é a cliente sozinha, e nasce `SOLICITADO`. `PAINEL` apagaria da
agenda que a marcação veio do agente.

`StatusAgendamento.inicialPara` virou `switch` exaustivo: uma origem nova não compila sem alguém
decidir de que lado ela está.

O agente **não força nada** — jornada, passado ou duração. Forçar exige justificativa de quem sabe
por quê, e isso é da tela.

## 13. Dados

`V54__intencao_pendente.sql`:

```
PENDENTE ──┬── CONFIRMADA    a pessoa respondeu com o código, e gravou
           ├── RECUSADA      respondeu, e a gravação foi recusada (motivo = código do erro)
           ├── SUBSTITUIDA   uma proposta nova do mesmo telefone chegou antes
           └── EXPIRADA      respondeu depois da validade
```

- `comando`, `resumo`, `pedido_de_confirmacao` e `codigo` são **imutáveis** para a aplicação: só as
  colunas da decisão têm `grant update`. O que foi confirmado é o que foi proposto.
- `argumentos` guarda o que o modelo pediu, como pediu — entrada não confiável, para o rastro.
- Índice único parcial: **uma pendente por telefone**. Trava consultiva por telefone (`pg_advisory_xact_lock`)
  serializa duas propostas simultâneas do mesmo número.
- RLS, `revoke delete`. A origem `AGENTE` entra no `check` de `agendamento.origem`.

Pendente que venceu sem ninguém responder **continua `PENDENTE`**, com `expira_em` no passado — e é lida
assim. A próxima proposta ou a tentativa de confirmar a marca `EXPIRADA`. Nenhum job: nada depende
disso.

## 14. Testes obrigatórios

Tudo passou na primeira execução: 69 unitários e 63 de integração nas classes da rotina. A mutação
mostrou um buraco, e ele ganhou teste (§15). O `verify` completo, depois disso: 359 unitários e 945 de
integração, verde.

- [x] `CodigoDeConfirmacaoTest` — 22, parametrizado: reconhece oito formas de "SIM 4821" · **não
  reconhece onze que não são SÓ a confirmação**, inclusive "sim 4821 e cancela o resto" e "pode
  marcar, sim 4821" · nula · o sorteio não repete o código recente e completa com zeros · o pedido traz
  resumo, código e validade, **e não é ele mesmo uma confirmação**.
- [x] `ArgumentosDaFerramentaTest` — 5: texto · id canônico e opcional · **data e hora do salão, com
  fuso recusado** · **a recusa nomeia o campo e não repete o valor** · argumentos que não são objeto.
- [x] `OrquestradorTest` — 14: **escrita vira PROPOSTA e nada grava** · recusa na simulação · o prompt
  fala do código só para quem tem escrita · argumentos nulos viram objeto · **modelo comprometido pede
  cinco coisas: a escrita permitida só propõe, a não permitida nem simula** · os de RT-CNV-004.
- [x] `CatalogoDeFerramentasTest` — 5: escrita oferecida só com a permissão · sem declaração nunca,
  leitura ou escrita · nome repetido entre leitura e escrita derruba a subida.
- [x] `StatusAgendamentoTest.status_inicial_por_origem` — `AGENTE` nasce `CONFIRMADO`.
- [x] `ArquiteturaTest.so_a_confirmacao_da_pessoa_grava_pelo_agente`.
- [x] `FerramentasDaAgendaIT` — 11: disponibilidade em hora do salão · teto da lista · argumento
  inválido sem ecoar o valor · **simular não grava** · conflito em texto de gente · **encaixe na pausa
  continua oferecido** · não força passado nem jornada · fuso recusado · sem permissão, negado ·
  **confirmar grava `AGENTE`/`CONFIRMADO`** · **confirmar confere tudo de novo**.
- [x] `FerramentaBuscarClientesIT` — 5: **só o mínimo para escolher** · pelos 4 dígitos · termo curto
  · no máximo dez · sem permissão, negado.
- [x] `IntencaoPendenteIT` — 15, com as ferramentas reais: **caminho completo** (o código não vai ao
  modelo, a confirmação não chama o modelo, trilha `BOT` a pedido da recepção, `criado_por`) · leituras
  reais em nome da recepção · **o modelo não confirma** · sem permissão não vê `agendar` · código
  errado · vencida · nova substitui a anterior · SIM repetido · **confirmações simultâneas** · horário
  tomado no meio · permissão retirada no meio · outra pessoa · **o número passou a outra pessoa** ·
  outro salão · **proposto imutável**.
- [x] `AgenteIT` — 13: **leitura que grava é recusada pelo banco** · desconhecido com cara de
  confirmação · modelo comprometido agora com `PROPOSTA` e sem gravação · os de RT-CNV-004.
- [x] `CriarAgendamentoIT` (19) e `AgendamentoConcorrenteIT` (1) — verdes depois de `preparar` ser
  extraído; a perdedora continua perdendo pelo banco.

`AgenteIT` e `IntencaoPendenteIT` herdam de `AbstractAgenteIT`: o mesmo `@DynamicPropertySource` e as
mesmas ferramentas falsas, e por isso o mesmo contexto do Spring. O limite por telefone da suíte subiu
de 3 para 5, porque trocar de pedido e confirmar os dois são quatro conversas.

## 15. Mutações verificadas

Três rodadas, cada uma numa execução só, com os originais restaurados e conferidos no fim.

**Rodada A** — treze linhas quebradas juntas. Doze derrubaram exatamente os testes esperados, nenhum
outro ficou vermelho, e **uma sobreviveu**:

| Linha quebrada de propósito | Testes que ficaram vermelhos |
|---|---|
| a transação somente leitura virou leitura e escrita | `AgenteIT.leitura_que_grava` |
| a validade da intenção não conferida | `IntencaoPendenteIT.vencida` |
| `for update` tirado da trava da intenção | `IntencaoPendenteIT.confirmacoes_simultaneas` |
| a proposta nova sem encerrar a anterior | `IntencaoPendenteIT.nova_proposta_substitui` |
| a pré-checagem de conflito do profissional desligada na simulação | `FerramentasDaAgendaIT.simular_conflito` |
| `AGENTE` nascendo `SOLICITADO` | `StatusAgendamentoTest.status_inicial_por_origem`, `FerramentasDaAgendaIT.confirmar_grava`, `IntencaoPendenteIT.caminho_completo` |
| `inicio` com `Z` aceito | `ArgumentosDaFerramentaTest.datas`, `FerramentasDaAgendaIT.simular_com_fuso` |
| o telefone inteiro devolvido ao modelo | `FerramentaBuscarClientesIT.minimo_para_escolher` |
| a recusa da confirmação sem registro | `IntencaoPendenteIT.horario_tomado_no_meio` |
| o sorteio sem evitar o código recente | `CodigoDeConfirmacaoTest.sorteio` |
| o `revoke`/`grant` de coluna tirado da `V54` | `IntencaoPendenteIT.proposto_e_imutavel` |
| **o orquestrador chamando `confirmar`** logo depois de propor | `ArquiteturaTest.so_a_confirmacao_da_pessoa_grava_pelo_agente`, `OrquestradorTest.escrita_vira_proposta`, `OrquestradorTest.modelo_comprometido`, `AgenteIT.modelo_comprometido` |
| **as duas comparações com o dono da intenção** tiradas | *nenhum* — sobreviveu |

**A que sobreviveu, e por quê.** `outra_pessoa_nao_confirma` continuou verde sem as comparações com o
usuário: a busca da intenção é pelo TELEFONE, então outra pessoa, com outro número, nem a encontra. O
teste provava o escopo por telefone, e não a comparação com o dono. O caso que a comparação de fato
protege — **o mesmo número passar a outra pessoa** entre o resumo e o "SIM" — não tinha teste. Ganhou
`IntencaoPendenteIT.telefone_trocou_de_dono`.

**Rodada B** — a mesma mutação, com o teste novo: ficou vermelho `telefone_trocou_de_dono`, e só ele. A
nova dona do número chegava à intenção, esbarrava na terceira comparação (dentro de `emNomeDe`) e a
intenção terminava `RECUSADA` em vez de continuar `PENDENTE` com "não encontrei".

**Rodada C** — separada porque os testes se sobrepõem aos da A: o processador **sem reconhecer o
código**, mandando "SIM 4821" ao modelo. Ficaram vermelhos os dez testes que confirmam pela mensagem
(`caminho_completo`, `codigo_errado`, `vencida`, `nova_proposta_substitui`, `confirmacao_repetida`,
`horario_tomado_no_meio`, `permissao_retirada_no_meio`, `outra_pessoa_nao_confirma`,
`telefone_trocou_de_dono`, `outro_salao`), e nenhum dos cinco que não confirmam. Sem o reconhecimento,
nada grava — o modelo recebe o "SIM" e não tem como confirmar: a falha é fechada.

### E um defeito de operação, pego relendo o runbook

O runbook dizia que uma ferramenta que grava no caminho de leitura aparece no log como
"read-only transaction". Não aparecia: o orquestrador registrava `FALHA` no rastro e não logava nada.
Agora loga `Ferramenta <nome> falhou (<classe>)` — só a classe, porque a mensagem de uma exceção
qualquer pode trazer dado pessoal.

## 16. Pendências

- [x] ~~**Nada é entregue.**~~ RT-CNV-006 criou a saída para a equipe: a resposta e o pedido de
      confirmação são enfileirados ao encerrar a conversa, nessa ordem e como mensagens separadas.
      **Falta o provedor de envio** — até lá, a fila espera.
- [ ] **Remarcar e cancelar** não têm ferramenta. O contrato comporta: cada uma é uma
      `FerramentaDeEscrita` no módulo `agenda`.
- [ ] **Sem modelo configurado, as confirmações também esperam.** O processador não reserva nada sem
      chave e preço. Como sem modelo não nasce intenção, a que ficou vence sozinha.
- [x] ~~**Intenção de conversa que não terminou**~~ continua pendente e vence — mas desde RT-CNV-006
      o pedido só é entregue quando a conversa termina, e nunca chega a quem não recebeu resposta.
- [ ] **Transcrição por extenso não confirma.** "sim quatro oito dois um" vai ao modelo, que não grava:
      falha fechada. Converter número por extenso seria interpretar; medir antes.
- [ ] **O resumo pode divergir do gravado num caso:** a duração da profissional mudar entre a simulação
      e o "SIM". A gravação usa a receita do momento. Dez minutos de validade tornam raro; comparar o
      fim previsto é o próximo passo se acontecer.
- [ ] **Sem observação.** O agente não passa `observacao` ao agendamento — seria texto do modelo
      gravado na agenda.
- [ ] **Retenção de `intencao_pendente`.** O resumo tem nome de cliente; a tabela entra na regra de 12
      meses de `acao_ia` (docs/04), e o job não existe — a mesma pendência de RT-CNV-007.
- [ ] **O dado vai ao provedor.** `buscar_clientes` manda nome e apelido, e o resumo nomes de cliente e
      profissional. Reforça o contrato de processamento de dados pedido em RT-CNV-004.
- [ ] **Profissional não marca na própria agenda pelo agente.** `agenda:write:own` não vai pelo canal
      (RN-IAM-021), e o vínculo usuário↔profissional não existe (RT-AGD-002).
- [ ] **Pendente vencida sem resposta continua `PENDENTE`** até a próxima proposta ou tentativa. Quem
      contar pendentes precisa olhar `expira_em` — ou um job de expiração, quando alguém precisar.
- [ ] **O modelo não sabe que há uma pendente.** Se a pessoa escrever "pode confirmar" sem o código, o
      modelo só consegue pedir o código, que não conhece. É o comportamento seguro: quem mostra o código
      é o pedido do servidor.
