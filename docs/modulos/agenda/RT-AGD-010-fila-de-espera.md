---
id: RT-AGD-010
titulo: Fila de espera
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-AGD-005, RT-AGD-001, RT-AGD-009]
permissoes: [agenda:write:all, agenda:read:all]
eventos: []
regras: [RN-AGD-009, RN-AGD-023, RN-AGD-025, RN-AGD-026, RN-AGD-027, RN-AGD-028]
atualizado_em: 2026-09-22
---

# RT-AGD-010 — Fila de espera

## 1. Objetivo

A cliente quer quinta às 14h e está tomado. Hoje a recepção anota num papel ao lado do telefone — e
o papel é justamente o que se perde quando a vaga abre uma semana depois, com outra pessoa no
balcão.

A rotina tem duas metades. A primeira (04/09/2026) guardou a espera. A segunda (22/09/2026) liga o
cancelamento à fila: a vaga que abre fica registrada, o sistema diz quem cabe nela, e a recepção
chama e registra o que cada uma respondeu.

## 2. As decisões — do dono, em 22/09/2026

| Pergunta | Decisão |
|---|---|
| Como a vaga chega a quem espera | **A recepção chama, o sistema organiza.** Não há provedor de WhatsApp ligado, e uma oferta automática ficaria `SEM_PROVEDOR` sem chegar a ninguém |
| Que vaga serve | **Qualquer serviço que caiba** no tempo liberado, conferido na disponibilidade real |
| A ordem de chegada é obrigatória? | **Em ordem; pular exige motivo.** Quem recusa ou não atende continua na fila |
| Os dias da série que não couberam | **A recepção decide, um dia por vez**: mesmo dia, serviço e profissional |

## 3. Janela, não horário

A espera é um **intervalo de dias** mais uma faixa de hora opcional.

"Quinta que vem à tarde" é o que a cliente diz. Exigir que ela escolha 14h ou 14h30 transformaria a
fila numa lista de horários específicos que quase nunca abrem exatamente — e a fila só serve se ela
casar com o que de fato cancela.

Pelo mesmo motivo, `profissionalId` nulo significa "qualquer um", e é **o caso em que a fila mais
funciona**: qualquer cancelamento serve.

**Uma espera por dia, não uma por serviço.** A mesma cliente não espera duas vezes pelo mesmo
serviço e profissional em **dias que se sobrepõem** — seriam duas ligações pela mesma vaga. Em dias
separados pode: é o que acontece com a série de quintas em que três não couberam. Até 22/09 a regra
era um índice único que proibia a segunda espera em qualquer dia; ela virou uma exclusion constraint
sobre o intervalo de dias.

## 4. A fila expira sozinha

Não há job de limpeza, e isso é decisão e não omissão.

A busca por candidatos filtra pelo intervalo de dias que a cliente aceitou, então uma espera cuja
janela já passou simplesmente **para de aparecer**. Uma fila que dependesse de um job para dar a
resposta certa daria a resposta errada exatamente quando o job falhasse — e ninguém percebe um job
que não roda até alguém ser chamado para um horário de duas semanas atrás.

A mesma lógica vale para a vaga (seção 6): ela some quando o horário passa, sem ninguém a fechar.

## 5. Duas saídas, e a diferença importa

`atendida_em` e `desistiu_em`, não um campo só nem um `delete`.

*"Conseguiu horário"* é o sistema funcionando; *"cansou de esperar"* é a fila sendo longa demais.
Apagar a linha, ou marcar as duas do mesmo jeito, jogaria fora justamente o número que diz se a fila
está servindo para alguma coisa.

## 6. A vaga nasce com o cancelamento — na mesma transação

Cancelar (RT-AGD-005) e reagendar (RT-AGD-003) gravam uma **vaga aberta**: de quem era o tempo, de
que hora a que hora, e por que abriu. Na mesma transação — um cancelamento gravado sem a vaga não
chega a existir. Encerrar uma série cancela as ocorrências pelo mesmo caso de uso, então abre as
vagas delas sem código próprio.

