---
id: RT-CAT-001
titulo: Serviço com blocos ATIVO/PAUSA, duração e recursos exigidos
modulo: catalogo
fase: 1
perfil: completo
status: implementado
depende_de: [RT-INF-002]
permissoes: [config:manage, agenda:read:all]
eventos: []
regras: [RN-CAT-001, RN-CAT-002, RN-CAT-003, RN-CAT-004, RN-CAT-010, RN-CAT-011]
atualizado_em: 2026-09-01
---

# RT-CAT-001 — Serviço com blocos

## 1. Objetivo

Descrever o que o salão vende — e, principalmente, **como o tempo do serviço se distribui**.

## 2. A peça que distingue um sistema de salão de uma agenda genérica

Coloração não é "100 minutos".

É: aplicar (30 min, profissional ocupada), a química agir (**40 min, profissional livre**, cliente
esperando), lavar e finalizar (30 min, ocupada de novo).

Uma agenda que trate isso como um bloco único de 100 minutos **joga fora 40 minutos de trabalho por
coloração**. Num sábado, são o horário inteiro de outra cliente. É a diferença entre o sistema
render dinheiro para o salão e apenas registrar horários.

Daí a sequência de blocos `ATIVO`/`PAUSA` (RN-CAT-001), e daí o serviço responder duas perguntas
diferentes:

| Pergunta | Resposta para coloração |
|---|---|
| Quanto tempo a cliente fica no salão? | 100 min |
| Quanto custa do profissional? | 60 min |
| Quanto sobra para vender a outra pessoa? | 40 min |

## 3. Duas invariantes, e as duas são de negócio

**Começa e termina em ATIVO** (RN-CAT-002).

Um serviço que começa em pausa faria a cliente chegar e esperar sem ninguém ter feito nada — não é
serviço, é atraso. Um que termina em pausa encerraria o atendimento com a química ainda agindo: a
agenda marcaria a próxima cliente, e a primeira ficaria sentada com tinta no cabelo.

**ATIVO e PAUSA se alternam** (RN-CAT-003).

Dois `ATIVO` seguidos são um `ATIVO` com a soma das durações. Permitir as duas formas deixaria a
mesma receita ser escrita de várias maneiras — e duas fichas do mesmo serviço com blocos diferentes
é o começo de um relatório que não fecha.

Juntas, garantem que a sequência é sempre `A`, `APA`, `APAPA`: número ímpar de blocos, começando e
terminando em ativo. Luzes com dois processos cabem; receita incoerente não.

## 4. Duas consultas, não um join

Serviço e blocos são lidos separadamente. O join devolveria a cabeça repetida uma vez por bloco —
com cinco blocos e trinta serviços, é mais dado trafegando do que as duas consultas somadas.

E os blocos de **todos** os serviços da listagem vêm numa consulta só, não uma por serviço. O
cardápio de um salão tem dezenas de itens; trinta consultas para montar uma tela é o tipo de coisa
que só dói quando o salão cresce.

## 5. A quinta ocorrência, tratada antes de acontecer

`SequenciaDeBlocos` recusa receita inválida com `IllegalArgumentException` — correto no domínio, e
**500 na borda**.

Depois de rota inexistente, parâmetro malformado, permissão negada e construção de domínio no
controller (RT-EQP-002), o padrão já é conhecido: *exceção que a borda pode provocar precisa de
tradutor*. Desta vez `EtapaBruta` entrou junto com o código, e o teste do caso (`duração zero`)
nasceu passando.

É a primeira vez neste projeto que a lição foi aplicada **antes** do defeito aparecer.

## 6. Permissão: cardápio é do dono, não do balcão

Alterar exige `config:manage`; ler basta `agenda:read:*` (RN-CAT-004).

Mudar a duração de um serviço **muda a agenda de todo mundo** — o encaixe, a disponibilidade, o
horário que já foi prometido à cliente. Não é operação de balcão. Mas a recepção precisa ver o
cardápio para marcar, e esconder isso dela impediria o trabalho.

## 7. Desativar, não apagar

O histórico de atendimento aponta para o serviço. Apagar quebraria o relatório do que foi vendido —
e o extrato de comissão, calculado sobre ele.

Mesma decisão de cliente e profissional. E o contraste com RT-EQP-003 continua valendo: lá o
bloqueio é apagado de verdade, **porque nada aponta para ele**. A regra não é "nunca apague" — é
"não apague o que sustenta o passado".

## 8. Testes

- [x] `SequenciaDeBlocosTest` — 9: **a pausa separa o tempo da cliente do tempo do profissional** ·
      serviço simples · **não começa em pausa** · **não termina em pausa** · não repete o tipo ·
      duas pausas alternadas (luzes) · sem bloco · duração positiva · imutável
- [x] `CatalogoDeServicosIT` — 13: coloração com pausa · **a ordem dos blocos persiste** · serviço
      simples · termina em pausa recusado · tipos repetidos recusados · **duração zero é 422, não
      500** · sem etapa · nome único ignorando caixa · mesmo nome em salões diferentes · desativar
      mantém o serviço · listagem traz os blocos · isolamento entre salões · **a recepção lê mas
      não altera**

## 9. O que o plano pediu e não foi entregue — **fechado em RT-CAT-003**

O título da rotina diz "duração e **recursos exigidos**". Os recursos não entraram, e não por
esquecimento: `RT-CAT-003` é a rotina que cria a entidade `Recurso` (lavatório, cadeira, sala).

Não dá para exigir um recurso que ainda não existe. O vínculo bloco↔recurso pertence a CAT-003. É
um defeito de recorte do plano, do mesmo tipo que a ordenação de `RT-EQP-004` — que também foi
corrigida nesta rotina, movendo-a para depois de `RT-CAT-001`, de quem ela depende.

**Entregue em [RT-CAT-003](RT-CAT-003-recurso.md)** (2026-09-01): `BlocoDoServico` ganhou
`recursosExigidos`, gravado junto com os blocos, e a exigência ficou **no bloco** — porque a
coloração ocupa o lavatório em 30 dos seus 100 minutos (RN-CAT-010).

## 10. Pendências

- [x] **Recursos exigidos por bloco** — entregue em RT-CAT-003. É o que permite saber que o
      lavatório está ocupado durante a lavagem, mesmo com o profissional livre na pausa
- [ ] **Editar serviço não existe**, só cadastrar e ativar/desativar. Vai aparecer: corrigir a
      duração de um serviço é rotina, e hoje a saída seria desativar e recriar — o que perde o
      histórico que a desativação existia para preservar. RT-CAT-003 agravou: os recursos de uma
      receita também só podem ser definidos no cadastro
- [ ] **Não há categoria** (cabelo, unha, estética). Um cardápio de quarenta itens sem agrupamento
      é uma lista para rolar. Não estava no escopo pedido
- [ ] **Nada avisa que mudar a duração afeta agendamentos futuros** — quando a agenda existir,
      alterar um serviço com horários já marcados precisa mostrar quantos
- [ ] **Não há tela**, como em toda a Fase 1
