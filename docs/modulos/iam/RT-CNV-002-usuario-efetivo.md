---
id: RT-CNV-002
titulo: Resolução de identidade por telefone → usuário efetivo e escopo
modulo: iam
fase: 4
perfil: completo
status: implementado
depende_de: [RT-CNV-001, RT-IAM-006, RT-IAM-007, RT-IAM-008]
permissoes: [usuario:manage]
eventos: []
regras: [RN-IAM-019, RN-IAM-020, RN-IAM-021, RN-IAM-022]
atualizado_em: 2026-09-12
---

# RT-CNV-002 — Usuário efetivo pelo telefone

## 1. Objetivo

Quando alguém escreve para o WhatsApp do salão, saber **em nome de quem** o agente pode agir — e
fazer com que ele só consiga o que essa pessoa conseguiria.

## 2. Mora no `iam`, apesar do prefixo CNV

Mesma situação de RT-FIN-008, que mora em `atendimento`. Tudo o que esta rotina precisa é do `iam`:
a tabela `usuario`, o mapa de permissões, a regra do segundo fator (RN-IAM-014), a auditoria. O
`conversacional` não pode ler nada disso — é interno — e reimplementar do lado de lá seria a regra
de autorização escrita duas vezes.

O que o `conversacional` recebe é um contrato: `UsuarioEfetivoApi`.

## 3. O projeto já tinha decidido quase tudo, e decidiu estrito

- **docs/05:** *"O bot não é usuário privilegiado. Ele age em nome de um usuário resolvido pelo
  telefone, herdando exatamente as permissões dele. Telefone não cadastrado → o bot responde como
  desconhecido e não executa nada que escreva."*
- **Pergunta 8:** cliente **não** agenda sozinho na v1 — identidade de cliente por telefone muda o
  modelo inteiro e abre superfície de abuso.
- **RT-IAM-006:** `BOT` tem permissão **vazia**; herda.
- **RT-IAM-008:** o ator `BOT` na auditoria ficou pendente *"quando o bot passar a agir em nome de
  alguém"*.

Consequência: o usuário efetivo é **alguém da equipe**. Cliente escrevendo é, para esta rotina,
`TELEFONE_DESCONHECIDO` — e é a resposta certa.

## 4. "Exatamente as permissões dele" tinha três buracos

Herdar exatamente, ao abrir o código, abriria o que a web não abre.

### Contornar o segundo fator — RN-IAM-020

Na web, ADMIN e GERENTE sem MFA na sessão **não acessam nada**: `segundoFatorEmDia` nega toda
requisição. O WhatsApp não tem como apresentar segundo fator. Herdar as permissões do dono pelo
celular seria dar pelo canal o que o próprio login dele não dá sem o código.

A decisão é **recusar**, e não aplicar um teto: qualquer teto ainda seria mais do que a web concede.
E vale mesmo com MFA inscrito — ter o autenticador configurado não diz quem está com o celular.

A regra é sobre `exigeMfa`, não sobre "ADMIN e GERENTE": o teste percorre todos os perfis.

### Escopo que ninguém impõe — RN-IAM-021

`agenda:read:own` promete "só a própria agenda". Os casos de uso só conferem a **autoridade**; o
dono, ninguém confere, porque `usuario` não tem vínculo com `profissional`. Pelo agente, um prompt
pedindo a agenda de todo mundo seria atendido.

O canal tira toda permissão `:own`. O profissional continua reconhecido — com `cliente:read`, e sem
o que ninguém sabe limitar.

### Número parecido — RN-IAM-022

O WhatsApp às vezes manda número brasileiro antigo sem o nono dígito. Casar "com ou sem o 9"
parece gentileza. Numa identidade que **concede permissão**, é personificação esperando para
acontecer. Comparação exata, e a pendência diz o caminho certo.

## 5. O contexto de segurança, e não uma checagem paralela

O agente poderia perguntar `usuarioEfetivo.pode("comanda:close")` antes de cada ação. Seria uma
segunda implementação da autorização.

`emNomeDe` monta um `Authentication` cujo principal é `EmNomeDe(usuarioId)` e cujas autoridades são
as do usuário efetivo, executa a ação e restaura o contexto anterior. Todo `@PreAuthorize` já
escrito passa a valer para o agente sem mudar uma linha — que é exatamente o que RT-IAM-007 previu
ao pôr a autorização no caso de uso e não no controller.

Quatro detalhes, cada um com teste:

| Detalhe | Por quê |
|---|---|
| resolve de novo **dentro** de `emNomeDe` | desativado depois da mensagem não age |
| recebe **telefone**, não id | quem chama não escolhe em nome de quem agir |
| recusa com alguém já autenticado | agir por uma pessoa dentro da sessão de outra é o próprio confused deputy |
| restaura no `finally` | com virtual threads o carrier é reaproveitado; contexto que sobra é herdado pelo próximo trabalho |

E a auditoria passa a responder "o agente cadastrou, a pedido da Bia": `ator = BOT`,
`usuario_id` = a pessoa. `EmNomeDe` mora em `shared.seguranca` porque `UsuarioAtual`, que é de
`shared`, precisa reconhecê-lo — e `shared` não depende de módulo nenhum.

## 6. O telefone do usuário

`V51`: `usuario.telefone`, só dígitos, único por salão incluindo inativos. Ligado por
`PUT /api/v1/usuarios/{id}/telefone`, com `usuario:manage` — é gesto de permissão, não de cadastro.
A auditoria guarda o número anterior e o novo, inteiros.

`Telefone` saiu de `cliente.internal.domain` para `shared.telefone`. É o precedente de
`UsuarioAtual`: o segundo lugar precisou da mesma resposta, e duas normalizações divergiriam — com o
efeito de a recepcionista ter o número gravado de um jeito, escrever de outro, e o agente tratá-la
como desconhecida. É regra de formato, como `Money`.