**Uma linha, e não um evento assíncrono.** O plano previa um ouvinte de `AgendamentoCancelado`. Seria
um ouvinte do próprio módulo, em outra thread, para gravar uma linha que a transação de origem pode
gravar sozinha — e o reagendamento não publica a janela antiga inteira. A escrita direta é atômica
e aparece no código de quem cancela, em vez de acontecer num lugar que ninguém procura.

**Reagendar também abre vaga.** Mudar a Marilda de quinta para sexta libera a quinta tanto quanto
cancelar. Se o horário novo cobre parte do antigo, a disponibilidade (seção 7) já desconta a parte
reocupada.

**Vaga dentro de vaga aberta não duplica.** Se o tempo liberado já está inteiro dentro de uma vaga
aberta da mesma profissional, nada é gravado: a vaga que já existe anuncia aquele tempo. Acontece
quando a vaga é preenchida pela fila e a nova cliente também cancela.

**Não há "fechar a vaga".** Ela deixa de aparecer quando o horário passa ou quando ninguém da fila
cabe mais — as duas coisas são calculadas na leitura. Uma coloração de duas horas cancelada e
preenchida por um corte de trinta minutos continua aberta para a hora e meia que sobrou.

## 7. Quem cabe: a disponibilidade responde, não o serviço

Até 22/09 só servia quem esperava **o mesmo serviço** que cancelou. Agora serve **qualquer serviço
que caiba**: cancelou uma coloração de duas horas, e quem espera um corte com a mesma profissional
também serve.

"Caber" não é conta de minutos. A resposta vem de RT-AGD-001, a mesma disponibilidade que a
recepção vê: jornada, bloqueios, a pausa da receita, o lavatório. Uma escova de 45 minutos não cabe
em 45 minutos livres se o lavatório está tomado.

Para cada espera, o horário oferecido precisa:

- **começar dentro da vaga** — é o tempo que abriu. Um horário que já estava livre antes não é esta
  vaga;
- ser da **profissional da vaga** — a espera sem profissional aceita qualquer uma;
- cair num **dia da janela** e, se houver faixa, **começar dentro dela**, em relógio de parede;
- estar no futuro.

A consulta de disponibilidade roda uma vez por serviço e dia, e não uma por espera: dez clientes
esperando corte na quinta são uma consulta só.

## 8. A recepção chama; o sistema guarda a ordem

A tela **Fila de espera** mostra as vagas abertas e, em cada uma, quem cabe, **na ordem de quem
chegou primeiro**, com telefone e os horários possíveis. A recepção liga e registra o que aconteceu:

| Resultado | O que muda |
|---|---|
| **Aceitou** | Cria o agendamento no horário escolhido e tira a espera da fila (seção 9) |
| **Recusou** | Nada na fila: ela recusou *este* horário, não a espera |
| **Não atendeu** | Nada na fila; pode ser chamada de novo |

**Pular exige motivo.** Chamar a segunda antes de resolver a primeira é permitido — "a primeira só
pode de manhã" —, mas o motivo fica gravado na chamada. Sem ele, a ordem de chegada seria uma
sugestão, e a cliente que espera há duas semanas perderia a vez para a que entrou ontem sem que
ninguém soubesse por quê. "Resolver" é ter qualquer chamada registrada naquela vaga, inclusive
"não atendeu".

Cada chamada é uma linha nova, nunca editada. A trilha de uma vaga conta a história inteira: quem
foi chamada, a que horas, por quem, o que respondeu, quem foi pulada e por quê.

**A chamada precisa caber.** Registrar chamada para quem não é candidata daquela vaga é recusado: a
tela pode estar velha, e a vaga pode ter sido tomada por outra pessoa no balcão.

## 9. Aceitou é um agendamento de verdade

