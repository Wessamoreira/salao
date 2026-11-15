# ADR-0011: A superfície pública do balcão é uma lista explícita de casos de uso sem permissão

- **Status:** aceita · **Data:** 2026-09-27
- **Rotinas afetadas:** RT-CNV-010, e toda rotina que queira responder a quem não tem conta

## Contexto

Em 27/09/2026 o dono decidiu que a cliente passa a ser respondida pelo agente — **só consulta**:
que serviços existem, quanto custam, que horários estão livres. Marcar continua sendo da recepção.

A cliente não tem conta, não tem perfil e não é ninguém no sistema. Isso colide com uma decisão que
sustenta o projeto inteiro: **toda autorização mora no caso de uso**, num `@PreAuthorize` que o
ArchUnit exige de todo método público de caso de uso (`todo_caso_de_uso_tem_preauthorize`). As duas
consultas de que a cliente precisa —`ListarServicosUseCase` e `ConsultarDisponibilidadeUseCase` —
exigem `agenda:read:all`, `agenda:read:own` ou `config:manage`. Sem principal, as três recusam.

A pergunta, então, não é "como dar permissão à cliente". É **como o sistema representa aquilo que
qualquer pessoa aprende parada no balcão**.

## Opções consideradas

| Opção | Prós | Contras |
|---|---|---|
| Perfil `CLIENTE` com poucas permissões | Reaproveita tudo o que existe | Põe quem não tem conta dentro do mecanismo que existe para descrever contas. Daí em diante **toda permissão nova teria de lembrar de excluí-la** — a lista de exclusões que alguém esquece |
| Principal sintético com `agenda:read:all` só na conversa da cliente | Uma classe, nada mais muda | Um principal com poder real vivendo dentro de `conversacional`: no dia em que o executor da cliente alcançar uma ferramenta da equipe, o `@PreAuthorize` **passa**. A tranca vira a lista de ferramentas, que é "esconder o botão" com outro nome. E `agenda:read:all` devolve a agenda inteira, inclusive quem está marcado |
| A ferramenta da cliente chama a API pública do módulo | Sem caso de uso novo | Perde a segunda tranca — e é exatamente o motivo pelo qual, segundo o javadoc de `Ferramenta`, as ferramentas chamam caso de uso e não API |
| **Casos de uso públicos, numa lista explícita** | A superfície que um estranho alcança vira **uma lista que se lê**. Nenhum principal com poder. O caso de uso público não *tem como* devolver quem está marcado, porque não consulta isso | Mais código: casos de uso e DTOs próprios em dois módulos, e uma categoria nova de isenção no ArchUnit |

## Decisão

Casos de uso **públicos por desenho**, em categoria própria e estreita.

1. O módulo dono escreve um caso de uso sem `@PreAuthorize`, cujo nome diz o que ele é
   (`...DoBalcaoUseCase`), e que **só lê o que se aprende no balcão**.
2. Ele entra em `PUBLICOS_DO_BALCAO` no `ArquiteturaTest`, do mesmo jeito deliberado que
   `TRANCADOS_PELA_IDENTIDADE`, `NAO_SAO_PORTA` e `AUTENTICADOS_PELO_PROVEDOR`: acrescentar um nome
   é um gesto que aparece na revisão e obriga a escrever por quê.
3. Uma regra de arquitetura garante que **só as ferramentas da cliente os alcançam** — nenhum
   controller, nenhuma ferramenta da equipe. É a mesma condição que
   `o_que_o_provedor_autentica_so_e_alcancado_pelo_webhook` impõe ao webhook, e pelo mesmo motivo:
   sem ela, um caso de uso sem tranca fica a uma tela de distância de virar porta aberta.

### Onde foi parar a segunda tranca

Para a equipe, as duas trancas são a allowlist de ferramentas e o `@PreAuthorize` do caso de uso.
Para a cliente não existe permissão a conferir, então a segunda tranca muda de natureza: passa a ser
**a forma do caso de uso público**. Ele não devolve menos porque filtra — devolve menos porque não
consulta mais. `HorariosDoBalcaoUseCase` não sabe quem está marcado; ele calcula o que sobra.

Essa é a diferença que torna esta opção defensável e as outras duas não: nas outras, o dado
sensível continua ao alcance e algo precisa lembrar de escondê-lo.

### Emenda (27/09/2026, ao implementar): "não consulta mais" vale para o cardápio, não para o horário

A frase acima estava certa para os serviços e **errada para os horários**. A disponibilidade é uma
conta de cem linhas — jornada menos bloqueios menos ocupação, com a receita de cada profissional
encaixada no que sobra. Escrever uma segunda cópia dela para obter a propriedade "consulta menos"
trocaria um risco por um pior: **duas contas de disponibilidade divergem**, e a que diverge em
silêncio oferece à cliente um horário que o cadastro vai recusar.

Então a conta ficou num colaborador compartilhado (`CalculoDeDisponibilidade`), que não é caso de
uso e não é porta de ninguém, e **o recorte virou um TIPO**: `HorarioAberto` não tem
`profissionalId` nem `minutosDoProfissional` — quanto daquele intervalo o salão consegue revender a
outra pessoa, que é informação de dentro do negócio e não do balcão.

A propriedade que se queria continua de pé, e por um mecanismo melhor do que "não consultar": campo
que não existe no tipo não vaza no dia em que alguém acrescenta um campo ao DTO de cima. A regra
geral, corrigida, é esta: **o caso de uso público não pode devolver o que o balcão não mostra — por
não consultar, quando isso não custa duplicação; por não ter onde caber, quando custa.**

## Consequências

**Positivas.** "O que um estranho consegue perguntar ao sistema" deixa de ser uma dedução a partir
de permissões e vira uma lista de nomes, revisável numa sentada. Nenhum principal sintético existe.
A cliente nunca entra no mecanismo de permissões, então nenhuma permissão futura precisa lembrar
dela.

**Negativas.** Há duplicação deliberada: `listar_servicos` da equipe e o do balcão devolvem coisas
parecidas por caminhos diferentes. É o preço de não filtrar a saída de uma consulta poderosa — e a
duplicação que se vê é melhor que a tranca que se esquece.

**O que vigiar.** A lista é para ser curta. No dia em que alguém quiser pôr ali algo que não se
aprende parado no balcão, a resposta é não — e se a resposta precisar ser sim, então a cliente
passou a precisar de identidade, e isso é outra decisão, com outro ADR.
