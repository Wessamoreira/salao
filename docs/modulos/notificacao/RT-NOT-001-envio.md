---
id: RT-NOT-001
titulo: Envio de mensagem com template, retry e opt-out
modulo: notificacao
fase: 1
perfil: completo
status: implementado
depende_de: [RT-INF-006, RT-CLI-004]
permissoes: []
eventos: []
regras: [RN-NOT-001, RN-NOT-002, RN-NOT-003]
atualizado_em: 2026-09-05
---

# RT-NOT-001 — Envio de mensagem

## 1. Objetivo

Falar com a cliente — se, quando e como for permitido.

## 2. O consentimento é perguntado aqui, e só aqui

O contrato do `ClienteApi` já dizia: *"a pergunta que o módulo `notificacao` faz antes de qualquer
envio, e a única que ele deve fazer"*. Esta rotina é a que a cumpre.

Quem pede o lembrete não decide se tem direito de mandá-lo. E a recusa **vira linha**, com status
`BLOQUEADA`, em vez de virar nada: sem o registro, *"por que a cliente não recebeu?"* não tem
resposta — e a resposta certa é justamente a que o salão precisa dar quando ela reclama.

`BLOQUEADA` é o estado mais fácil de não modelar e o que mais informa. Não é falha (o sistema fez o
certo) e não é envio.

## 3. Grava antes de enviar

"O WhatsApp caiu por dez minutos" é rotina. Um envio direto na chamada do caso de uso não tem como
ser repetido; gravar primeiro transforma uma falha de rede numa linha para tentar de novo.

É a mesma forma do outbox de eventos, com uma diferença que importa: **esta tabela tem RLS**. O
payload carrega nome e horário da cliente, e `event_publication` não tem política de tenant — foi
por isso que RN-INF-009 proibiu PII lá. Aqui o dado pessoal é necessário e fica dentro do perímetro.

## 4. Um relógio só

`proxima_tentativa_em` é preenchido pela **aplicação**, não por `default now()`.

Foi um defeito real desta rotina: com o padrão do banco, o valor vinha do relógio do PostgreSQL e
era comparado pelo despachante contra o port `Relogio`. Dois relógios na mesma comparação — e
qualquer defasagem entre eles fazia a mensagem nunca ser vista. **Sem erro, sem log, só uma fila que
não anda.**

A `RN-INF-006` existe exatamente para isso, e eu a furei pelo caminho mais discreto possível: um
`default` no DDL.

## 5. O texto é validado no enfileiramento e montado no envio

Duas coisas diferentes, e as duas importam.

**Validado agora:** um parâmetro faltando descoberto na hora do envio viraria uma falha que o retry
repetiria cinco vezes, sem chance nenhuma de dar certo.

**Montado depois:** uma correção de redação alcança o que ainda está na fila. Um lembrete
enfileirado hoje sairia com o texto de ontem se o texto fosse fixado na entrada.

E faltar parâmetro é **erro**, não buraco: `"Oi {nome}, seu horário é amanhã"` sai, é entregue, e
faz o salão parecer quebrado para a cliente. É o defeito que ninguém reporta e todo mundo vê.

## 6. Pela conexão de manutenção, como o purgador

O despachante atende **todos** os salões: roda por fora de qualquer requisição e não tem tenant. É
exatamente o poder que `salao_app` não pode ter.

Mesmo padrão de `refresh_token` (V8): política própria para a role de plataforma e concessão
estreita — ele lê e atualiza o estado do envio, **nunca insere e nunca apaga**. Enfileirar continua
sendo operação da aplicação, dentro do escopo do tenant.

E `for update skip locked`, porque mais de uma instância roda isto ao mesmo tempo: sem o lock, as
duas pegam a mesma linha e a cliente recebe o lembrete duas vezes.

## 7. O provedor é um port, e hoje ele só registra

Nenhum provedor está contratado e nenhuma credencial existe — e o módulo inteiro (fila, template,
retry, opt-out) funciona e é testável sem isso. `MensageiroDeRegistro` é honesto sobre o que é:
loga que **não** enviou.

E não loga o texto. O log não tem RLS, é lido por quem opera a máquina e costuma sair do perímetro;
o texto carrega nome e horário da cliente. Vai canal, destino mascarado e tamanho.

## 8. Três erros meus, e o que cada um ensinou

**`Matcher.quoteReplacement` numa substituição literal.** `String.replace` não é regex — escapei
contra um problema que só existe em `replaceAll` e criei um que não existia: `R\$ 80,00`. O teste
com valor em dinheiro pegou, e ele existia justamente porque valor é o que uma mensagem de salão
carrega.

**`@ConditionalOnMissingBean` fora de auto-configuração.** Depende da ordem de registro dos beans,
que o Spring não garante. O sintoma foi um teste recebendo o mensageiro de produção em vez do duble
— e em produção seria o inverso, com o provedor real ignorado em silêncio. Virou `@Primary`, que é
o padrão para substituição.

**Colisão de versão de migração.** `V29` já existia, de RT-AGD-010. O Flyway reprovou a subida na
hora, mas eu só descobri depois de escrever a rotina inteira — conferir o último número passou a
ser o primeiro passo.

## 8b. Correção de 21/09/2026 — o mensageiro mentia

