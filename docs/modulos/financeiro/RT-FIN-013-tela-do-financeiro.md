---
id: RT-FIN-013
titulo: "Tela do financeiro: extrato, vale, fechamento e repasse"
modulo: financeiro
fase: 7
perfil: completo
status: implementado
depende_de: [RT-FIN-003, RT-FIN-004, RT-FIN-005, RT-FIN-006, RT-FIN-007, RT-INF-010]
permissoes: [financeiro:read:all, financeiro:advance, financeiro:close]
eventos: []
regras: [RN-FIN-011, RN-FIN-014, RN-FIN-020, RN-FIN-023, RN-FIN-041]
atualizado_em: 2026-09-26
---

# RT-FIN-013 — O dinheiro de quem trabalha

## 1. Objetivo

Ver quanto cada pessoa tem a receber, entregar vale, fechar o período e registrar o repasse — com
o número confirmado sendo o número que se viu.

## 2. Contexto de negócio

`/financeiro` é a **última** tela que diz "em breve". Doze rotinas do módulo estão entregues e
testadas, e o front usa duas: a comanda recebe, e a conciliação confere a maquininha. O outro
lado — quanto cada profissional tem a receber, o que já foi adiantado, o fechamento do mês — não
tem por onde ser feito.

Diferente do estoque, aqui **toda rota existe**: os vinte e um casos de uso do módulo têm
controlador. O que falta é a tela.

**Com uma exceção, e ela é o buraco desta rotina.** RN-FIN-023 recusa estornar um estorno e
oferece o caminho certo por escrito: *"quem quer o valor de volta faz um lançamento novo, com
motivo e rastro próprio"*. Esse lançamento novo **não existe**. `TipoDeLancamento.AJUSTE` e
`origem_tipo = 'MANUAL'` estão no modelo e nada os escreve — a própria doc de RT-FIN-007 registra:
*"é a próxima peça natural"*.

Na prática: uma comissão apurada errada pode ser **anulada** e não pode ser **corrigida**. Quem
anula fica com o extrato certo e a pessoa sem o dinheiro, e a saída é um vale — que mente, porque
vale é adiantamento, não correção.

## 3. Atores e permissões

| Ator | Permissão | O que faz |
|---|---|---|
| Dono / gerente | `financeiro:read:all` | vê o extrato de todo mundo e a prévia |
| Dono / gerente | `financeiro:advance` | entrega vale |
| Dono / gerente | `financeiro:close` | fecha o período, estorna e ajusta o razão |
| Profissional | `financeiro:read:own` | **ainda não**: falta o vínculo usuário → profissional |

O menu "Financeiro" já aparece para quem tem `financeiro:read:own` **ou** `read:all`
(RT-IAM-006) — e hoje quem entra com perfil PROFISSIONAL chega a uma tela que não consegue
carregar nada, porque `:own` não filtra. É pendência conhecida de RT-FIN-004, e a tela **diz
isso** em vez de mostrar um erro cru.

## 4. Pré-condições

- [x] Razão, comissão e apuração (RT-FIN-003) · extrato (RT-FIN-004) · vale (RT-FIN-005)
- [x] Fechamento e repasse (RT-FIN-006) · estorno (RT-FIN-007)

## 5. Fluxo principal

1. Abre **Financeiro**. O período já vem escolhido: o último que terminou, na periodicidade do
   salão. Setas para o anterior e o seguinte; datas à mão quando for outro recorte.
2. **Quem tem a receber**: uma linha por profissional, com saldo anterior, o que o período
   produziu, o saldo final e o que sai de repasse.
3. Abre uma linha: o **extrato item a item** daquela pessoa no período, com os totais por tipo.
4. **Vale**: registra o adiantamento, e ele aparece no extrato na mesma hora.
5. **Fechar o período**: a confirmação diz o total e para quantas pessoas. Confirmado, viram
   contracheques.

## 6. Fluxos alternativos

**A1 — O número mudou entre ver e confirmar.** A prévia carrega uma `versao` (hash das posições).
Se alguém fechou uma comanda ou registrou um vale no meio, o servidor recusa com
`ER-FIN-PREVIA_DESATUALIZADA` e a tela recarrega a prévia: **o número que se confirma é o número
que se viu**, e não o que estava lá quando a página abriu.

**A2 — Período já fechado.** `ER-FIN-PERIODO_JA_FECHADO`. A tela mostra os contracheques daquele
período em vez de oferecer fechar de novo.

**A3 — Desfazer um fechamento.** Estorno com motivo (RT-FIN-007): o documento fica, marcado, e o
período é liberado. A confirmação diz quanto volta a ser devido.

**A4 — Corrigir uma linha errada.** Estornar anula; **ajustar** reaplica (RN-FIN-041). A tela
oferece os dois no mesmo lugar, porque a pergunta de quem chega ali é a mesma.

**A5 — Quem deve ao salão.** Saldo final negativo: a linha aparece, com repasse zero e a dívida
que atravessa. Some-la faria a conferência não fechar.

