---
id: RT-AGD-009
titulo: "Recorrência: gera ocorrências materializadas"
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-AGD-002, RT-AGD-005]
permissoes: [agenda:write:all]
eventos: []
regras: [RN-AGD-013, RN-AGD-022]
atualizado_em: 2026-09-04
---

# RT-AGD-009 — Recorrência

## 1. Objetivo

"A Marilda vem toda quinta às 14h."

## 2. Materializar, não calcular

O plano já dizia "ocorrências materializadas", e a razão vale escrever:

1. **A exclusion constraint precisa de linhas.** Uma ocorrência virtual não impede ninguém de
   marcar por cima dela — e a cliente semanal seria justamente a que perderia o horário.
2. **Cada ocorrência é cancelável e movível sozinha.** A Marilda vai viajar na terceira quinta:
   isso é um cancelamento comum (RT-AGD-005), não uma exceção dentro da regra.
3. **A grade lê linhas.** Misturar agendamentos reais com ocorrências calculadas faria toda
   consulta da agenda ter dois caminhos.

O preço é o horizonte: não dá para materializar até o infinito. Hoje a recepção diz quantas, com
teto de 52.

## 3. A mesma pergunta de RT-AGD-008, com resposta oposta

| | RT-AGD-008 (empurrar) | RT-AGD-009 (série) |
|---|---|---|
| Resultado parcial | **Proibido** | **Correto** |
| Por quê | O empurrão é *um ato só* — o dia andou | Cada ocorrência é um *compromisso independente* |

Lá, uma agenda empurrada pela metade é pior do que uma não empurrada. Aqui, a terceira quinta é
feriado, e recusar as outras onze por causa dela seria absurdo.

**O critério:** se as partes são consequências de um mesmo fato, é tudo ou nada; se são compromissos
separados que por acaso foram pedidos juntos, é parcial.

Por isso `SerieCriada` devolve `naoCouberam` junto com o que coube — informação que a recepção
resolve uma a uma, e não um erro que apaga o trabalho inteiro.

## 4. O parcial exige uma transação por ocorrência

E aqui a decisão de negócio tem uma tradução técnica exata, que quase deu errado.

Numa transação única, o `ErroDeDominio` de uma ocorrência recusada marcaria a transação inteira
como *rollback-only*. Pior: a violação da exclusion constraint **aborta a transação no PostgreSQL**,
e nenhum comando posterior funciona sem savepoint. O resultado seria *"não marca nada"* com
aparência de *"marcou três"*.

Então `CriarRecorrenciaUseCase` **não** é `@Transactional`, e cada ocorrência abre a sua pela
anotação que já está em `CriarAgendamentoUseCase`.

Encerrar é o contrário: `@Transactional`, tudo ou nada. Um cancelamento só falha por versão
desatualizada ou status impossível — e qualquer um dos dois significa que a série mudou debaixo de
quem pediu o encerramento.

## 5. Três armadilhas de proxy numa rotina só

Tirar o `@Transactional` do caso de uso revelou, em sequência:

1. **`EstabelecimentoApi.fusoDe` nunca abriu transação.** Chamava `configuracao` em `this` —
   auto-invocação, sem proxy — e só funcionava porque *todo chamador já vinha de dentro de uma
   transação*. Sem transação não há `SET LOCAL app.tenant_id`, e a RLS escondia a linha: o método
   estourava "estabelecimento não encontrado" para um tenant que existe. Defeito latente numa
   rotina entregue, exposto pelo primeiro chamador sem transação. Varri as demais APIs públicas de
   módulo — era o único.
2. **A trilha é `MANDATORY` de propósito** ("auditoria precisa commitar junto com o fato que
   descreve"). Com o caso de uso sem transação, ela recusa — corretamente. Daí o colaborador
   `RegistroDaSerie`, que abre transação para as escritas de bookkeeping sem envolver as
   ocorrências.
3. **`new RegistroDaSerie(...)` não resolve nada.** Objeto criado com `new` não tem proxy, e o
   `@Transactional` dele é decoração. O sintoma foi idêntico ao da auto-invocação, e a causa também.
   Virou bean.

As três são a mesma família: *anotação de transação só vale onde o Spring consegue interceptar*.

## 6. As datas somam semanas no fuso do salão

Somar 168 horas ao instante atravessa o horário de verão e a cliente chega uma hora errada. As
ocorrências são calculadas em `ZonedDateTime` no fuso do estabelecimento (ADR-0009), o que mantém
"quinta às 14h" sendo quinta às 14h dos dois lados da virada.

## 7. Encerrar cancela o futuro, com motivo

Cada ocorrência futura passa por `CancelarAgendamentoUseCase`, com motivo e trilha. Apagar as linhas
em massa seria mais rápido e perderia exatamente o que RT-AGD-005 existe para guardar — o histórico
da cliente diria que ela nunca teve aquele horário.

As que já aconteceram ficam onde estão: são o registro do que foi feito.

## 8. Testes

- [x] `RecorrenciaIT` — 12: **quatro quintas seguidas** · quinzenal pula uma semana · as
      ocorrências nascem confirmadas e ligadas à série · **marca o que cabe e devolve o que não
      coube** · **uma recusa não envenena a transação das outras** · série inteira sem espaço
      devolve zero e não erro · uma ocorrência só não é série · limite de um ano · **encerrar
      cancela o futuro** · cada cancelamento tem motivo · encerrar exige motivo · isolamento entre
      salões

## 9. Pendências

- [ ] **Não há horizonte rolante.** A série termina quando as N ocorrências acabam, e ninguém
      estende. O certo é um job que mantenha oito semanas à frente — e ele depende de decidir o que
      fazer quando a nova ocorrência não couber, sem ninguém olhando
- [ ] **Mensal não existe.** "Toda primeira sexta" precisa decidir o que fazer quando o mês tem
      cinco sextas e quando o dia 31 não existe. Ficou de fora inteiro em vez de resolvido pela
      metade
- [ ] **As recusadas não viram nada.** `naoCouberam` volta na resposta e some: não há lista de
      pendências para a recepção resolver depois. Deveria virar fila de espera (RT-AGD-010)
- [ ] **Alterar a série não existe** — mudar o horário de "toda quinta" hoje é encerrar e criar
      outra, o que cancela e recria as ocorrências futuras, com todas as notificações que isso
      implica
- [ ] **`RegistroDaSerie` é uma classe que existe por causa do Spring**, não do domínio. É honesto
      e está explicado, mas é o tipo de coisa que some numa refatoração desatenta e leva a
      transação junto
