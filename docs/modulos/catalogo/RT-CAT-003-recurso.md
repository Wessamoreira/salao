---
id: RT-CAT-003
titulo: Recurso (lavatório, cadeira, sala)
modulo: catalogo
fase: 1
perfil: completo
status: implementado
depende_de: [RT-INF-002, RT-CAT-001]
permissoes: [config:manage, agenda:read:all]
eventos: []
regras: [RN-CAT-009, RN-CAT-010, RN-CAT-011, RN-CAT-012]
atualizado_em: 2026-09-01
---

# RT-CAT-003 — Recurso

## 1. Objetivo

Dizer o que o salão tem em **número limitado**, e em qual momento de cada serviço isso é disputado.

## 2. O horário que o sistema promete e o salão não consegue cumprir

Sem recurso, a agenda pergunta uma coisa só: *a profissional está livre às 15h?* Está — e marca.

Às 15h, três colorações chegam juntas na lavagem. O salão tem dois lavatórios. Duas clientes
esperam sentadas com tinta no cabelo, e o horário que o sistema prometeu **nunca existiu**.

É a mesma economia da RT-CAT-001, aplicada à outra coisa escassa: lá o gargalo é a pessoa, aqui é
o equipamento. E é o mesmo tipo de defeito — nada quebra, nenhum erro aparece, e o salão descobre
na segunda-feira quando três clientes reclamam da mesma coisa.

## 3. A exigência mora no bloco

É a decisão central da rotina (RN-CAT-010).

| Onde a exigência mora | Lavatório reservado por |
|---|---|
| No serviço | **100 min** — os 30 de aplicação, os 40 de química e os 30 de lavagem |
| No bloco da lavagem | **30 min** |

Pendurar a exigência no serviço reserva 70 minutos de um equipamento que ninguém está usando —
exatamente o desperdício que os blocos ATIVO/PAUSA existem para eliminar, só que movido de lugar.
Seria construir metade da economia e jogar a outra metade fora.

**E um bloco `PAUSA` pode exigir recurso.** Durante os 40 minutos da química a profissional está
livre e a **cadeira não**: a cliente continua sentada nela. Este é o caso que um modelo ingênuo
erra, e errá-lo é vender a mesma cadeira duas vezes.

## 4. Contável, não nomeado

O salão tem "três lavatórios", não "Lavatório A, B e C" (RN-CAT-009).

As unidades são intercambiáveis: ninguém escolhe qual, só importa se sobra uma. Nomeá-las criaria
uma escolha que a recepção não sabe fazer e um dado que ninguém consulta — e "chegou o terceiro
lavatório" vira uma edição de uma coluna em vez de um cadastro que precisa ser ligado às receitas
que já existem.

**O que essa escolha custa**, dito antes de a agenda existir: `EXCLUDE USING gist` — que protege a
jornada em RT-EQP-002 — garante *no máximo um*, não *no máximo N*. A capacidade de um recurso com
três unidades não sai de graça do banco como a da profissional. O caminho previsto é a agenda
alocar um número de unidade (1..quantidade) e o `EXCLUDE` passar a valer sobre
`(recurso_id, unidade)`; fica registrado como pendência de RT-AGD-002, com a ressalva de que
escolher a unidade na hora da marcação pode criar conflito falso que uma contagem pura não teria.

## 5. E por que não tem `tipo`

O título da rotina lista "lavatório, cadeira, sala", e a tentação é transformar isso numa coluna.

Mas os três não se comportam de forma diferente em lugar nenhum do sistema: o que distingue um
lavatório de uma cadeira é **qual bloco os exige**, e isso já está dito no vínculo. Um campo que só
serviria para agrupar numa tela que ainda não existe é dado especulativo — e dado especulativo
envelhece errado, porque quando a tela chegar a taxonomia certa vai ser outra.

## 6. A chave estrangeira não separa salões

O achado da rotina, e ele vale para o projeto inteiro (RN-CAT-011).

`bloco_exige_recurso.recurso_id` é uma FK. A intuição diz que a RLS cobre isso. **Não cobre**: no
PostgreSQL as checagens de integridade referencial rodam como dono da tabela e passam por cima da
política — é assim de propósito, para que a integridade não dependa de quem está olhando. O efeito
colateral é que um `insert` com `recurso_id` de outro salão **funciona**, porque a política protege
a linha inserida e não o alvo da FK.

Quem separa é a aplicação: ler o pai antes de gravar o filho, com uma consulta comum que a política
filtra. Auditei as outras FKs que recebem id vindo da requisição e **todas já faziam isso** —
`preco`, `jornada`, `bloqueio`, `consentimento`, `mesclagem`. O que faltava era o motivo escrito e a
demonstração.

