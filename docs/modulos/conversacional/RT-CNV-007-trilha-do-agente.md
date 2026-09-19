---
id: RT-CNV-007
titulo: "Auditoria de ação por IA (`acao_ia`) + painel de revisão"
modulo: conversacional
fase: 4
perfil: completo
status: implementado
depende_de: [RT-CNV-004, RT-CNV-005, RT-CNV-006, RT-IAM-008]
permissoes: [agente:review]
eventos: []
regras: [RN-CNV-022, RN-CNV-023, RN-CNV-024, RN-CNV-025, RN-CNV-026]
atualizado_em: 2026-09-19
---

# RT-CNV-007 — A trilha do agente, e a revisão do que ele fez

## 1. Objetivo

Responder, meses depois, a três perguntas sobre o agente do WhatsApp: **o que ele fez, em nome de
quem, e o que as trancas recusaram**. Toda conversa encerrada — concluída, recusada ou que falhou —
vira uma linha imutável em `acao_ia`, gravada no mesmo commit do encerramento. O dono e a gerência
leem essa trilha numa tela própria, separando o que gravou do que foi bloqueado.

Até aqui R-10 estava provado só em teste: o ataque que não passava não deixava rastro para quem
administra o salão.

## 2. As decisões que definiram a rotina

| Decisão | Por quê |
|---|---|
| **Tabela própria**, e não uma view sobre `execucao_do_agente` e `intencao_pendente` | Os prazos são outros: a caixa de entrada vale 90 dias, e a trilha, 12 meses. Uma view morreria junto com a primeira tabela expurgada |
| **Retrato no mesmo commit** do encerramento (`MANDATORY`) | Separado, uma queda entre os dois deixaria conversa encerrada sem rastro — e nenhuma rodada seguinte a reencontraria |
| **"Gravou" e "bloqueada" calculados uma vez**, ao registrar | São as duas perguntas do painel. Calcular na leitura dependeria de tabelas que o expurgo já terá apagado |
| **Permissão própria**, `agente:review`, para dono e gerente | A tela mostra mensagem de número desconhecido e o que o agente consultou em nome de cada pessoa. Não é a recepção olhando a si mesma |
| **Sem chave estrangeira** para a execução | O retrato não pode depender de nada continuar existindo |

## 3. O caminho

1. `ProcessadorDeInteracoes` encerra a execução sempre por `ConcluirExecucao` — `conduzida`,
   `semModelo`, `recusada` ou `falhou`. Nenhum caminho marca a execução direto.
2. Na mesma transação, `AcoesDoAgenteJdbc.registrar` faz um `INSERT … SELECT` a partir do que a
   conversa deixou: a execução, a mensagem (para saber se veio de áudio) e as intenções que ela
   propôs, decidiu ou aceitou. Execução ainda `PENDENTE` não registra nada, e a segunda chamada é
   inofensiva (`on conflict do nothing`).
3. O painel lê por `ConsultarAcoesDoAgenteUseCase`: a lista com cursor, os totais do período e as
   pessoas que conversaram.
4. Todo dia às 3h45, `PurgadorDeAcoesDoAgente` anonimiza o que passou de 90 dias e apaga o que passou
   de 12 meses.

## 4. Bloqueada e gravou — RN-CNV-023

- **Bloqueada**: a identidade, o limite ou o teto recusaram a conversa (`RECUSADA`), **ou** alguma
  chamada parou numa tranca — `INEXISTENTE`, `FORA_DA_LISTA`, `SEM_PERMISSAO`. Virar proposta
  **não** é bloqueio: é o caminho certo da escrita. Recusa de regra do domínio (`ERRO_DE_DOMINIO`,
  horário tomado) também não: a tranca deixou passar, e a agenda disse não.
- **Gravou**: foi *esta* conversa que confirmou — o "SIM + código" de uma intenção sem aceite, ou o
  toque de aceite da profissional. A proposta nunca grava; o SIM de uma intenção que ainda espera o
  aceite também não.

Uma tranca por conversa no teste (`AcaoIaIT.cada_tranca_bloqueia`): com as três juntas, o teste
passaria mesmo que o cálculo esquecesse duas delas.

## 5. O que não entra — RN-CNV-024

- **O id do botão.** Ele carrega o token do aceite (RN-CNV-019). No toque, `pedido` é "Aceitar",
  "Recusar" ou "Botão" — nunca o id (`AcaoIaIT.botao_sem_token`).
- **Nada em log de aplicação.** O conteúdo vive só na tabela, sob RLS; o expurgo registra contagens.

