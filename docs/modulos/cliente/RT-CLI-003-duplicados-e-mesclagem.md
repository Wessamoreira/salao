---
id: RT-CLI-003
titulo: Detecção de duplicado no cadastro + mesclagem
modulo: cliente
fase: 1
perfil: completo
status: implementado
depende_de: [RT-CLI-002]
permissoes: [cliente:read, cliente:write]
eventos: []
regras: [RN-CLI-013, RN-CLI-014, RN-CLI-015, RN-CLI-016, RN-CLI-017]
atualizado_em: 2026-08-30
---

# RT-CLI-003 — Duplicado e mesclagem

## 1. Objetivo

Perceber que a pessoa que está sendo cadastrada **já tem ficha**, e juntar as duas quando tiver
acontecido — sem impedir o cadastro e sem apagar histórico.

É aqui que a decisão de RT-CLI-001 de não proibir telefone repetido é cobrada: o sistema precisa
distinguir *a mesma pessoa cadastrada duas vezes* de *mãe e filha que dividem o número*.

## 2. A medição que mudou o desenho

O plano dizia "trigram". O caminho óbvio seria: escore de similaridade acima de um limiar é
duplicado. Antes de escolher o limiar, medi no próprio Postgres, com pares reais de salão:

| A | B | escore | são a mesma pessoa? |
|---|---|---:|---|
| maria silva | mariana silva | **0,733** | **não** |
| maria silva | maria silva santos | 0,667 | sim |
| jose antonio | jose antonio da costa | 0,591 | sim |
| maria silva | maria silvana souza | 0,550 | não |
| maria silva | julia silva | 0,412 | não (mãe e filha) |
| ana | ana paula | 0,400 | sim |

**Não existe limiar que separe essas linhas.** O par de pessoas diferentes com maior escore (0,733)
está *acima* de dois pares que são a mesma pessoa. Qualquer corte junta gente que não deve ser
junta ou perde duplicado óbvio — e o primeiro erro é bem pior, porque mesclar dá trabalho para
desfazer.

Também não adianta ordenar por escore: "Mariana Silva" apareceria antes de "José Antônio da Costa".

## 3. O que separa de verdade: o primeiro nome

Mãe e filha compartilham o **sobrenome**. A mesma pessoa cadastrada duas vezes compartilha o
**primeiro nome**.

É o que distingue "Maria Silva"/"Júlia Silva" (0,412, família) de "Ana"/"Ana Paula" (0,400, mesma
pessoa) — dois pares com escore praticamente idêntico e significados opostos.

Daí os três graus (RN-CLI-013):

| Grau | Quando | O que a tela faz |
|---|---|---|
| `PROVAVEL` | mesmo telefone **e** mesmo primeiro nome | "É esta pessoa?" |
| `POSSIVEL` | sem telefone, mesmo primeiro nome e escore ≥ 0,5 | vale conferir |
| `MESMO_TELEFONE` | mesmo telefone, primeiro nome diferente | contexto: "este número é da Maria" |

`MESMO_TELEFONE` **não é aviso de duplicidade**. Tratar família como erro treinaria a recepção a
ignorar o aviso — e aí ele deixaria de servir para o caso em que está certo.

A inicial conta como primeiro nome ("M. Silva" é Maria Silva), mas o prefixo é limitado a dois
caracteres: aceitar prefixo de qualquer tamanho faria "Luís" e "Luísa" casarem, que num salão é,
com frequência, pai e filha.

O escore não sumiu — ele continua como confirmação secundária quando não há telefone. Só deixou de
ser quem decide.

## 4. Avisar, nunca impedir

A detecção é um endpoint **separado** do cadastro, consultado enquanto se digita (R-UX-15).

Embutir a checagem no `POST` obrigaria a escolher entre bloquear e ignorar. Bloquear tem o mesmo
defeito da `unique` em telefone que RT-CLI-001 recusou: recusar cadastro legítimo ensina a recepção
a contornar, e o contorno é sempre pior — "Maria 2", um telefone inventado, um sobrenome trocado.
Consultando antes, quem decide é quem está olhando para a cliente.

## 5. Mesclar é redirecionar, não apagar

A ficha absorvida continua existindo e passa a apontar para a sobrevivente (RN-CLI-014).

Tudo que já guardou aquele `cliente_id` — agendamento, comanda, extrato de comissão, e o que a
Fase 2 ainda vai criar — continua resolvendo para a pessoa certa **sem ser reescrito**. Apagar
exigiria encontrar e repontar todas as referências numa transação só, e a que fosse esquecida
viraria um registro órfão apontando para uma ficha inexistente.

O redirecionamento falha do lado seguro: **referência esquecida continua achando a pessoa**.

