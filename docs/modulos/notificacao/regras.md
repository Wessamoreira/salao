# Regras de negócio — módulo `notificacao`

---

### RN-NOT-001 — O consentimento é perguntado aqui, e a recusa vira registro

**Enunciado.** Antes de enfileirar, o módulo pergunta ao `ClienteApi` se pode falar com a cliente
para aquela finalidade. Recusa **não** descarta o pedido: grava a mensagem com status
`BLOQUEADA`.

**Motivo.** Quem pede o lembrete não pode decidir se tem direito de mandá-lo — a regra da LGPD
escrita em dois lugares tem uma segunda cópia, e a segunda é a que continua mandando depois da
revogação.

E a recusa precisa deixar rastro. Sem o registro, *"por que a cliente não recebeu?"* não tem
resposta — e a resposta certa, *"ela não autorizou marketing"*, é justamente a que o salão precisa
dar quando ela reclama.

Lembrete de agendamento dispensa consentimento porque a base legal é execução de contrato
(RT-CLI-004): exigir opt-in para avisar do horário que ela marcou seria confundir marketing com
atendimento.

**Onde é garantida.** `EnfileirarMensagemUseCase` · `ClienteApi.podeReceber`.
**Rotinas.** RT-NOT-001, RT-NOT-002 · **Teste.** `NotificacaoIT.sem_consentimento_bloqueia`,
`NotificacaoIT.lembrete_dispensa_consentimento`, `NotificacaoIT.com_consentimento_passa`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-NOT-002 — Grava antes de enviar, e tenta de novo com espera crescente

**Enunciado.** A mensagem é gravada com status `PENDENTE` e enviada depois, por um despachante que
roda a cada minuto. Falha de envio conta uma tentativa e adia a próxima — 1, 2, 4, 8, 16 minutos —,
e na quinta a mensagem vira `FALHOU`.

**Motivo.** "O WhatsApp caiu por dez minutos" é rotina, não exceção. Um envio feito direto na
chamada do caso de uso não tem como ser repetido; gravar primeiro transforma uma falha de rede numa
linha para tentar de novo.

A espera crescente existe porque, sem ela, um provedor fora do ar vira uma rajada de chamadas que o
mantém fora do ar — e o salão descobre pelo bloqueio da conta, não pela mensagem que não chegou.

Cinco tentativas cobrem meia hora. Além disso a mensagem perdeu o sentido: um lembrete de "amanhã
às 14h" que sai seis horas depois é ruído.

**Um relógio só.** `proxima_tentativa_em` é preenchido pela aplicação, não por `now()` do banco: o
despachante compara esse valor com o port `Relogio` (RN-INF-006), e dois relógios na mesma
comparação fazem a mensagem nunca ser vista — sem erro, sem log, só uma fila que não anda.

**Onde é garantida.** Tabela `mensagem` (V30) · `EsperaEntreTentativas` ·
`DespachanteDeMensagens`.
**Rotinas.** RT-NOT-001 · **Teste.** `ModeloTest.espera_dobra`, `ModeloTest.desiste_na_quinta`,
`NotificacaoIT.falha_volta_para_a_fila`, `NotificacaoIT.falha_adia`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-NOT-003 — O mesmo fato não vira duas mensagens

**Enunciado.** Uma mensagem pode declarar a `referencia` do fato que a gerou — o id do agendamento,
no lembrete. Pedir duas vezes com a mesma referência devolve a mensagem que já existe.

**Motivo.** O job que varre os agendamentos de amanhã roda de novo a cada execução. Sem a
referência, a cliente recebe seis lembretes do mesmo horário — que é pior do que nenhum, e é o tipo
de erro que faz o salão desligar a funcionalidade inteira.

Garantido por índice único parcial, não por consulta prévia: duas execuções simultâneas do job
passariam pela mesma checagem.

**Onde é garantida.** `idx_mensagem_sem_duplicata` (V30) · `on conflict do nothing` em
`MensagensJdbc.enfileirar`.
**Rotinas.** RT-NOT-001, RT-NOT-002 · **Teste.** `NotificacaoIT.referencia_impede_duplicata`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-NOT-004 — O lembrete tem janela larga e idade mínima

**Enunciado.** O lembrete é gerado para agendamentos que começam entre 12 e 26 horas à frente, cujo
registro tenha ao menos 2 horas, e que ainda ocupem a agenda.

**Motivo.** A janela é larga porque uma faixa estreita deixaria sem lembrete todas as clientes
daquela hora se a execução falhasse — e o silêncio não aparece em lugar nenhum. Repetir é barato;
perder é caro. A duplicata é impedida pela referência única (RN-NOT-003), não pela precisão do
agendamento do job: apoiar a correção no relógio seria trocar uma garantia do banco por uma do
sistema operacional.

A idade mínima existe porque quem combinou o horário no balcão há dez minutos não precisa de uma
mensagem dizendo o que acabou de combinar. A regra adia o lembrete, não o cancela — a janela larga
alcança a mesma cliente na execução seguinte.

E só o que ainda ocupa a agenda: lembrar de um cancelado faz a cliente aparecer para um horário que
não existe.

**Onde é garantida.** `LembradorDeAgendamentos` · `AgendaJdbc.aLembrar`.
**Rotinas.** RT-NOT-002 · **Teste.** `LembradorDeAgendamentosIT.nao_duplica`,
`LembradorDeAgendamentosIT.recem_marcado_nao_lembra`,
`LembradorDeAgendamentosIT.cancelado_nao_lembra`
**Configurável?** Ainda não, e deveria ser por salão · **Origem.** 2026-09-05

### RN-NOT-005 — Mensagem que não saiu não diz que saiu

**Enunciado.** Todo `Mensageiro` responde se entrega de verdade (`configurado()`, sem valor padrão).
Sem provedor, o despachante grava `SEM_PROVEDOR` — **final** — sem chamar o envio e sem preencher
`enviada_em`. `ENVIADA` exige a data do envio, que vem do relógio da aplicação.

**Motivo.** Até 21/09/2026 toda mensagem aparecia como enviada sem ter saído, e o salão concluía que a
cliente tinha ignorado um lembrete que nunca chegou. Final, e não "espera o provedor", porque lembrete
tem prazo: o de um horário de três semanas atrás não pode disparar no dia em que alguém ligar o
WhatsApp (decisão do dono). É a diferença deliberada em relação à fila da equipe (RT-CNV-006), onde o
"marcado" continua servindo depois.

**Onde é garantida.** `Mensageiro.configurado` · `MensageiroDeRegistro` · `DespachanteDeMensagens`
(`marcarSemProvedor`, `marcarEnviada`) · `V65` (o estado, a reclassificação do passado e o `check`
`mensagem_enviada_tem_data`).
**Rotinas.** RT-NOT-001 (correção) · **Teste.** `MensageiroDeRegistroTest.nao_finge_que_entrega`,
`NotificacaoIT.sem_provedor_nao_finge`, `.sem_provedor_e_final`, `.enviada_tem_data`
**Configurável?** Não · **Origem.** 2026-09-21, decisão do dono