## 6. O painel — RN-CNV-025

`/agente`, no grupo *Gestão* do menu, para quem tem `agente:review`.

- **Abre respondendo.** No alto, uma linha de totais: conversas, quantas gravaram, quantas foram
  bloqueadas, custo estimado. Os recortes *Todas · Gravaram · Bloqueadas* são essas perguntas, e são
  filtrados **no servidor**.
- **Dias do salão.** Hoje, 7, 30 ou 90 dias, no fuso do estabelecimento. O servidor aceita no máximo
  92: uma consulta de um ano inteiro de trilha é relatório, e relatório é outra rotina.
- **Quem pediu.** O nome de hoje, lido do `iam` por `NomesDeUsuariosApi`. Sem usuário — o número
  desconhecido, a profissional sem conta —, só os **quatro últimos dígitos**. Com usuário, nem isso.
- **O trilho das trancas.** No detalhe, cada pedido do modelo aparece em sequência: *Existe →
  Liberada ao agente → Permissão de quem pediu → resultado*. Onde parou fica marcado por forma (✕ e
  barra) e por texto ("Sem permissão de quem pediu"); as trancas seguintes aparecem vazadas, porque o
  pedido não chegou nelas. Mostrar só `SEM_PERMISSAO` obrigaria o dono a conhecer a ordem das trancas
  para saber o que ficou para trás.
- **Proposta nunca parece feita.** A linha ganha a etiqueta *Propôs*; no detalhe, "Virou pedido de
  confirmação", com o resumo e o estado da intenção ao fim daquela conversa ("Esperando o SIM").
- **Anonimizada não finge conteúdo.** Depois dos 90 dias a linha diz quando o conteúdo saiu e mostra o
  esqueleto que ficou.
- **Só leitura.** Nem "marcar como revisado": quem precisa agir sobre o que viu age pela tela do
  assunto.

Cor, sempre com forma junto (docs/16, decisões 1 e 2): barra lateral contínua verde para *gravou*,
interrompida vermelha para *bloqueada*, borda tracejada âmbar para o que ainda espera alguém. Bodoni só
nos números dos totais.

## 7. Retenção — RN-CNV-026

| Quando | O que sai | O que fica |
|---|---|---|
| 90 dias | pedido, resposta, telefone, argumentos das ferramentas, resumos das intenções | quando, em nome de quem, quais ferramentas e com que desfecho, estado das intenções, gravou, bloqueada, tokens, custo |
| 12 meses | a linha inteira | — |

Pela conexão de manutenção, que atravessa salões e tem `update` **só nas colunas de conteúdo**: o
expurgo pode apagar o que foi dito, e não reescrever o que aconteceu. A constraint
`acao_anonimizada_sem_conteudo` impede marcar a linha como anonimizada com conteúdo ainda nela.

Prazos em `app.agente.acao-ia.anonimizar-apos` (`P90D`) e `app.agente.acao-ia.apagar-apos`
(`P365D`). A subida recusa configuração que apague antes de anonimizar.

## 8. Contrato de API

O projeto não gera OpenAPI; o contrato é este, e `AcoesDoAgenteWebIT` o prende.

**Lista**

```http
GET /api/v1/agente/acoes?recorte=BLOQUEADAS&pessoa=3f0c9b2e-…&de=2026-09-08&ate=2026-09-14&cursor=…&limite=30
Authorization: Bearer <token-com-mfa>
```

Todos os parâmetros são opcionais. `recorte`: `TODAS` (padrão), `GRAVARAM`, `BLOQUEADAS`. Sem
período, os últimos sete dias. `limite`: 30 por padrão, 100 no máximo. Nenhum parâmetro é dado
pessoal — pessoa é id, período é data, cursor é instante e id (docs/05).

```json
{
  "itens": [
    {
      "id": "0b6f2d4a-…",
      "ocorridaEm": "2026-09-14T17:30:12.418Z",
      "tipo": "CONVERSA",
      "desfecho": "CONCLUIDA",
      "motivoRecusa": null,
      "usuarioEfetivoId": "3f0c9b2e-…",
      "quemPediu": "Carla Menezes",
      "telefoneFinal": null,
      "veioDeAudio": true,
      "pedido": "quanto o salão faturou essa semana?",
      "resposta": "Não consigo ver o financeiro pelo seu usuário.",
      "ferramentas": [
        { "nome": "ler_financeiro_do_salao", "desfecho": "SEM_PERMISSAO",
          "argumentos": { "periodo": "semana" } }
      ],
      "intencoes": [],
      "gravou": false,
      "bloqueada": true,
      "modelo": "claude-sonnet-5",
      "tokensEntrada": 1840,
      "tokensSaida": 72,
      "custoUsd": 0.0066,
      "anonimizadaEm": null
    }
  ],
  "proximoCursor": null,
  "temMais": false
}
```

