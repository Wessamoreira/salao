---
id: RT-CAT-004
titulo: Duração por profissional
modulo: equipe
fase: 1
perfil: completo
status: implementado
depende_de: [RT-EQP-004, RT-CAT-001]
permissoes: [usuario:manage, agenda:read:all]
eventos: []
regras: [RN-CAT-013, RN-CAT-014]
atualizado_em: 2026-09-04
---

# RT-CAT-004 — Duração por profissional

> O id diz `CAT`, o módulo é **`equipe`**. A duração de alguém num serviço é atributo da relação
> profissional × serviço, que mora em `habilidade` (RT-EQP-004). Ver a seção "Correções de recorte"
> em [09-plano-de-implementacao.md](../../09-plano-de-implementacao.md).

## 1. Objetivo

A Ana faz escova em 30 minutos e a Júlia em 45.

Sem isto, o sistema promete o mesmo horário para as duas: ou a agenda da Ana fica com um buraco de
15 minutos em cada escova, ou a da Júlia atrasa 15 — e o atraso acumula pelo dia inteiro, até a
última cliente esperar uma hora.

## 2. A pausa não encolhe

É a decisão da rotina (RN-CAT-013).

Se a Ana faz coloração em 90 minutos em vez de 100, ela é mais rápida **aplicando e finalizando**.
A química continua levando 40 minutos, porque é **reação, não habilidade**.

| | Catálogo | Ana, 90 min |
|---|---|---|
| Aplicar (ATIVO) | 30 | 25 |
| Química (PAUSA) | 40 | **40** |
| Lavar e finalizar (ATIVO) | 30 | 25 |

Um modelo que escalasse o serviço inteiro por um fator daria 36/36/18 — mandaria lavar o cabelo com
a química agindo há 36 minutos em vez de 40. O sistema não erraria em nada que se possa medir na
tela; erraria no cabelo da cliente.

Daí também o piso: dizer que alguém faz a coloração em 40 minutos é dizer que ela aplica, espera a
química e finaliza no tempo em que **só a química acontece**. O mínimo é a pausa mais um minuto por
bloco ativo.

## 3. O arredondamento precisa de regra

50 minutos de trabalho repartidos entre blocos de 30 e 20 não dão números inteiros.

A regra: cada bloco ativo recebe **1 minuto de base** — nenhum pode desaparecer — e o resto é
proporcional, com os minutos que sobram do arredondamento indo para os blocos de maior fração e,
em empate, para o primeiro.

Não é preciosismo. Sem uma regra fixa, a mesma entrada produziria receitas diferentes conforme a
ordem de iteração, e a agenda mostraria **horários que mudam sozinhos** entre dois carregamentos
da mesma tela.

## 4. Por que é coluna, e não tabela

Duas razões, e a segunda quase virou defeito.

**Conceitual:** duração e habilidade são a mesma relação. Tabelas separadas permitiriam "a Ana leva
30 minutos numa coloração que ela não faz" (RN-CAT-014).

**Mecânica:** `substituirHabilidades` apaga e regrava as linhas a cada edição (RT-EQP-004). Numa
tabela irmã, **toda edição de habilidade apagaria as durações em silêncio** — exatamente o defeito
que RT-CAT-003 evitou gravando o recurso exigido junto com o bloco. Segunda aparição da mesma
lição, e desta vez ela foi lembrada antes de custar alguma coisa: a checagem foi olhar como
`substituirHabilidades` escrevia, antes de escolher onde guardar.

O teste `duracao_sobrevive_a_edicao` existe só para isso.

## 5. Uma pergunta, não duas

`ProfissionalApi.receitaDe(profissional, servico)` devolve os blocos **já reescalados**, ou vazio se
a pessoa não executa o serviço, está inativa, ou o serviço não existe.

Poderia ser `executa()` + `duracaoDe()` + a composição no chamador. Seria pior: quem esquecesse de
compor usaria a duração do catálogo para alguém que leva quinze minutos a mais, e **nada acusaria**
— a agenda apenas ficaria errada. É a mesma razão de `executa` já filtrar inativo por dentro.

## 6. O defeito que virou regra de arquitetura

