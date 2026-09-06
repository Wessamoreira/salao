# Módulo `atendimento`

- **Sigla:** ATD · **Fase de introdução:** 2 · **Status:** em construção

## O que ele responde

*O que foi executado, e quanto isso soma.*

## Fronteira

Expõe `ComandaResumo`, `ComandaDetalhada`, `ItemDaComanda`, `ExecutanteDoItem`,
`AberturaDeComanda`, os enums de estado e `ErrosDoAtendimento`.

A comanda é o documento em que o serviço vira dinheiro. Ela é o **único** lugar onde o valor de um
atendimento é somado — e, a partir de RT-ATD-006, o único que congela esse valor.

## O que ele **não** sabe

**Quanto custa.** Preço é do `catalogo`, e o que a comanda guarda é o *snapshot* do preço no
momento da venda (RN-ATD-010) — não uma consulta que muda de resposta quando a tabela muda.

**Quem faz o quê.** Habilidade é da `equipe`. Este módulo pergunta se a pessoa executa o serviço; a
resposta, e a regra por trás dela, são de lá.

**Quanto cada um pode descontar.** Alçada é do `iam`, e este módulo pergunta o **teto**, não o
perfil — saber que alçada se decide por perfil o faria repetir a tabela do lado de fora.

**Quando o atendimento aconteceu.** É da `agenda`. A comanda registra que aconteceu, não em que
horário estava marcado.

**Quem é a cliente.** `ComandaResumo` carrega o id e nada mais; nome vem da `ClienteApi` para quem
precisa exibir, e o que não é carregado não vaza em log.

## Depende de

- `cliente` — `ClienteApi` (existência e nomes em lote)
- `agenda` — `AgendaApi.porId` (de quem é o horário, e se ele ainda vale)
- `catalogo` — `ServicoApi` (o que é, e quanto custava hoje)
- `equipe` — `ProfissionalApi` (quem executa o quê, e com que percentual)
- `estoque` — `ProdutoApi` (o que é, e se está à venda)
- `financeiro` — `PagamentoApi` (quanto já foi pago nesta comanda)
- `estoque` — `EstoqueApi.baixar` (tirar do armário o que foi vendido)
- `iam` — `Permissao`, `AuditoriaApi`, `EstabelecimentoApi`, `LimitesDoUsuarioApi`, `UsuarioAtual`

**Sete, e o teto de três não vale aqui.** RT-ATD-001 registrou "três, que é o teto que a agenda
segue"; RT-ATD-002 estourou, e a correção é da frase, não do módulo.

O teto da `agenda` existe porque ela **decide** — se um horário está livre —, e um módulo que
precisa de muitas fontes para decidir está decidindo o que não é dele. `atendimento` **registra**:
a comanda é, por definição, onde catálogo, equipe, agenda e cliente se encontram. Contar
dependências aqui mediria a natureza do módulo, não um defeito.

O guarda certo para ele é de direção, não de número:

- nada pode depender de `atendimento` para **decidir** — quem depende, depende do que já aconteceu.
  Foi essa regra que decidiu a direção com o `financeiro` em RT-FIN-001: o pagamento é guardado lá,
  e a pergunta "quanto falta?" é respondida aqui, porque o total é daqui;
- `atendimento` não decide nada que é de outro: preço é do `catalogo`, habilidade é da `equipe`,
  horário é da `agenda`. Ele copia o que eles dizem, no instante em que dizem, e guarda a cópia.

Se algum dia uma regra de preço ou de habilidade aparecer dentro deste módulo, é aí que a fronteira
foi rompida — não quando a sexta dependência entrar.

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-ATD-001](RT-ATD-001-abrir-comanda.md) — abrir comanda, do agendamento ou avulsa | implementada |
| [RT-ATD-002](RT-ATD-002-item-de-servico.md) — item de serviço + executantes | implementada |
| [RT-ATD-003](RT-ATD-003-item-de-produto.md) — item de produto | implementada |
| [RT-ATD-004](RT-ATD-004-desconto.md) — desconto com permissão e limite por perfil | implementada |
| [RT-ATD-005](RT-ATD-005-executante-adicional.md) — executante adicional (assistente) | implementada |
| [RT-ATD-006](RT-ATD-006-fechar-comanda.md) — **fechar comanda**, transacional | implementada |
| [RT-ATD-007](RT-ATD-007-estornar-reabrir.md) — estornar / reabrir | implementada |

## Rotas

| Rota | O que faz |
|---|---|
| `POST /api/v1/comandas` | abre — 201 se criou, **200 se já estava aberta** |
| `GET /api/v1/comandas/abertas` | a barra do balcão (R-UX-07), da mais antiga para a mais nova |
| `GET /api/v1/comandas/{id}` | a comanda com os itens — a tela de fechamento |
| `POST /api/v1/comandas/{id}/itens` | lança um serviço; devolve a **comanda inteira**, com o total novo |
| `POST /api/v1/comandas/{id}/produtos` | lança um produto — rota própria, porque produto não tem executante |
| `POST /api/v1/comandas/{id}/pagamentos` | registra uma forma; devolve **quanto falta** |
| `DELETE /api/v1/comandas/{id}/pagamentos/{pagId}` | desfaz um pagamento digitado errado |
| `GET /api/v1/comandas/{id}/conta` | o que já entrou e o que falta — a tela de fechamento |
| `POST /api/v1/comandas/{id}/fechamento` | **fecha**: confere o pago, baixa o estoque, publica o evento |
| `POST /api/v1/comandas/{id}/reabertura` | volta a `ABERTA` e devolve o estoque — `comanda:reopen` |
| `POST /api/v1/comandas/{id}/estorno` | `ESTORNADA`, terminal, com motivo na linha — `comanda:reopen` |
| `DELETE /api/v1/comandas/{id}/itens/{itemId}` | tira uma linha lançada errada |
| `PUT /api/v1/comandas/{id}/itens/{itemId}/desconto` | define o desconto da linha; zero remove |
| `POST /api/v1/comandas/{id}/itens/{itemId}/executantes` | acrescenta um assistente ao item |
| `DELETE /api/v1/comandas/{id}/itens/{itemId}/executantes/{execId}` | tira o assistente; o principal não sai |

## Eventos publicados

| ID | Evento | Quando | Consumidores |
|---|---|---|---|
| EV-ATD-001 | `ComandaFechada` | fechamento (RT-ATD-006) | `agenda` (planejado), `financeiro` (RT-FIN-003), comprovante (RT-FIN-008) — **nenhum existe ainda** |
| EV-ATD-002 | `ComandaReaberta` | reabertura (RT-ATD-007) | quem apurar sobre o fechamento precisa desfazer |
| EV-ATD-003 | `ComandaEstornada` | estorno (RT-ATD-007) | idem, e o razão (RT-FIN-007) |

> EV-ATD-002 e EV-ATD-003 são publicados **sem consumidor**, ao contrário do que foi decidido para
> `ComandaAberta` em RT-ATD-001. A diferença é concreta: reabrir e fechar de novo publica
> `ComandaFechada` uma segunda vez, e sem estes eventos no meio a apuração de comissão contaria em
> dobro sem nada avisar. Publicá-los agora é o que torna a compensação possível; não publicá-los
> seria deixar uma armadilha silenciosa para a próxima rotina.

## Regras

Ver [regras.md](regras.md) — RN-ATD-001 a RN-ATD-024.

Todos os códigos de erro do módulo vivem em `ErrosDoAtendimento`, um enum só: eles estavam declarados em seis enums, e `ER-ATD-COMANDA_NAO_ESTA_ABERTA` aparecia em cinco deles.