"Aceitou" chama o mesmo caso de uso de RT-AGD-002, com o horário que a cliente escolheu. Tudo o que
vale para qualquer agendamento vale aqui. Se outra pessoa tomou o horário entre a tela e o clique,
o recálculo das candidatas já não o oferece e o registro é recusado; se as duas gravações forem
simultâneas, quem recusa é a exclusion constraint. Nos dois casos a chamada **não é gravada**: não
existe "aceitou" sem horário marcado.

É também o fim da pendência "marcar atendida é manual": quando a fila preenche a vaga, o sistema
sabe, porque foi ele que marcou. Marcar à mão continua existindo para o caso em que ela conseguiu
horário por outro caminho — e automatizar esse caso continua errado, porque ela pode ter marcado
outra coisa.

## 10. Recorrência: o dia que não coube, com um clique

RT-AGD-009 marca o que cabe e devolve o que não coube. Agora cada dia devolvido pode ir para a fila:
**mesmo dia, mesmo serviço, mesma profissional**, sem faixa de hora — a cliente disse "quinta", e a
recepção confirma a hora ao ligar.

Um dia por vez, e não a lista inteira: a cliente pode não querer aquele dia avulso. A espera guarda
de que série veio.

O dia precisa **cair no ritmo da série** — a partir do primeiro dia, de semana em semana ou de duas
em duas, contado no fuso do salão — e **não ter agendamento dela**, em nenhum status. Senão não é um
dia que não coube, é um pedido novo, e para isso existe a entrada comum. Uma ocorrência marcada e
depois cancelada coube: o cancelamento já abriu a vaga dela.

## 11. A hora é relógio de parede

A faixa de hora é comparada em `LocalTime` no fuso do salão, não em instante. A cliente disse
"quinta à tarde", que é uma frase sobre o relógio da parede — comparar instantes faria a faixa
escorregar uma hora na virada do horário de verão.

Daí a divisão: o banco filtra o que dá para filtrar por índice (profissional, intervalo de dias) e o
domínio decide a hora.

## 12. O que não entra

- **A mensagem automática.** Decisão do dono: sem provedor, toda oferta ficaria `SEM_PROVEDOR`
  (RN-NOT-005) e a recepção continuaria sem ferramenta. O que foi construído é o fluxo que a
  mensagem vai usar: a vaga, a ordem, os candidatos e o registro do resultado. Quando houver
  provedor, falta o canal — e decidir o prazo de resposta antes de passar à próxima.
- **Segurar a vaga.** Enquanto a recepção liga, o horário continua livre para quem chegar ao balcão.
  Numa ligação a resposta vem na hora; segurar só faz sentido quando a oferta for por mensagem.
- **Aviso de fila longa.** A tela mostra quantas pessoas esperam; um alerta a partir de N pede o N,
  que é do dono.

## 13. As regras

| Regra | O quê |
|---|---|
| RN-AGD-009 | Cancelar e reagendar abrem a vaga na mesma transação; a recepção chama (revisada) |
| RN-AGD-023 | A fila expira pela janela, não por limpeza |
| RN-AGD-025 | Quem cabe é quem a disponibilidade aceita, começando dentro da vaga — não quem espera o mesmo serviço |
| RN-AGD-026 | A ordem de chegada vale; pular exige motivo gravado |
| RN-AGD-027 | Aceitar é marcar: sem agendamento criado, não há "aceitou" |
| RN-AGD-028 | Uma espera por serviço e profissional em dias que se sobrepõem |

## 14. Contrato de API

**`GET /api/v1/vagas-abertas`** — `agenda:read:all`. Só vagas com alguém que cabe, da mais próxima
para a mais distante:

```json
[{
  "id": "0b1c…", "profissionalId": "7a2e…", "profissional": "Ana",
  "inicio": "2026-09-24T17:00:00Z", "fim": "2026-09-24T19:00:00Z", "origem": "CANCELAMENTO",
  "candidatas": [{
    "esperaId": "c41d…", "posicao": 1, "clienteId": "9f3a…", "cliente": "Marilda",
    "telefone": "5511987654321", "servicoId": "e5b0…", "servico": "Escova",
    "aceitaDe": "2026-09-22", "aceitaAte": "2026-09-26", "horaDe": "13:00", "horaAte": "18:00",
    "naFilaDesde": "2026-09-12T13:20:00Z",
    "horarios": ["2026-09-24T17:00:00Z", "2026-09-24T17:15:00Z"],
    "chamadas": [{ "resultado": "NAO_ATENDEU", "registradaEm": "2026-09-22T13:02:00Z",
                   "quem": "Júlia", "motivoDoPulo": null }]
  }]
}]
```

**`POST /api/v1/vagas-abertas/{id}/chamadas`** — `agenda:write:all`. Registra o resultado; `201` com
a chamada gravada.

```json
{ "esperaId": "c41d…", "resultado": "ACEITOU", "inicio": "2026-09-24T17:00:00Z",
  "motivoDoPulo": null }
```

| Erro | Quando |
|---|---|
| `404 ER-AGD-VAGA_NAO_ENCONTRADA` | a vaga não existe (ou é de outro salão) |
| `409 ER-AGD-VAGA_ENCERRADA` | o horário da vaga já passou |
| `409 ER-AGD-ESPERA_NAO_CABE` | a espera não é candidata desta vaga — tela velha, ou a vaga foi tomada |
| `422 ER-AGD-PULO_SEM_MOTIVO` | há alguém antes na ordem ainda não chamada, e não veio motivo |
| `422 ER-AGD-HORARIO_FORA_DA_VAGA` | "aceitou" sem horário, ou com um horário que não é desta candidata nesta vaga |
| `409 ER-AGD-CONFLITO_HORARIO` | outra pessoa tomou o horário entre a tela e o clique (vem de RT-AGD-002) |

```json
{ "type": "https://salao.app/erros/ER-AGD-PULO_SEM_MOTIVO", "status": 422,
  "title": "Chamar fora da ordem exige motivo",
  "detail": "Marilda está antes na fila e ainda não foi chamada nesta vaga; diga por que pular" }
```

**`POST /api/v1/recorrencias/{id}/fila-de-espera`** — `agenda:write:all`. Um dia da série que não
coube:

```json
{ "dia": "2026-10-08" }
```

`201` com a espera criada. `422 ER-AGD-DIA_FORA_DA_SERIE` quando o dia não é de uma ocorrência da
série ou já tem agendamento dela; `409 ER-AGD-JA_ESTA_NA_FILA` quando ela já espera aquele dia.

**`GET /api/v1/fila-de-espera`** passa a devolver os nomes (cliente, serviço, profissional) e a
série de origem, para a tela não montar a lista com uma consulta por linha.

## 15. Dados — `V66`

- `vaga_aberta` — profissional, início, fim, origem (`CANCELAMENTO` | `REAGENDAMENTO`) e o agendamento
  que a liberou. Só `insert`.
- `chamada_da_fila` — vaga, espera, resultado, agendamento criado (obrigatório se e só se
  `ACEITOU`), motivo do pulo, quem e quando. Só `insert`; uma espera aceita uma vez.
- `fila_espera.recorrencia_id` — a série de onde veio, quando veio de uma.
- `fila_espera`: o índice único vira `exclude using gist` sobre o intervalo de dias (RN-AGD-028).

## 16. As telas

**Fila de espera**, em Operação, para quem tem `agenda:write:all` — quem liga é quem registra.

- **Vagas abertas** no alto, porque é o que pede ação: a profissional, o dia e a hora, e a lista
  numerada de quem cabe — o número é a ordem de chegada, e é informação. Telefone clicável, os
  horários possíveis, e as chamadas anteriores escritas na linha. Chamar fora da ordem abre o campo
  do motivo.
