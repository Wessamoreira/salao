---
id: RT-EQP-004
titulo: Habilidades — quais serviços cada profissional executa
modulo: equipe
fase: 1
perfil: completo
status: implementado
depende_de: [RT-EQP-001, RT-CAT-001]
permissoes: [usuario:manage, agenda:read:all]
eventos: []
regras: [RN-EQP-013, RN-EQP-014, RN-EQP-015]
atualizado_em: 2026-09-02
---

# RT-EQP-004 — Habilidades

## 1. Objetivo

Responder a pergunta de RN-AGD-001: *este profissional executa este serviço?*

## 2. A pergunta que decide a rotina

**Lista vazia significa "faz tudo" ou "não faz nada"?**

Toda a rotina sai daqui, e as duas respostas óbvias estão erradas:

| Resposta | O que quebra |
|---|---|
| Vazia = **não faz nada** (lista branca) | 6 profissionais × 40 serviços = 240 caixas antes do primeiro agendamento. E pior: **todo serviço novo nasce invisível** para todo mundo, até alguém revisitar 6 cadastros |
| Vazia = **faz tudo** (lista negra) | A recepção marca progressiva com quem só faz manicure, e a descoberta é no balcão, com a cliente sentada |

A lista branca falha uma vez por serviço novo, para sempre, em silêncio. A lista negra falha uma
vez por agendamento errado, alto, no pior momento.

**A saída é não deixar o padrão implícito** (RN-EQP-013). Uma chave no profissional diz em qual
regime ele está:

- **aberto** (padrão) — faz o cardápio inteiro; a lista é ignorada
- **restrito** — faz apenas o que está na lista

O padrão aberto é o que faz um salão novo funcionar no primeiro dia. Restringir é um ato
deliberado, e a consequência — serviço novo não alcança quem está restrito — tem teste próprio
justamente porque é o que a tela precisa avisar.

Note a distinção que parece pedante e não é: no regime aberto a lista é **ignorada**, não
"consultada e vazia". Se fosse consultada, um profissional sem linha nenhuma responderia "não
executa nada" — exatamente o defeito que a chave existe para evitar.

## 3. Restringir para nada é recusado

Passar ao regime restrito com lista vazia seria o único jeito de deixar alguém sem poder atender
nada **sem que isso estivesse dito em lugar nenhum**: a agenda pararia de oferecer horário dessa
pessoa e nenhuma tela explicaria por quê (RN-EQP-014).

Quem quer tirar alguém da agenda desativa o profissional — explícito, reversível, e já existe.

Por isso também o regime e a lista são escritos **na mesma operação**, num endpoint só: dois
endpoints criariam o instante em que o profissional está restrito e ainda sem serviço, que é
precisamente o estado proibido.

## 4. Sem chave estrangeira para serviço

`habilidade.servico_id` é um uuid solto (RN-EQP-015).

`servico` é do módulo `catalogo`; `habilidade` é de `equipe`. Uma FK costuraria os dois esquemas e
deixaria um `on delete` do catálogo mexer em dado da equipe — a fronteira existiria no código e não
no banco, que é o inverso do que este projeto faz em todo o resto. É a mesma razão que faz
RN-AGD-001 proibir join entre módulos.

**E não se perde garantia nenhuma.** RT-CAT-003 mediu isto: as checagens de integridade referencial
do PostgreSQL rodam como dono da tabela e não passam pela RLS (RN-CAT-011). A FK aceitaria um
serviço de outro salão. Aqui a checagem da aplicação não é reforço — é, e sempre seria, a única
barreira. O teste `servico_de_outro_salao_nao_serve` prova que ela funciona.

Serviço **desativado** continua servindo como habilidade, de propósito: ele pode voltar ao
cardápio, e recusá-lo faria a informação de quem sabe executá-lo se perder no caminho.

## 5. Uma consulta para o conjunto

A primeira versão de `conferirServicos` chamava `ServicoApi.porId` num laço — uma consulta por
serviço, e cada uma trazendo os blocos que a validação não usa. É o mesmo N+1 que as rotinas
anteriores recusaram; ele apareceu aqui porque `ServicoApi` não tinha o método de conjunto.

Agora tem: `idsExistentes(Collection<UUID>)`, uma consulta, espelhando `RecursosJdbc.idsAtivos` de
RT-CAT-003.

## 6. O recorte do plano estava errado

O plano tinha `RT-CAT-004` (override de duração por profissional) **antes** desta rotina, e as duas
como tabelas separadas em módulos diferentes.

Mas as duas são a **mesma relação**: profissional × serviço. Separá-las permitiria a contradição
"a Ana leva 30 minutos numa coloração que ela não faz", e obrigaria `catalogo` a depender de
`equipe` — dependência que hoje não existe e que o módulo faz questão de não ter.

A duração é um **atributo da habilidade**, não uma tabela irmã. Então:

- `RT-EQP-004` (esta) cria a relação
- `RT-CAT-004` acrescenta a coluna `duracao_minutos` nela — e será implementada no módulo `equipe`,
  apesar do id dizer `CAT`, porque é onde a relação vive

É o mesmo tipo de defeito de recorte que já havia sido corrigido em RT-CAT-001 (recursos exigidos)
e na ordenação original de RT-EQP-004.

## 7. Testes

- [x] `HabilidadesTest` — 5: **o regime aberto alcança serviço que nem existia** · regime restrito
      é lista branca · **restringir a nada é recusado** · o regime aberto ignora a lista · lista
      imutável
- [x] `HabilidadesIT` — 14: nasce no regime aberto · **serviço novo alcança quem já existia** ·
      regime restrito · **restrito não recebe serviço novo** · voltar ao aberto descarta a lista ·
      **restringir a nada é 422, não 500** · **serviço de outro salão não serve** · serviço
      inexistente · **serviço desativado ainda serve** · quem executa inclui abertos e listados ·
      **inativo não executa nada** · inexistente não executa e não estoura · isolamento entre
      salões · a recepção não altera

## 8. Pendências

- [ ] **Nada avisa quantos agendamentos futuros a restrição invalida** — quando a agenda existir,
      restringir alguém que já tem coloração marcada precisa mostrar quantas. Mesma pendência que
      RT-CAT-001 e RT-CAT-003 registraram para duração e quantidade
- [ ] **Não há aviso na tela de que virar a chave muda o futuro** — depois de restringir, todo
      serviço novo do cardápio nasce indisponível para essa pessoa. É a consequência menos óbvia
      da rotina e a que mais precisa aparecer na interface
- [ ] **Habilidade não sabe quanto tempo a pessoa leva** — é `RT-CAT-004`, a coluna
      `duracao_minutos` nesta mesma tabela
- [ ] **Linha órfã se um serviço for apagado de verdade** — hoje serviço só se desativa, então não
      acontece. Se um dia houver expurgo no catálogo, ele precisa limpar habilidades
- [ ] **Não há tela**, como em toda a Fase 1