**Totais** — `GET /api/v1/agente/acoes/resumo?pessoa=&de=&ate=`. Sem recorte: é o que os recortes
decompõem.

```json
{ "de": "2026-09-08", "ate": "2026-09-14", "conversas": 128, "gravaram": 3, "bloqueadas": 5,
  "custoUsd": 0.42, "tokensEntrada": 181200, "tokensSaida": 9100 }
```

**Pessoas** — `GET /api/v1/agente/acoes/pessoas?de=&ate=`, de quem mais conversou para quem menos.

```json
[ { "usuarioId": "3f0c9b2e-…", "nome": "Bia Andrade", "conversas": 41 } ]
```

**Erros** (formato de docs/07)

| Status | Código | Quando |
|---|---|---|
| 403 | `ER-INF-ACESSO_NEGADO` | sem `agente:review` (a recepção), ou dono e gerente ainda sem MFA |
| 400 | `ER-INF-DADOS_INVALIDOS` | parâmetro de tipo errado: `recorte=QUALQUER`, `de=ontem`, `pessoa=nao-e-uuid` |
| 422 | `ER-CNV-FILTRO_INVALIDO` | início depois do fim, mais de 92 dias, cursor que não veio do painel |

```json
{ "status": 422, "codigo": "ER-CNV-FILTRO_INVALIDO", "detail": "o período pode ter no máximo 92 dias" }
```

## 9. Dados

**`V56__acao_ia.sql`** — a tabela, com `execucao_id` único e sem FK; índices para o painel
(`estabelecimento_id, ocorrida_em desc, id desc`), por pessoa e por data (o expurgo). RLS por
`aplicar_rls_tenant`; `revoke update, delete` da aplicação; para a manutenção, `select` e `delete` e
`update` apenas em `pedido`, `resposta`, `de_telefone`, `ferramentas`, `intencoes` e
`anonimizada_em`.

**`iam`** — a permissão `agente:review` (ADMIN e GERENTE), o menu *Agente* no grupo *Gestão* quando
ela existe, e `NomesDeUsuariosApi`, que resolve os nomes de uma página de uma vez.

## 10. Observabilidade

- O expurgo registra `Trilha do agente: N apagadas, M anonimizadas` quando houve algo; silêncio é o
  normal.
- O runbook (`ligar-whatsapp.md`, seção Agente) tem a consulta de conversa encerrada sem linha na
  trilha — que tem de dar zero.
- O próprio painel é a observação de negócio: bloqueadas subindo é ataque ou cadastro errado.

## 11. Testes obrigatórios

**`AcaoIaIT`** (14), com o modelo falso e o caminho real do WhatsApp até a fila:
`conversa_vira_acao`, `desconhecido_na_trilha`, `cada_tranca_bloqueia`, `confirmacao_gravou`,
`audio_na_trilha`, `falhou_na_trilha`, `botao_sem_token`, `trilha_imutavel`,
`recortes_pessoa_e_resumo`, `cursor`, `periodo`, `isolamento`, `sem_permissao`, `retencao`.

**`AcoesDoAgenteWebIT`** (4): a gerente revisa com MFA e o telefone não sai inteiro; a recepção
recebe 403 nas três rotas; filtro de tipo errado é 400 e filtro impossível é 422; sem token não abre.

**`AceiteDaProfissionalIT.caminho_completo`** — na trilha, quem gravou foi o toque da Ana; nem a
proposta nem o SIM da Bia.

**`CapacidadesIT`** — `agente:review` e o menu para o dono; nada disso para a recepção.

**Front** — `RevisaoDoAgente.test.tsx` (8): totais, trilho parado na tranca certa, proposta como
pedido, número pelo final, recorte e período pedidos ao servidor, cursor, anonimizada.
`periodo.test.ts` (3): o dia do salão, a virada de mês, fuso desconhecido.

## 12. Um defeito que o teste pegou

A primeira rodada deu 500 em `/resumo`. `totais` montava o SQL somando as condições ao fim de um bloco
de texto — `"""…from acao_ia where """ + condicoes(…)` —, e bloco de texto **apaga o espaço no fim da
linha**: o banco recebia `whereocorrida_em`. A lista escapou porque usava `StringBuilder` com string
comum. Corrigido encaixando as condições com `%s`, e o motivo ficou no comentário de `condicoes`.

