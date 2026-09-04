---
id: RT-AGD-001
titulo: Consultar disponibilidade
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-CAT-001, RT-CAT-003, RT-CAT-004, RT-EQP-002, RT-EQP-003, RT-EQP-004]
permissoes: [agenda:read:all, agenda:read:own, config:manage]
eventos: []
regras: [RN-AGD-001, RN-AGD-002, RN-AGD-004, RN-AGD-005, RN-AGD-008, RN-AGD-010, RN-AGD-011]
atualizado_em: 2026-09-04
---

# RT-AGD-001 — Consultar disponibilidade

## 1. Objetivo

*Que horário eu posso oferecer para quem quer este serviço neste dia?*

É a primeira rotina que **usa** tudo o que veio antes: os blocos do serviço (CAT-001), os recursos
(CAT-003), a duração de cada profissional (CAT-004), a jornada (EQP-002), os bloqueios (EQP-003) e
as habilidades (EQP-004). Nenhuma delas aparecia numa tela até agora.

## 2. A conta

**jornada − bloqueios − blocos ocupados**, com a receita encaixada no que sobrou.

As duas primeiras parcelas chegam prontas de `equipe.intervalosEm`. A terceira é desta rotina. O
encaixe é `BuscaDeEncaixe`, domínio puro.

## 3. Cada bloco pergunta uma coisa diferente

É aqui que o modelo de blocos se paga, e é a razão de a busca **não** ser "cabe um intervalo de N
minutos?".

| Bloco | O profissional precisa estar livre? | O recurso precisa estar livre? |
|---|---|---|
| `ATIVO` | **sim** | se o bloco exigir |
| `PAUSA` | **não** | se o bloco exigir |

A linha da `PAUSA` é a rotina inteira. Enquanto a química age, a profissional pode atender outra
cliente — e o teste `pausa_libera_encaixe` mostra os 40 minutos virando um corte vendido. Exigir
que ela estivesse livre jogaria fora 40 minutos por coloração, que num sábado é o horário inteiro
de outra pessoa.

E a coluna da direita vale também na pausa: a cliente segue ocupando a cadeira. Um modelo que não
saiba dizer isso vende a mesma cadeira duas vezes.

## 4. O bloco cabe em UMA janela, não na soma delas

Uma profissional livre das 9h às 12h e das 13h às 18h tem oito horas livres, e mesmo assim um
serviço de 45 minutos **não** começa às 11h30: ele atravessaria o almoço.

Parece óbvio escrito assim, e é o erro mais fácil de cometer — somar as durações disponíveis e
comparar com a duração do serviço. O teste `ativo_nao_atravessa_buraco` fixa isso.

## 5. Encostar não é conflito

`Janela` é fechada no início e aberta no fim, igual ao `tstzrange` das constraints. Não é
coincidência: **se o domínio e o banco discordassem sobre encostar, a aplicação ofereceria horários
que o banco recusaria na hora de gravar** — ou esconderia horários que ele aceitaria.

Com o fim fechado, todo horário cheio de agenda seria um conflito. A recepção aprenderia a marcar
às 10h01, e o dia perderia um minuto por cliente.

## 6. A grade é do salão

`granularidade_agenda_minutos` no estabelecimento, padrão 15.

Uns salões marcam de 15 em 15, outros de 30, e barbearia costuma trabalhar de 10. Um número fixo no
código estaria errado para metade deles — e o efeito não seria um erro, seria a agenda oferecendo
horários que aquele salão não usa.

A grade é alinhada ao **início do dia**, não ao início da jornada de cada um. Com o alinhamento por
profissional, duas colunas da grade da recepção mostrariam horários desencontrados (9h05 numa, 9h00
na outra) e o dia inteiro ficaria ilegível.

## 7. Cinco consultas, independente do tamanho da equipe

Quem executa · as receitas · as jornadas do dia · as ocupações · os recursos.

A grade da recepção pergunta por dez pessoas de uma vez, e um laço aqui viraria trinta idas ao
banco a cada troca de dia na tela. Para isso `equipe` ganhou dois métodos de conjunto —
`intervalosEm(Collection, data)` e `receitasDe(Collection, servico)` — pela mesma razão que
`ServicoApi.idsExistentes` nasceu em RT-EQP-004.

`receitasDe` continua sendo **uma pergunta só** por profissional, e não "executa?" mais "duração?":
quem tivesse de compor no chamador acabaria usando a duração do catálogo para quem leva outra, e a
agenda ficaria errada sem nada acusar.