Acrescentei um *overload* de conveniência ao caso de uso — três argumentos em vez de quatro,
delegando ao método anotado. Sem anotação nenhuma, "porque quem valida é o outro".

Não é. **A chamada de dentro da própria classe não passa pelo proxy do Spring.** O overload virou um
caminho de entrada sem transação, sem tenant e **sem autorização** — um caso de uso que ignorava a
permissão sem que nada no código parecesse errado.

Quem pegou foi `recepcao_nao_altera`, um teste de permissão que já existia por outro motivo. Isso é
sorte, e sorte não é método. Virou a regra `caso_de_uso_nao_tem_porta_sem_autorizacao`: todo método
público de `*UseCase` precisa de `@PreAuthorize`.

A regra tem lista de exceção explícita, como a de dinheiro — sete casos de uso do `iam` cuja tranca
é a **identidade** e não uma permissão: login, refresh, provisionamento (antes de existir usuário
autenticado) e autosserviço sobre a própria conta (senha, sessões, MFA, capacidades). Acrescentar
um nome ali obriga a escrever por que aquela porta não tem tranca de permissão.

Verifiquei que a regra morde reintroduzindo o overload de propósito: o build reprovou apontando o
método exato.

## 7. E um `wasNull` na ordem errada

A leitura da coluna anulável fazia:

```java
int duracao = rs.getInt("duracao_minutos");
return new Linha(rs.getObject("servico_id", UUID.class), rs.wasNull() ? null : duracao);
```

`wasNull()` reporta sobre a **última coluna lida**, e os argumentos são avaliados da esquerda para
a direita: o `getObject` roda primeiro, e `wasNull` passa a falar do `servico_id`, que nunca é nulo.
Toda duração ausente virava **zero** — e zero é uma duração, o que fazia a leitura estourar no
invariante do domínio.

Estourar foi sorte de novo: se `Habilidades` aceitasse zero, o defeito teria virado receita de
duração zero na agenda. Um invariante bem posto transforma um bug silencioso em um alto.

> **O mesmo erro voltou em RT-ARQ-001**, com o comentário que o explicava escrito quinze arquivos
> ao lado. Foi o que fez virar `Colunas.inteiroOuNulo` / `Colunas.longoOuNulo` e regra de
> arquitetura (RN-INF-014): um comentário não impede o erro; uma função que não tem como ser usada
> errado, sim.

## 8. Testes

- [x] `SequenciaDeBlocosTest` — 7 novos: **a pausa não encolhe** · bloco único · **o arredondamento
      fecha exato e é determinístico** · nenhum bloco ativo some · **duração menor que a pausa é
      recusada** · o mínimo absoluto · reescalar preserva os recursos
- [x] `HabilidadesTest` — 4 novos: **duração exige habilidade** · regime aberto aceita qualquer
      duração · duração zero é recusada · sem duração é vazio, não zero
- [x] `DuracaoPorProfissionalIT` — 12: **a Ana em 30, a Júlia em 45** · **a química continua em
      40** · **a duração sobrevive a uma edição de habilidade** · tirar a duração volta ao catálogo
      · duração menor que a pausa é 422 · duração sem habilidade é 422 · serviço de outro salão é
      422 · duração zero é 422 · **quem não executa não tem receita** · inativo não tem receita ·
      serviço inexistente não estoura · a receita reescalada mantém os recursos
- [x] `ArquiteturaTest.caso_de_uso_nao_tem_porta_sem_autorizacao` — verificada mordendo

## 9. Pendências

- [ ] **Mudar a duração não avisa quantos agendamentos futuros deslocam** — terceira vez que esta
      pendência aparece (RT-CAT-001 para a duração do serviço, RT-CAT-003 para a quantidade de
      recurso). Quando a agenda existir, vale resolver as três de uma vez
- [ ] **A duração é total, e serviços de muitos blocos distribuem por proporção** — se um salão
      precisar dizer "a Ana é mais rápida só na finalização", não dá. Não apareceu como
      necessidade; o modelo por bloco seria um formulário por profissional por serviço por etapa
- [ ] **Nada usa `receitaDe` ainda** — ela existe para RT-AGD-001, que é a próxima
- [ ] **Não há tela**, como em toda a Fase 1