**A6 — Só o próprio extrato.** Quem tem apenas `financeiro:read:own` vê uma tela que explica a
pendência, sem tentar carregar o que o servidor vai recusar.

## 7. Regras de negócio aplicadas

| ID | Resumo em uma linha | Garantida em |
|---|---|---|
| RN-FIN-011 | O extrato inverte o sinal uma vez, e só ele | `ConsultarExtratoUseCase` |
| RN-FIN-014 | A competência é o dia do salão, e não retroage | `Competencia` |
| RN-FIN-020 | Fechar exige a prévia vigente, por hash | `FecharPeriodoUseCase` |
| RN-FIN-023 | Um estorno não se estorna; corrige-se com lançamento novo | `EstornarLancamentoUseCase` |
| **RN-FIN-041** | **O ajuste manual é escrito no sinal de quem recebe** | `RegistrarAjusteNoRazaoUseCase` |

## 8. O que este documento decide de desenho

**A tela é o período.** Tudo pende de "de/até": a prévia, os vales, os contracheques, o extrato.
Duas pessoas olhando períodos diferentes e concluindo que uma está errada é a conversa mais cara
deste sistema — é o que `ExtratosController` já dizia ao recusar um padrão de "mês corrente" na
API. A tela escolhe **um** padrão, visível e navegável, em vez de deixar cada seção com o seu.

**O padrão é o último período que terminou.** Fecha-se o mês depois que ele acaba. Abrir em "mês
corrente" ofereceria fechar um período pela metade, que é o erro caro; abrir em branco obrigaria a
digitar duas datas antes de ver qualquer coisa.

**A prévia é a lista, e é a confirmação.** Não são duas telas: a tabela que responde "quem tem a
receber" é literalmente a que o servidor usa para calcular o repasse, com o mesmo hash. Fazer uma
tabela "de visualização" ao lado garantiria que um dia as duas divergissem.

**Estornar e ajustar moram juntos.** Quem abre uma linha errada quer uma das duas coisas, e não
sabe o nome delas. A tela pergunta o que aconteceu — "essa linha não devia existir" ou "esse valor
está errado" — e escolhe o verbo.

**O ajuste se digita no sinal de quem recebe.** No razão, comissão é negativa: sai do salão. Um
ajuste que aumenta o que a pessoa recebe precisa ser negativo lá dentro. Pedir isso a quem digita
seria pedir que ela conhecesse a contabilidade do razão para corrigir um erro de digitação. O
extrato já inverte o sinal uma vez (RN-FIN-011); o ajuste entra pelo mesmo lado.

**Saldo negativo aparece em vermelho, e não some.** Quem adiantou mais do que ganhou atravessa o
mês devendo, e é exatamente essa linha que alguém vai perguntar. Escondê-la faria a soma da
coluna não bater com o total.

## 9. Contrato de API

```http
GET    /api/v1/fechamentos/previa?de=&ate=
POST   /api/v1/fechamentos                  { de, ate, versaoDaPrevia }
GET    /api/v1/fechamentos?de=&ate=[&profissionalId=]
POST   /api/v1/fechamentos/{id}/estorno     { motivo }

GET    /api/v1/extratos/{profissionalId}?de=&ate=
GET    /api/v1/vales?de=&ate=[&profissionalId=]
POST   /api/v1/vales                        { profissionalId, valor, forma, motivo }
POST   /api/v1/vales/{id}/cancelamento      { motivo }

POST   /api/v1/lancamentos/{id}/estorno     { motivo }
POST   /api/v1/lancamentos                  ← NOVO (RN-FIN-041)
```

**O ajuste**

```http
POST /api/v1/lancamentos
{ "profissionalId": "…", "valor": 45.00, "motivo": "comissão apurada a menos na comanda 1420" }
```

`valor` no **sinal de quem recebe**: positivo aumenta o que a pessoa tem a receber, negativo
reduz. O razão inverte uma vez, no caso de uso.

**Dinheiro sai como número.** As rotas deste módulo devolviam `Money`, que serializa como
`{"valor": …}`. Passam a ter DTO de tela, como o catálogo e o estoque já têm. O projeto tem hoje
**duas formas de dinheiro na API** — comanda e conciliação ainda usam a aninhada, porque suas
telas já a leem —, e está convergindo para o número puro. Está nas pendências.

**Erros**

| Código | Quando |
|---|---|
| `ER-FIN-PREVIA_DESATUALIZADA` | 409 — alguém mexeu no período entre ver e confirmar |
| `ER-FIN-PERIODO_JA_FECHADO` | 409 — já existe fechamento vigente no intervalo |
| `ER-FIN-ESTORNO_DE_ESTORNO` | 422 — anular uma anulação; o caminho é o ajuste |
| `ER-FIN-DADOS_INVALIDOS` | 422 — valor zero, profissional inexistente, motivo ausente |

## 10. Dados