- **Na fila**, embaixo: quem espera, o quê, com quem, que dias e que horas, desde quando; "conseguiu
  horário" e "desistiu"; e o formulário para pôr alguém na fila.

**E a agenda avisa.** Cancelar acontece na grade, não aqui: uma linha no alto da agenda diz quantas
vagas abriram com gente esperando e leva para esta tela. Sem ela, a vaga só existiria para quem
lembrasse de trocar de tela — e o buraco que a recepção acabou de criar é justamente quando ela
poderia preenchê-lo.

## 17. Observabilidade

- `info` ao abrir vaga: a vaga, o agendamento que a liberou e a janela — sem nome de cliente.
- `info` a cada chamada: vaga, espera, resultado e "(fora da ordem)" quando houve pulo.
- A auditoria grava a criação de cada `chamada_da_fila` com a posição, o resultado, o agendamento
  criado na aceitação e o motivo do pulo; e a de cada espera que veio de uma série, com a série.
- O erro de pulo diz a **posição** de quem seria pulada, e não o nome: detalhe de erro pode acabar
  em log, e nome de cliente não entra em log.

## 18. Testes

- [x] `EsperaNaFilaTest` — os 10 da primeira metade, sem mudança: a regra de dia, faixa e
      profissional continua a mesma, e a pergunta do serviço agora é feita com o da própria espera
- [x] `CandidatasDaVagaTest` — 11, domínio puro: **começa dentro da vaga, terminar depois pode** ·
      a faixa vale no início, em relógio de parede · a profissional da vaga · o dia da janela ·
      **ordem de chegada, e a posição conta só quem cabe** · sem horário não é candidata · cada
      espera com os horários do seu serviço · o passado fica de fora · e as três perguntas do pulo
      (a primeira não pula; a segunda pula a primeira sem chamada; com as de antes chamadas, não há
      pulo)
- [x] `FilaDeEsperaIT` — 12: os de entrar e sair da primeira metade, mais **sobreposição parcial é
      recusada** · **dias separados podem** · "qualquer uma" e "a Ana" são esperas diferentes · os
      nomes que a tela escreve. Os de candidatos foram para `VagaAbertaIT`, porque a pergunta mudou
- [x] `VagaAbertaIT` — 29, com Postgres de verdade: **cancelar abre a vaga, e quem espera outro
      serviço que caiba é candidata** · reagendar também abre · reagendar sem liberar, não · cancelar
      o que já passou, não · cancelamento recusado não abre · **vaga dentro de vaga não duplica** ·
      **só cabe o que a disponibilidade aceita** · **com o lavatório tomado, não cabe** · serviço que
      a profissional não faz · **espera de serviço inexistente não derruba a lista nem a chamada** ·
      ordem de chegada · faixa de
      hora · profissional (e chamar quem não cabe é recusado) · janela de dias · quem saiu · **a vaga
      passada some e recusa chamada** · **aceitou cria o agendamento, tira da fila, e o resto da vaga
      continua** · **horário tomado entre a tela e o clique não grava nada** · aceitou sem horário da
      vaga · recusou continua na fila · não atendeu conta como chamada · **pular sem motivo é
      recusado** (e a tela sabe antes) · pular com motivo grava · sem pulo o motivo não é gravado ·
      **o dia da série vai para a fila e vira candidata** · dia que coube, fora do ritmo, repetido
      ou depois da 52ª · dia passado e série encerrada · isolamento · permissão de ler e de
      registrar
- [x] `CapacidadesIT` (+2 conferências): a recepção recebe o menu da fila; o profissional, não
- [x] Front — `Fila.test.tsx`, 9: a vaga com a ordem, o telefone e o que já foi tentado · recusou
      num clique · aceitou pede o horário · quem exige motivo abre o campo antes · a recusa do
      servidor na linha · a vaga vazia · a fila com a contagem · desistiu pede confirmação · pôr na
      fila pela busca

## 19. O que apareceu no caminho