Isto é o que torna a rotina implementável agora, antes de a agenda existir. Não há tabela para
repontar, e não vai precisar haver.

**As cadeias ficam planas.** Ao mesclar, quem já apontava para a absorvida passa a apontar direto
para a sobrevivente. Sem isso, A→B→C exigiria seguir ponteiros em toda leitura — e leitura
recursiva é onde um ciclo acidental vira laço infinito em produção. Com um salto só, a consulta é
um `left join` e não tem como girar.

## 6. Campos: completa vazio, nunca sobrescreve

O dado que já está na ficha sobrevivente foi conferido por alguém. Um dado bom substituído por
outro bom não melhora nada; um bom substituído por um velho é perda silenciosa (RN-CLI-015).

A observação é a exceção: as duas são concatenadas. Texto livre costuma guardar a única anotação
que existe sobre a pessoa — "alérgica a amônia" — e descartá-la seria perder informação que
ninguém saberia ter existido.

## 7. Permissão: a mesma de cadastrar

Quem cria o duplicado é a recepção, no balcão, e é ela quem percebe na hora. Exigir um perfil mais
alto significaria duplicado acumulando até alguém com a permissão certa aparecer — e ficha
duplicada só piora com o tempo, porque cada uma vai juntando histórico próprio.

## 8. A regra de arquitetura que precisou ser afinada

`ArquiteturaTest.dinheiro_nunca_e_double_ou_float` reprovou o build: o escore de similaridade é um
`double`.

A regra proíbe ponto flutuante em **todo** campo, e não só onde há dinheiro — de propósito, porque
nomear "os campos que são dinheiro" exigiria acertar a intenção de quem escreveu, e é justamente
quem não percebeu que aquilo era dinheiro que a regra precisa pegar.

A saída não foi afrouxar nem contorcer o código: a regra ganhou uma **lista explícita** do que não
é dinheiro, com o motivo de cada entrada. Acrescentar um nome ali é um gesto deliberado, aparece na
revisão e obriga a justificar — o oposto de um `double` que entra despercebido num cálculo de
comissão. Verificado do jeito certo: um `double valorPago` fora da lista continua reprovando o
build.

## 9. Testes

- [x] `SemelhancaTest` — 9, com os escores **medidos**, não inventados: mesma pessoa · mãe e filha ·
      **Mariana não é Maria mesmo com escore alto** · inicial conta · prefixo longo não conta ·
      sem telefone exige nome parecido · acento e caixa
- [x] `DuplicadosEMesclagemIT` — 15: duplicado óbvio · família como contexto · Mariana · possível
      sem telefone · sem relação não avisa · isolamento na detecção · completa vazios sem
      sobrescrever · **id antigo continua resolvendo** · absorvida some da busca · cadeia fica
      plana · mesclar em ficha já absorvida resolve · não mescla em si mesma · não mescla duas
      vezes · isolamento na mesclagem · sem `cliente:write` é recusado
- [x] `ClientesWebIT` — 3 novos: graus no JSON · mesclar pelo HTTP com id antigo resolvendo ·
      corpo inválido recusado na borda
- [x] `ArquiteturaTest` — lista do que não é dinheiro, verificada por violação deliberada

## 10. O que a rotina revelou e foi corrigido

**A busca continuava devolvendo a ficha absorvida.** As consultas de RT-CLI-002 filtravam só
`anonimizado_em`. Pego por `absorvida_some_da_busca`. Como os índices de V14 passaram a ser parciais
por `mesclado_para is null`, a consulta sem esse filtro também deixaria de usar índice.

**`BuscaIndexadaIT` media uma cópia do SQL, não o SQL.** Tinha a consulta transcrita à mão, e ela
já havia divergido do produto nesta migração — medindo com precisão um plano que ninguém executava.
Agora o teste lê a constante do próprio repositório e só troca os parâmetros nomeados por literais.

## 11. Pendências

- [ ] **Desfazer a mesclagem.** A informação está toda lá (a ficha absorvida continua intacta), mas
      não há operação para reverter. Uma mesclagem errada hoje se conserta no banco à mão
- [ ] **Repontar referências quando a agenda existir.** Hoje o redirecionamento resolve tudo; se a
      leitura por `cliente_id` virar gargalo, vale um trabalho que repõe as referências em segundo
      plano. Rever no fim da Fase 2
- [ ] **Detecção não roda no cadastro em si**, só no endpoint consultado antes. Duas recepções
      cadastrando a mesma pessoa ao mesmo tempo criam duas fichas — e vão continuar criando. É
      aceitável (a mesclagem existe para isso) e está registrado para não parecer esquecimento
- [ ] Mesclagem não considera qual ficha tem mais histórico; quem escolhe é a recepção. Reavaliar
      quando houver histórico para comparar (Fase 2)