Nenhuma migration. O ajuste escreve na tabela `lancamento` com `tipo = 'AJUSTE'` e
`origem_tipo = 'MANUAL'`, que já existem no modelo e nunca tinham sido usados.

## 11. Testes

| O que prova | Onde |
|---|---|
| O ajuste entra no razão com o sinal invertido (RN-FIN-041) | `AjusteNoRazaoIT` |
| O ajuste aparece no extrato com o sinal de quem recebe | `AjusteNoRazaoIT` |
| Ajuste exige motivo, valor não nulo e profissional que existe | `AjusteNoRazaoIT` |
| Quem não fecha período não ajusta o razão | `AjusteNoRazaoIT` |
| A competência do ajuste é o dia do salão | `AjusteNoRazaoIT` |
| Extrato, vale, prévia e fechamento saem com dinheiro em número | `RespostasDoFinanceiroTest` |
| A tela abre no último período terminado, na periodicidade do salão | `Financeiro.test.tsx` |
| Fechar leva a versão da prévia que está na tela | `Financeiro.test.tsx` |
| Prévia desatualizada recarrega em vez de insistir | `Financeiro.test.tsx` |
| Saldo negativo aparece, com repasse zero | `Financeiro.test.tsx` |
| O extrato abre pela linha e mostra os totais por tipo | `Financeiro.test.tsx` |
| Vale registra e cancela com motivo | `Financeiro.test.tsx` |
| Sem violação estrutural de acessibilidade | `Financeiro.test.tsx` |

## 12. O que foi construído

| Peça | Onde |
|---|---|
| O ajuste manual no razão | `RegistrarAjusteNoRazaoUseCase` · `POST /v1/lancamentos` |
| O id do razão na linha do extrato | `ExtratoDoProfissional.LinhaDoExtrato.lancamentoId` |
| Extrato, vale, prévia e fechamento com dinheiro em número | os DTOs dos quatro controladores |
| A periodicidade de fechamento no `/me/capabilities` | `Capacidades.EstabelecimentoResumo` |
| A tela | `frontend/src/telas/financeiro/` · rota `/financeiro` |
| Prévia sem backend | `npm run previa:financeiro` |
| **A tela `SemTela` foi apagada** | era a última área sem tela |

**Testes.** 10 de integração (`AjusteNoRazaoIT`), 5 de unidade (`RespostasDoFinanceiroTest`) e 31
de tela (`Financeiro.test.tsx`, `periodo.test.ts`), incluindo acessibilidade com axe.

**Rodada de mutação: 17 mutações, 17 mortas** — uma sobreviveu na primeira passada e virou teste:
nada provava que **cada ajuste é um documento próprio**. Com `origem_id` apontando para o
profissional, anular um ajuste anularia todos os ajustes daquela pessoa, de todos os meses.

**Dois defeitos achados pelos próprios testes**, não pela rodada:

1. **"Período anterior" de um mês inteiro andava trinta e um dias**, caindo em 29/01–28/02 — que
   não é o mês anterior e não é nada. Agora um mês inteiro anda um mês; qualquer outro recorte
   anda o próprio tamanho.
2. **A tela disparava três consultas antes de saber a permissão.** Quem só tem
   `financeiro:read:own` via três 403 antes da explicação de por que a tela não abre.

E um terceiro, achado na prévia: `origem_id` é `not null` no razão, e o ajuste não tem tabela
própria. O documento dele passou a ser ele mesmo, com id sorteado — o que mantém o par
`(origem_tipo, origem_id)` significando a mesma coisa que significa nas outras linhas.

## 13. Pendências

- [ ] **Duas formas de dinheiro na API.** Comanda e conciliação ainda devolvem `Money` aninhado
      (`{"valor": …}`); catálogo, estoque, acessos e financeiro devolvem número. Unificar exige
      tocar nas duas telas antigas, e é trabalho de uma rotina própria.
- [ ] **`financeiro:read:own` continua sem filtrar.** A tela explica em vez de falhar, e a
      explicação some no dia em que existir o vínculo `usuario` → `profissional` (RT-IAM-009).
- [ ] **Sem paginação no extrato.** Um mês movimentado passa de cem linhas, e a rotina devolve
      todas — pendência que RT-FIN-004 já registrava.
- [ ] **Não há comprovante de repasse para imprimir.** O repasse é conferido no papel, e a tela
      não imprime nada.
- [ ] **Não há fechamento parcial por profissional**: é o salão inteiro ou nada.
- [ ] **O repasse não registra por onde o dinheiro saiu**, e por isso não concilia com o caixa.
- [ ] **Estornar um fechamento não avisa quem recebeu** — a pessoa descobre no extrato.
- [ ] **Regra de comissão não tem tela.** `/v1/regras-de-comissao` tem quatro rotas e nenhuma
      delas é alcançável: a cascata se configura, hoje, só pelo percentual da ficha do
      profissional. É a próxima peça natural do módulo.
