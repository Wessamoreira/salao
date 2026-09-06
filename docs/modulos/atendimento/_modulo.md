# Módulo `atendimento`

- **Sigla:** ATD · **Fase de introdução:** 2 · **Status:** em construção

## O que ele responde

*O que foi executado, e quanto isso soma.*

## Fronteira

Expõe `ComandaResumo`, `AberturaDeComanda`, `StatusDaComanda` e `OrigemDaComanda`.

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
- `iam` — `Permissao`, `AuditoriaApi`, `EstabelecimentoApi`, `LimitesDoUsuarioApi`, `UsuarioAtual`

**Cinco, e o teto de três não vale aqui.** RT-ATD-001 registrou "três, que é o teto que a agenda
segue"; RT-ATD-002 estourou, e a correção é da frase, não do módulo.

O teto da `agenda` existe porque ela **decide** — se um horário está livre —, e um módulo que
precisa de muitas fontes para decidir está decidindo o que não é dele. `atendimento` **registra**:
a comanda é, por definição, onde catálogo, equipe, agenda e cliente se encontram. Contar
dependências aqui mediria a natureza do módulo, não um defeito.

O guarda certo para ele é de direção, não de número:

- nada pode depender de `atendimento` para **decidir** — quem depende, depende do que já aconteceu;
- `atendimento` não decide nada que é de outro: preço é do `catalogo`, habilidade é da `equipe`,
  horário é da `agenda`. Ele copia o que eles dizem, no instante em que dizem, e guarda a cópia.

Se algum dia uma regra de preço ou de habilidade aparecer dentro deste módulo, é aí que a fronteira
foi rompida — não quando a sexta dependência entrar.

## Rotinas

| Rotina | Situação |
|---|---|
| [RT-ATD-001](RT-ATD-001-abrir-comanda.md) — abrir comanda, do agendamento ou avulsa | implementada |
| [RT-ATD-002](RT-ATD-002-item-de-servico.md) — item de serviço + executantes | implementada |
| [RT-ATD-004](RT-ATD-004-desconto.md) — desconto com permissão e limite por perfil | implementada |
| [RT-ATD-005](RT-ATD-005-executante-adicional.md) — executante adicional (assistente) | implementada |

## Rotas

| Rota | O que faz |
|---|---|
| `POST /api/v1/comandas` | abre — 201 se criou, **200 se já estava aberta** |
| `GET /api/v1/comandas/abertas` | a barra do balcão (R-UX-07), da mais antiga para a mais nova |
| `GET /api/v1/comandas/{id}` | a comanda com os itens — a tela de fechamento |
| `POST /api/v1/comandas/{id}/itens` | lança um serviço; devolve a **comanda inteira**, com o total novo |
| `DELETE /api/v1/comandas/{id}/itens/{itemId}` | tira uma linha lançada errada |
| `PUT /api/v1/comandas/{id}/itens/{itemId}/desconto` | define o desconto da linha; zero remove |
| `POST /api/v1/comandas/{id}/itens/{itemId}/executantes` | acrescenta um assistente ao item |
| `DELETE /api/v1/comandas/{id}/itens/{itemId}/executantes/{execId}` | tira o assistente; o principal não sai |

## Regras

Ver [regras.md](regras.md) — RN-ATD-001 a RN-ATD-016.