- **Um `catch` que teria derrubado a chamada errada.** A primeira versão do cálculo de candidatas
  pegava a exceção da disponibilidade para um serviço que saiu do catálogo. Só que a disponibilidade
  é transacional: a exceção marcaria a transação de quem chama como perdida, e registrar a chamada
  da Marilda falharia por causa da espera velha de outra pessoa. Virou pergunta antes, sem exceção.
- **A página inteira rolava de lado no celular.** O rótulo invisível da coluna de ações da tabela é
  absoluto, e sem ancestral posicionado ele escapava da rolagem da tabela. Só a prévia no celular
  mostrou; nenhum teste de comportamento pegaria.
- **O rótulo "Cliente" saiu com outro desenho** dos demais: o seletor compartilhado do rótulo só
  alcança filho direto. O campo foi reestruturado em vez de ganhar uma regra própria.
- **A tela precisava saber do pulo antes de clicar.** Descobrir pelo erro 422 faria a recepção
  clicar, ler o erro e só então ver o campo do motivo. O servidor passou a dizer, por candidata,
  se chamá-la exige motivo — a regra continua lá, e ele recusa de novo se a tela estiver velha.

## 20. Mutações verificadas

Uma rodada, 38 mutações, cada uma sozinha, rodando só os testes que deveriam pegá-la. **37 mortas**;
a que sobreviveu é equivalente, e está explicada abaixo.

| # | Mutação | Morta por |
|---|---|---|
| N01 | horário que começa antes da vaga entra | `CandidatasDaVagaTest.comeca_dentro_da_vaga` |
| N02 | horário que começa no fim da vaga entra | `.comeca_dentro_da_vaga` |
| N03 | horário passado é oferecido | `.passado_fica_de_fora` |
| N04 | profissional, dia e faixa da espera ignorados | `.faixa_de_hora`, `.profissional_da_vaga`, `.dia_da_janela` |
| N05 | sem ordem de chegada | `.ordem_de_chegada` |
| N06 | a posição conta quem não cabe | `.ordem_de_chegada` |
| N07 | quem já foi chamada conta como pulada | `.antes_todas_chamadas`, `VagaAbertaIT.nao_atendeu_conta_como_chamada` |
| N08 | nunca há pulo | `.segunda_pula_a_primeira`, `VagaAbertaIT.pular_sem_motivo_e_recusado` |
| N09 | vaga dentro de vaga duplica | `VagaAbertaIT.vaga_dentro_de_vaga_nao_duplica` |
| N10 | vaga no passado é gravada | `VagaAbertaIT.passado_nao_abre_vaga` |
| N11 | a leitura traz vaga que já passou | **sobreviveu — equivalente** (abaixo) |
| N12 | chamada em vaga encerrada não é recusada como tal | `VagaAbertaIT.vaga_passada_some_e_recusa_chamada` |
| N13 | chamada de quem não cabe é aceita | `VagaAbertaIT.profissional_filtra_e_chamada_de_quem_nao_cabe` |
| N14 | pulo com motivo em branco passa | `VagaAbertaIT.pular_sem_motivo_e_recusado` |
| N15 | sem pulo, o motivo é gravado | `VagaAbertaIT.sem_pulo_motivo_nao_e_gravado` |
| N16 | aceitou com horário fora da vaga passa | `VagaAbertaIT.aceitar_sem_horario_da_vaga`, `.horario_tomado_nao_grava_chamada` |
| N17 | aceitou não tira da fila | `VagaAbertaIT.aceitar_marca_e_tira_da_fila` |
| N18 | aceitou sem marcar | `VagaAbertaIT.aceitar_marca_e_tira_da_fila` |
| N19 | cancelar não abre vaga | `VagaAbertaIT.cancelar_abre_vaga` |
| N20 | reagendar não abre vaga | `VagaAbertaIT.reagendar_abre_vaga` |
| N21 | reagendar que cobre o antigo abre vaga | `VagaAbertaIT.reagendar_sem_liberar_nao_abre_vaga` |
| N22 | reagendamento gravado como cancelamento | `VagaAbertaIT.reagendar_abre_vaga` |
| N23 | sem perguntar se ela faz o serviço | `VagaAbertaIT.servico_que_ela_nao_faz` |
| N24 | sem perguntar se o serviço existe | `VagaAbertaIT.servico_inexistente_nao_derruba` |
| N25 | dia fora do ritmo da série entra | `VagaAbertaIT.dia_fora_da_serie` |
| N26 | o teto de 52 ocorrências some | `VagaAbertaIT.dia_fora_da_serie` |
| N27 | o teto vira 51 | `VagaAbertaIT.dia_fora_da_serie` |
| N28 | dia que coube entra | `VagaAbertaIT.dia_fora_da_serie` |
| N29 | dia passado entra | `VagaAbertaIT.dia_passado_ou_serie_encerrada` |
| N30 | série encerrada aceita | `VagaAbertaIT.dia_passado_ou_serie_encerrada` |
| N31 | sobreposição de dias permitida (`V66`) | `FilaDeEsperaIT.sobreposicao_parcial_e_recusada` |
| N32 | o erro do banco sai cru | `FilaDeEsperaIT.sem_duplicata` |
| N33 | a leitura esquece quem aceita qualquer uma | `VagaAbertaIT.cancelar_abre_vaga` |
| N34 | vaga sem candidata aparece | `VagaAbertaIT.quem_saiu_nao_e_candidata` |
| N35 | "exige motivo" invertido | `VagaAbertaIT.pular_sem_motivo_e_recusado` |
| N36 | quem só lê a própria agenda vê as vagas | `VagaAbertaIT.permissao` |
| N37 | quem só lê registra chamada | `VagaAbertaIT.permissao` |
| N38 | o menu da fila para quem só escreve na própria agenda | `CapacidadesIT.recepcao_sem_financeiro`, `.profissional_ve_o_proprio_extrato` |