## 7. O defeito de infraestrutura que esta rotina encontrou

O teste `sem_tenant` — identificar sem tenant no escopo — derrubou **todos os logins dos testes
seguintes**, inclusive o `GestaoDeUsuariosIT` inteiro, com `cannot execute UPDATE in a read-only
transaction` e depois 25P02.

A causa estava em `TenantAwareTransactionManager.doBegin` (RT-INF-002): ele chamava `super.doBegin`
**antes** de conferir o tenant. O `super` já tinha pegado a conexão, marcado read-only e amarrado o
`EntityManager` à thread; a exceção saía e ninguém desfazia nada. A próxima `@Transactional` na
mesma thread **se juntava à transação morta**.

Em produção o estrago seria nos jobs: as threads `scheduling-N` são reaproveitadas, e uma única
chamada sem tenant deixaria aquela thread quebrada para sempre, com a conexão fora do pool.

Corrigido conferindo o tenant **antes** de adquirir qualquer recurso, e desfazendo o que o `super`
montou se o `set_config` falhar depois. `TenantIsolamentoIT.recusa_sem_tenant_nao_prende_a_thread`
guarda, e a mutação — voltar a ordem antiga — o deixa vermelho mostrando o `ConnectionHolder` e o
`EntityManagerHolder` ainda presos à thread.

O teste que já existia (`transacao_sem_escopo_falha`) provava a recusa e nunca olhou o que sobrava
depois dela.

## 8. O que esta rotina não faz, de propósito

**Não identifica cliente.** A pergunta 8 fechou isso para a v1, telefone de cliente não é único
(RN-CLI-005: mãe e filha dividem o número) e ler a ficha de uma cliente a cada mensagem de
desconhecido contraria a minimização da LGPD. O aviso de IA à cliente (RT-CNV-008) precisa saber
que o remetente **não** é da equipe — e isso esta rotina responde.

**Não consome `MensagemDeClienteRecebida`.** A identidade é resolvida na hora da ação, não na
chegada: guardar "quem escreveu, com quais permissões" junto da mensagem seria exatamente o
retrato velho que RN-IAM-019 proíbe. O primeiro consumidor do evento — e a verificação de que ele
passa a existir no outbox — fica com RT-CNV-003 ou RT-CNV-004.

## 9. Testes obrigatórios

- [x] `PoliticaDoUsuarioEfetivoTest` — 8: recepção com as permissões dela · **todo perfil que exige
  MFA é recusado** · **nenhum usuário efetivo carrega `:own`** · profissional perde o `:own` e
  mantém o resto · **nunca mais que o perfil** · painel e bot · inativo, com o motivo certo ·
  bloqueio em curso recusa, vencido não.
- [x] `UsuarioEfetivoIT` — 22: recepção reconhecida pelo telefone gravado com máscara · desconhecido
  · **sem o nono dígito não casa** · **dono recusado mesmo com MFA inscrito** · profissional sem
  `:own` · inativo · bloqueado · **telefone de outro salão não é visto** · sem tenant falha fechado
  · **pode o que a recepção pode** · **não pode o que a recepção não pode** (preço, gestão de
  usuário) · **auditoria diz BOT, a pedido de quem** · **resolvido na hora da ação** · contexto
  restaurado mesmo quando a ação falha · recusa dentro da sessão de outra pessoa · telefone de
  colega recusado nomeando quem tem · mesmo telefone em dois salões · vazio tira o telefone ·
  telefone inválido · **sem `usuario:manage`, logado, não liga telefone** · trilha com número
  anterior e novo · lista mostra o telefone.
- [x] `TenantIsolamentoIT.recusa_sem_tenant_nao_prende_a_thread` — verificado por mutação.
- [x] `TelefoneTest` — 19, movido para `shared.telefone` sem mudar uma asserção.

### Um teste que provava outra coisa

A primeira versão de `sem_permissao` rodava sem ninguém logado. O Spring recusava com
`AuthenticationCredentialsNotFoundException` — que não é `AccessDeniedException`, e prova "sem login
não entra", não "a recepção não liga telefone". Agora roda logado, com as permissões da recepção.

## 10. Pendências

- [ ] **Nono dígito.** Número brasileiro antigo chega do WhatsApp sem o 9 e não casa. O caminho
      certo não é aproximar: é **confirmar o pareamento** — mandar um código ao número pelo próprio
      canal e gravar o `wa_id` que respondeu. Até lá, quem tiver esse número não é reconhecido.
- [ ] **O dono não usa o agente.** Por RN-IAM-020, ADMIN e GERENTE ficam de fora até existir um
      segundo fator pelo canal. Não pode ser o TOTP digitado na conversa: ele iria parar em
      `acao_ia`, que guarda a mensagem original por 12 meses.
- [ ] **Vínculo `usuario → profissional`.** É o que devolveria o `:own` ao canal — e fecharia o
      mesmo buraco na web, onde ele também existe.
- [ ] **Rate limit por telefone** (docs/05). Entra com a orquestração (RT-CNV-004), onde há custo
      a proteger.
- [ ] **Um telefone por conta.** Quem usa dois números para trabalhar escolhe um.
- [ ] **Não há tela para ligar o telefone.** A rota existe; o lugar natural é a gestão de usuários.
- [ ] **O caminho de falha do `set_config` depois do begin não tem teste.** Provocá-lo exigiria
      derrubar a conexão no meio do `doBegin`; está escrito com o mínimo de passos.
- [ ] **A resposta ao remetente não pode revelar o motivo.** `MotivoDaRecusa` é para diagnóstico;
      quem montar a resposta em RT-CNV-004 precisa responder igual em todos os casos.