## 13. Mutações verificadas

Uma rodada, 23 mutações, cada uma aplicada sozinha e rodada contra os testes que deveriam matá-la.
**23 mortas**, todas pelo teste esperado.

| # | Mutação | Morta por |
|---|---|---|
| M01 | `bloqueada` ignora `RECUSADA` | `desconhecido_na_trilha`, `recortes_pessoa_e_resumo` |
| M02 | `bloqueada` ignora `SEM_PERMISSAO` | `cada_tranca_bloqueia` |
| M03 | `bloqueada` ignora `FORA_DA_LISTA` | `cada_tranca_bloqueia` |
| M04 | `gravou` credita a conversa que propôs, não a que decidiu | `confirmacao_gravou`, `recortes_pessoa_e_resumo` |
| M05 | `gravou` ignora o toque de aceite | `AceiteDaProfissionalIT.caminho_completo` |
| M06 | o toque grava o id do botão, com o token | `botao_sem_token` |
| M07 | áudio nunca marcado | `audio_na_trilha` |
| M08 | `recusada` não registra | `desconhecido_na_trilha` e outros três |
| M09 | `falhou` não registra | `falhou_na_trilha` |
| M10 | `conduzida` não registra | sete testes |
| M11 | o recorte *Gravaram* não filtra | `recortes_pessoa_e_resumo` |
| M12 | cursor inclusivo (`<=`) | `cursor` |
| M13 | ordem crescente | `cursor`, `recortes_pessoa_e_resumo` |
| M14 | final do telefone mesmo com usuário | `recortes_pessoa_e_resumo` |
| M15 | teto de 92 dias com um dia de folga | `periodo` |
| M16 | período invertido aceito | `periodo` |
| M17 | apaga no prazo de anonimizar | `retencao` |
| M18 | anonimizar mantém os argumentos | `retencao` |
| M19 | anonimizar mantém os resumos | `retencao` |
| M20 | `listar` sem `@PreAuthorize` | `sem_permissao` |
| M21 | a aplicação pode reescrever a trilha (sem `revoke`) | `trilha_imutavel` |
| M22 | sem RLS | `isolamento` |
| M23 | menu *Agente* sem conferir a permissão | `CapacidadesIT.recepcao_sem_financeiro` |

Na rodada inteira, M19 derrubou também `confirmacao_gravou` — que nem passa pelo expurgo. Refeita
sozinha, com a máquina acordada, só `retencao` caiu. O que houve: o Mac dormiu entre a proposta e o
"SIM", e o código de confirmação (10 minutos) venceu. Não é teste instável; é a validade funcionando.
Fica o registro para quem ver o mesmo sintoma numa rodada longa.

Três lacunas apareceram **ao planejar** a rodada, antes de rodar, e viraram teste: nenhum caso tinha
`SEM_PERMISSAO` sozinho (daí `cada_tranca_bloqueia`, uma tranca por conversa); nada provava que o final
do telefone some quando há usuário; e nada olhava a trilha no caminho do aceite (a asserção nova em
`caminho_completo`).

**Equivalentes, e por isso fora da rodada:** tirar `aceite_token is null` do cálculo de `gravou`, ou
`status <> 'PENDENTE'` do registro. A trilha é gravada no instante do encerramento, e nesse instante a
intenção de um SIM com aceite ainda está `AGUARDANDO_ACEITE`, e a execução já não está `PENDENTE` —
as duas condições não mudam resultado nenhum hoje. Ficam como defesa, não como comportamento testado.

## 14. Pendências

- [ ] **Relatório da trilha além de 92 dias**, e exportação — outra rotina.
- [ ] **Alerta de bloqueadas subindo.** Hoje só aparece no painel.
- [ ] **Nome da ferramenta cru** (`agendar`) no detalhe. Quando o catálogo crescer, o rótulo humano
      deve vir do servidor, junto com a ferramenta — não de um mapa na tela.
- [ ] **Conversas encerradas antes da `V56` não têm linha.** Sem produção ainda, não há o que migrar.
- [ ] **A `auditoria` geral por tela** continua pendente (RT-IAM-008): esta rotina resolveu a trilha
      do agente, não a de quem alterou o quê.
- [ ] **Retenção de `intencao_pendente` e `mensagem_para_equipe`** segue sem job (RT-CNV-005/006).
- [ ] **Sem provedor de LLM** (pergunta 14), a trilha só terá conversa de verdade quando o agente rodar.