## 8. Não executa o serviço é 422, não lista vazia

Perguntar a disponibilidade da Júlia para escova, quando a Júlia só faz corte, devolve
`ER-AGD-PROFISSIONAL_NAO_EXECUTA`.

Lista vazia diria "sem horário", e a recepção ofereceria outro dia — quando o problema é que aquela
pessoa não faz aquilo. É RN-AGD-001 aparecendo na consulta e não só na gravação.

## 9. As tabelas nascem aqui, na rotina que só as lê

`agendamento` e `agendamento_bloco` são criadas por esta rotina, embora quem escreve nelas seja
RT-AGD-002.

"Disponibilidade" é jornada menos bloqueios menos **blocos ocupados**, e a terceira parcela não
existe sem elas. Entregar a consulta sobre uma agenda que não pode ter nada marcado seria entregar
metade e chamar de rotina.

E como as constraints vieram junto, elas foram **verificadas mordendo**:

- `banco_recusa_sobreposicao_de_profissional` — duas clientes na mesma cadeira, recusado
- `banco_deixa_encostar` — 10h–11h e 11h–12h convivem
- `banco_garante_capacidade_por_unidade` — a mesma unidade duas vezes é recusada, a segunda unidade
  passa

O último é a dívida de RT-CAT-003 sendo paga: `recurso` é contável, `EXCLUDE` garante "no máximo
um", e é a coluna `unidade` que transforma isso em "no máximo três". Sem ela, a capacidade
dependeria de uma contagem da aplicação — verificação sob concorrência sem trava, que este projeto
não aceita em lugar nenhum.

Sem chave estrangeira para cliente, profissional e serviço: os três são de outros módulos, é a
decisão de RN-EQP-015, e por RN-CAT-011 a FK também não separaria os salões.

## 10. Testes

- [x] `BuscaDeEncaixeTest` — 10: serviço simples na grade · **a PAUSA não exige o profissional** ·
      **ATIVO não atravessa o almoço** · **capacidade do recurso: duas unidades aguentam duas** ·
      **recurso exigido na PAUSA bloqueia** · recurso desconhecido recusa · sem jornada · serviço
      maior que a jornada · a granularidade manda · **encostar não é conflito**
- [x] `JanelaESubtracaoTest` — 9: encostar não é cruzar · janela vazia recusada · **tomada no meio
      parte em duas** · tomadas nas pontas · tomadas sobrepostas entre si · tomada que cobre tudo ·
      tomada fora · **capacidade conta cruzamentos, não sobreposição** · recurso sem unidade
- [x] `DisponibilidadeIT` — 19: a jornada vira horários · **fora da jornada não aparece como
      livre** · sem jornada no dia · **bloqueio some** · **horário marcado some** · **cancelado
      devolve o horário** · **a PAUSA libera encaixe** · **lavatório esgotado tira o horário** ·
      dois lavatórios aguentam duas · qualquer profissional · **quem não executa é 422** · **a
      duração própria muda os horários** · serviço inexistente é 404 · **passado não aparece** ·
      isolamento entre salões · exige permissão · **as três constraints do banco, mordendo**

## 11. Pendências

- [ ] **`agenda:read:own` não filtra nada** — quem tem só essa permissão consulta a agenda de todo
      mundo. O filtro certo depende do vínculo usuário↔profissional, que não existe: hoje um
      usuário não sabe qual profissional ele é. É a maior lacuna desta rotina
- [ ] **Um dia por consulta** — a tela de marcação vai querer "os próximos sete dias com vaga", e
      sete chamadas é o caminho errado. O algoritmo aceita qualquer janela; falta a rota
- [ ] **Sem cache** — a consulta é pura leitura e repete muito. O `InvalidadorDeCache` e o
      `LISTEN/NOTIFY` já existem (RT-INF-*), e `JornadaAlterada` já está previsto como evento
      consumido pelo módulo. Medir antes
- [ ] **A pausa pode atravessar o fechamento do salão** — hoje o limite é o dia civil. Como toda
      receita termina em `ATIVO` e todo `ATIVO` exige jornada, na prática não acontece; mas o dia
      em que existir serviço terminando em pausa, isto vira defeito
- [ ] **`SubtracaoDeJanelas` duplica `SubtracaoDeIntervalos`** — uma em `Instant`, outra em
      `LocalTime`. A duplicação é deliberada (um genérico custaria mais em entendimento do que as
      trinta linhas), mas fica registrada
- [ ] **Não há tela**, como em toda a Fase 1