**Toda mensagem do sistema aparecia como enviada sem ter saído.** O `MensageiroDeRegistro` voltava
normalmente de `enviar`, e o despachante tratava a volta como entrega: `status = 'ENVIADA'`,
`enviada_em` preenchido. Lembrete de agendamento, comprovante, tudo. O custo era concreto: a cliente
diz "não recebi o lembrete", o salão vê "enviada" e conclui que ela ignorou.

Estava escrito no próprio contrato da porta — *"uma implementação que engolisse o erro e devolvesse
sucesso [...] a mensagem seria marcada como entregue sem ter saído"* — e a única implementação de
produção fazia exatamente isso. O módulo conversacional (RT-CNV-006) resolveu o mesmo problema na fila
dele meses depois, citando este mensageiro como o exemplo a não seguir, e a correção nunca voltou para
cá. Achado ao planejar a chamada automática da fila de espera (RT-AGD-010), cuja própria doc dizia que
registrar "oferta enviada" sem enviar grava uma mentira.

**A correção:**

- `Mensageiro.configurado()`, **sem valor padrão**: toda implementação precisa responder se entrega de
  verdade. Um padrão `true` deixaria o próximo mensageiro de mentira passar sem ninguém decidir nada.
- Sem provedor, o despachante grava **`SEM_PROVEDOR`** e nem chama o envio. Estado **final**, e essa é
  a diferença em relação à fila da equipe: lá a mensagem espera o provedor, porque o "marcado" continua
  servindo; aqui um lembrete de três semanas atrás não pode disparar no dia em que alguém ligar o
  WhatsApp. Decisão do dono.
- `V65` reclassifica o passado: toda linha `ENVIADA` passou pelo único mensageiro que existia, e ele
  nunca entregou nada. `enviada_em` volta a nulo, e um `check` passa a exigir a data em toda `ENVIADA`.
- `enviada_em` passa a vir do `Relogio` da aplicação, e não de `now()` do banco — a mesma lição do
  `proxima_tentativa_em` (seção 4), que tinha escapado justamente no método de marcar envio.

**Mutações da correção** — uma rodada, 5 mutações, cada uma sozinha. **5 mortas**.

| # | Mutação | Morta por |
|---|---|---|
| M01 | o mensageiro de registro volta a se dizer configurado | `MensageiroDeRegistroTest.nao_finge_que_entrega` |
| M02 | o despachante não pergunta se há provedor | `NotificacaoIT.sem_provedor_nao_finge` |
| M03 | sem provedor, a mensagem fica pendente em vez de final | `.sem_provedor_nao_finge`, `.sem_provedor_e_final` |
| M04 | `ENVIADA` gravada sem data de envio | `NotificacaoIT.enviada_tem_data` |
| M05 | o `check` de `V65` some e o banco aceita `ENVIADA` sem data | `NotificacaoIT.enviada_tem_data` |

## 9. Testes

- [x] `ModeloTest` — 8: preenche · **faltar parâmetro é erro, não buraco** · sobrar também é ·
      parâmetro repetido · **valor com cifrão** · modelo precisa de nome e texto · **a espera dobra
      e para de dobrar** · desiste na quinta
- [x] `MensageiroDeRegistroTest` — 1: o mensageiro de produção de hoje **não se diz configurado**
- [x] `NotificacaoIT` — 13 (+3 na correção: **sem provedor a mensagem diz que não saiu, e o envio nem é
      chamado** · **`SEM_PROVEDOR` é final** · enviada leva data, e o banco recusa `ENVIADA` sem ela):
      enfileira e despacha com o texto montado · **o mesmo fato não vira duas
      mensagens** · **marketing sem consentimento vira BLOQUEADA** · **o lembrete dispensa
      consentimento** · com consentimento passa · **provedor fora do ar não perde a mensagem** ·
      a falha adia em vez de repetir · modelo desconhecido · **parâmetro faltando é recusado agora,
      não no envio** · cliente sem telefone

## 10. Pendências

- [ ] **Nenhum provedor real.** O port existe e a implementação de hoje só registra — e, desde
      21/09/2026, diz isso: as mensagens ficam `SEM_PROVEDOR`, e não `ENVIADA`. Ligar o
      WhatsApp Business exige conta, credencial e **modelos aprovados previamente pela Meta** — o
      que muda o catálogo de modelos de "código" para "código espelhando o que foi aprovado lá"
- [ ] **Modelos não são editáveis pelo salão.** É uma boa ideia e traz junto uma pergunta que
      ninguém respondeu: quem revisa o texto que vai para a cliente? Um campo livre produziria
      mensagens que o provedor recusa, falha que só aparece no envio, uma por cliente
- [ ] **Não há tela.** Ver o que saiu, o que falhou e o que foi bloqueado é a primeira coisa que o
      salão vai pedir quando uma cliente disser que não recebeu
- [ ] **`FALHOU` não avisa ninguém.** A mensagem morre no banco com o motivo, e nada puxa a atenção
      de quem poderia agir
- [ ] **Sem expurgo.** A tabela cresce para sempre, como a de idempotência crescia antes de
      RT-INF-005 — mesmo modo de falha: nada quebra, o disco só enche, e enche primeiro em produção
- [ ] **Sem métrica de entrega.** Taxa de falha por provedor e por canal é o número que diz se o
      provedor está de pé, e ele não existe