(As FKs de `refresh_token` e `mfa_credencial` apontam para o usuário da sessão, que não vem do corpo
do pedido — lá não há id atravessado a inventar. A regra é para id que vem de fora.)

Por isso há dois testes e não um:

- `recurso_de_outro_salao_nao_serve` — prova que a tentativa é recusada
- `a_fk_sozinha_nao_e_barreira_de_tenant` — insere a linha atravessada por baixo, como
  `salao_app`, e **prova que o banco aceita**

O segundo é o que impede a defesa de virar superstição. Se um dia ele falhar, a notícia é boa: o
PostgreSQL mudou e a checagem da aplicação virou redundância. Enquanto passar, ela é a única
barreira. Registrado como quinta armadilha em
[05-seguranca-multitenancy-lgpd.md](../../05-seguranca-multitenancy-lgpd.md).

Para escrevê-lo, o `AbstractPostgresIT` ganhou `comoApp(tenant)` — o complemento de `comoOwner()`:
uma conexão que enxerga o banco exatamente como a aplicação enxerga. Faltava, e vai faltar de novo
em RT-AGD.

## 7. Desativar é aposentar, e é recusado enquanto alguém exigir

Desativar um recurso que uma receita ativa exige é recusado, e **o erro lista os serviços**
(RN-CAT-012).

Se passasse, cada um desses serviços ficaria impossível de marcar: a agenda procuraria um lavatório
livre, não acharia nenhum — porque não existe mais nenhum — e simplesmente deixaria de oferecer
horário. Sem erro, sem aviso, e sem ninguém conseguindo explicar o que aconteceu.

Listar os serviços é a diferença entre *"não pode"* e *"não pode; conserte estes três"*.

Só os serviços **ativos** contam. É coerente (um serviço fora do cardápio não pode ser marcado, não
há agenda para quebrar) e é a saída de quem precisa mesmo aposentar o equipamento: tira o serviço
do cardápio primeiro. A ordem das duas operações é a ordem em que as coisas acontecem na vida.

**Diminuir a quantidade é sempre permitido** — um lavatório quebra e o salão passa a ter dois. A
agenda encolhe junto, que é o que se quer.

**O que isto não é:** "o lavatório quebrou hoje". Indisponibilidade temporária é bloqueio — o
análogo de RT-EQP-003 para recurso — e não existe. Está na lista de pendências.

## 8. A sexta ocorrência, tratada antes de acontecer de novo

`Recurso` recusa quantidade zero com `IllegalArgumentException`: correto no domínio, 500 na borda.

O tradutor (`RecursoBruto`) entrou junto com o código, como `EtapaBruta` em RT-CAT-001. É a
segunda vez seguida que a lição vem antes do defeito, e desta vez ela cobriu **dois** caminhos —
cadastro e edição — porque a validação mora num lugar só.

## 9. Testes

- [x] `RecursoTest` — 3: **quantidade zero não existe** · uma unidade basta · nome obrigatório
- [x] `SequenciaDeBlocosTest` — 4 novos: **o recurso mora no bloco** · **a pausa também ocupa** ·
      recursos imutáveis · bloco sem recurso é conjunto vazio
- [x] `RecursosDoSalaoIT` — 19: cadastra e lista · **quantidade zero é 422, não 500** · quantidade
      ausente vira 1 · nome único ignorando caixa · mesmo nome em salões diferentes ·
      **a coloração exige o lavatório só na lavagem** · **a pausa pode exigir recurso** · a
      listagem traz os recursos sem uma consulta por serviço · recurso inexistente é 422 · recurso
      inativo não serve · **recurso de outro salão não serve** · **a FK sozinha não é barreira de
      tenant** · **não desativa recurso em uso, e o erro diz quais** · desativa quando ninguém mais
      exige · aumenta e diminui a quantidade · versão desatualizada é 409 · edição para zero é 422
      · isolamento entre salões · **a recepção lê mas não altera**

## 10. Pendências

- [ ] **A capacidade N não é garantida pelo banco** — o `EXCLUDE` garante "no máximo um". Enquanto
      RT-AGD-002 não alocar unidade, nada impede duas marcações de estourarem a quantidade sob
      concorrência. É a maior dívida desta rotina, e é da agenda
- [ ] **Bloqueio de recurso não existe** — "o lavatório quebrou hoje" não tem como ser dito.
      Desativar é aposentar, não é indisponibilidade temporária. Análogo de RT-EQP-003
- [ ] **Diminuir a quantidade não avisa quantos agendamentos futuros deixam de caber** — quando a
      agenda existir, é a mesma pendência que RT-CAT-001 registrou para a duração
- [ ] **Editar serviço continua não existindo**, então na prática só dá para definir os recursos de
      uma receita no cadastro. É a mesma pendência de RT-CAT-001, agora com uma consequência a mais
- [ ] **Não há tela**, como em toda a Fase 1