**N11 é equivalente, e o filtro fica.** Tirar o `fim > agora` da leitura das vagas não muda o que a
tela mostra: numa vaga que já passou nenhum horário começa no futuro, então ela não tem candidata e
sai da lista pela outra regra. O filtro não é regra, é custo — `vaga_aberta` só cresce, e sem ele cada
leitura consultaria a disponibilidade de todas as vagas da história do salão.

A primeira versão da bateria achou **sete guardas sem teste nenhum** antes de rodar: vaga no passado,
serviço que a profissional não faz, serviço inexistente, série encerrada, dia passado, o teto de 52 e o
menu. Cada uma ganhou teste antes da rodada, e é por isso que N10, N23, N24, N26, N29, N30 e N38 estão
na tabela como mortas.

## 21. Pendências

- [ ] **A mensagem automática** — quando houver provedor de WhatsApp. Falta o canal e o prazo de
      resposta antes de passar à próxima; a vaga, a ordem e o registro já existem
- [ ] **Aviso de fila longa** — a tela mostra quantas esperam; o alerta a partir de N precisa do N
- [ ] **A série não tem tela.** O "pôr na fila" do dia que não coube existe na API; o botão vai
      junto da tela de recorrência, quando ela existir
- [ ] **A série não guarda quantas ocorrências pediu.** O dia da série é conferido pelo ritmo (a
      partir do primeiro, de N em N semanas, até 52), e não contra a lista pedida: um dia depois da
      última ocorrência passa. Guardar a quantidade em `recorrencia` fecharia a conta
- [x] ~~**A agenda não avisa que abriu vaga.**~~ Feito em 22/09/2026: a grade mostra uma linha
      ("abriram N vagas e há quem esteja esperando por elas") com link para a Fila de espera, só
      para quem marca para os outros — o link leva ao telefone de quem espera. É a mesma consulta
      da tela da fila, com um minuto de validade, então abrir a fila depois não custa requisição
      nova
