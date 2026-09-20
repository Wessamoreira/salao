---
id: RT-FIN-012
titulo: "Taxa por bandeira/parcela e previsão de recebíveis (D+1, D+30)"
modulo: financeiro
fase: 5
perfil: completo
status: implementado
depende_de: [RT-FIN-010, RT-FIN-001]
permissoes: [config:manage, financeiro:read:all]
eventos: []
regras: [RN-FIN-037, RN-FIN-038, RN-FIN-039, RN-FIN-040]
atualizado_em: 2026-09-20
---

# RT-FIN-012 — A taxa da maquininha e quando o dinheiro cai

## 1. Objetivo

Responder a pergunta que faz o caixa do dono não bater com o faturamento: **do que foi vendido no
cartão, quanto chega e quando**.

- **Quanto chega** — a taxa que a adquirente retém, prevista pelo contrato e conferida contra o extrato;
- **Quando** — D+1 no débito, D+30 no crédito, e parcela a parcela no parcelado, sempre em dia útil
  bancário.

O título do plano diz "por bandeira/parcela". **Por bandeira ficou de fora, e é decisão do dono**: o
contrato do salão é negociado por produto e faixa de parcela, e a recepção quase nunca informa a
bandeira — uma taxa por bandeira ficaria sem saber qual usar na maioria das vendas.

## 2. As decisões — do dono, em 20/09/2026

| Pergunta | Decisão |
|---|---|
| De onde vem a taxa | **Cadastro do que foi negociado**, e o extrato confere |
| Granularidade | **Produto e faixa de parcela** — débito, crédito à vista, 2-6x, 7-12x |
| Comissão da profissional | **Continua sobre o valor cheio** — a taxa é custo do salão |
| Parcelado | **Parcela a parcela**: a primeira em D+30, as seguintes de 30 em 30 |
| Taxa no razão | **Só quando o extrato chega** — valor real, nunca estimativa |
| Venda que cai em fim de semana | **Próximo dia útil bancário** |

**A comissão não muda, e isso é o que mantém RT-FIN-002 e RT-FIN-006 intactos.** Se a taxa entrasse no
cálculo, todo fechamento já pago passaria a ter outro número certo.

## 3. O cadastro — quatro campos

| Faixa | O que é | Prazo padrão |
|---|---|---|
| `DEBITO` | débito | D+1 |
| `CREDITO_A_VISTA` | crédito em 1x | D+30 |
| `CREDITO_2_6` | crédito de 2 a 6 parcelas | primeira em D+30 |
| `CREDITO_7_12` | crédito de 7 a 12 parcelas | primeira em D+30 |

Cada faixa tem **percentual** e **dias até a primeira parcela**. Entre parcelas são 30 dias, que é o
calendário da adquirente e não uma escolha do salão — vira constante do domínio, com nome.

**Sem cadastro não há previsão**, e isso é de propósito: um percentual chutado vira número na tela do
dono, e número na tela do dono vira decisão. Enquanto a faixa não estiver cadastrada, a venda aparece
como "taxa não cadastrada" — que é a verdade.

## 4. A previsão mora no pagamento, fotografada

Ao registrar um pagamento no cartão (RT-FIN-001), o sistema grava `taxa_prevista` e
`previsao_recebimento` — as colunas existem desde `V36`, esperando esta rotina.

**Fotografada, e não recalculada**: é a mesma decisão do snapshot da comissão (RN-ATD-010). Renegociar
a taxa em dezembro não pode mudar o que o sistema prometeu em setembro; quem quiser saber o que foi
prometido precisa achar o número, e não reconstruí-lo a partir de um contrato que já mudou.

`previsao_recebimento` é a data da **primeira** parcela, já em dia útil. Não há tabela de parcela: o
cronograma é determinístico, e uma tabela a mais seria uma tabela a mais para sair de sincronia.

**Toda parcela conta a partir do dia da venda**, e não da parcela anterior. A diferença aparece quando a
primeira cai num sábado: ela é empurrada para segunda, e contar a segunda a partir da segunda-feira
empurraria todas as seguintes junto — dois dias de erro na primeira viram duas semanas na décima
segunda. É por isso que o prazo contratado também é fotografado (`dias_para_receber`): é ele que permite
voltar ao dia da venda para remontar o cronograma.

## 5. O calendário bancário

Dia útil bancário não é dia em que o salão abre: é dia em que o banco liquida. O salão trabalha no
feriado; o dinheiro, não.

Os feriados bancários nacionais são **deriváveis**, e por isso ficam em código e não em tabela: as datas
fixas (1/1, 21/4, 1/5, 7/9, 12/10, 2/11, 15/11, 20/11, 25/12) e as que dependem da Páscoa (Carnaval,
Sexta-Feira Santa, Corpus Christi), pelo algoritmo de Gauss. Somam-se 24/12 e 31/12, em que não há
liquidação. Pedir ao dono que digite doze datas por ano seria transformar regra conhecida em trabalho
manual — e em erro.

**Feriado municipal fica de fora**, e está nas pendências: ele muda por cidade, e não há de onde derivá-lo.

## 6. A taxa real, no razão

O extrato traz o **valor líquido** de cada venda. A taxa real é `bruto − líquido`, e é ela — nunca a
prevista — que vira `TAXA_ADQUIRENTE` no razão, negativa, com origem na transação.

- **Uma vez por venda**, garantido pelo próprio razão: `pendentesDeEstorno('ADQUIRENTE', id)` responde se
  aquela venda já tem linha. É a mesma forma do vale (RT-FIN-005) e da resolução (RT-FIN-011).
- **Venda cancelada estorna a taxa** — a adquirente devolve o que reteve.
- **Sem valor líquido no arquivo não há linha.** A coluna é opcional no mapeamento (RT-FIN-009); sem ela
  o sistema não sabe a taxa real, e inventar seria pior que não lançar.

**A competência é o dia da venda**, e não o da importação. Aqui a diferença com o ajuste de RT-FIN-011 é
real: lá a correção é uma decisão tomada hoje sobre algo que ninguém sabia; aqui a taxa é custo conhecido
de uma venda datada. E é seguro — a contraparte é o salão, então nenhum repasse já pago muda de valor.

## 7. A conferência da taxa

Com o cadastro de um lado e o extrato do outro, dá para responder **"a Stone está cobrando o que
combinou?"**. A conferência ganha uma linha: quantas vendas do mês tiveram taxa real acima da contratada,
e quanto de diferença somam.

É o motivo de ter escolhido cadastro em vez de só aprender do extrato: quem só aprende do extrato passa a
achar normal o que a adquirente cobrar.

**Um centavo de tolerância por venda.** A adquirente arredonda para centavos e o contrato tem quatro
casas; sem a folga, metade das vendas apareceria como cobrança a mais, e o aviso deixaria de ser lido —
a mesma razão de bandeira e parcelas só divergirem quando informadas (RN-FIN-030).

## 8. As regras

| Regra | O quê |
|---|---|
| RN-FIN-037 | A taxa é cadastrada por faixa; sem cadastro não há previsão, e a tela diz isso |
| RN-FIN-038 | A previsão é fotografada no pagamento; parcela a parcela, no próximo dia útil bancário |
| RN-FIN-039 | O razão recebe a taxa real do extrato, uma vez por venda, na competência da venda; cancelada estorna |
| RN-FIN-040 | Taxa real acima da contratada vira aviso na conferência |

## 9. Contrato de API

**As taxas contratadas**

```http
GET /api/v1/adquirente/taxas
PUT /api/v1/adquirente/taxas
```

```json
{ "adquirente": "STONE",
  "faixas": [ { "faixa": "DEBITO", "percentual": 1.99, "diasParaReceber": 1 },
              { "faixa": "CREDITO_A_VISTA", "percentual": 3.49, "diasParaReceber": 30 },
              { "faixa": "CREDITO_2_6", "percentual": 4.29, "diasParaReceber": 30 },
              { "faixa": "CREDITO_7_12", "percentual": 5.19, "diasParaReceber": 30 } ] }
```

**A previsão de recebíveis** — por dia do salão, no máximo 180 dias.

```http
GET /api/v1/recebiveis?de=2026-09-20&ate=2026-12-20
```

```json
{ "de": "2026-09-20", "ate": "2026-12-20",
  "bruto": 42300.00, "taxa": 1584.75, "liquido": 40715.25, "semPrevisao": 0,
  "dias": [ { "dia": "2026-09-21", "parcelas": 7, "bruto": 820.00, "taxa": 16.32,
              "liquido": 803.68 } ] }
```

**Erros**

| Status | Código | Quando |
|---|---|---|
| 403 | `ER-INF-ACESSO_NEGADO` | sem `config:manage` (gravar) ou `financeiro:read:all` (ler) |
| 422 | `ER-FIN-DADOS_INVALIDOS` | percentual fora de 0–100, prazo fora de 0–120, faixa repetida ou faltando |
| 422 | `ER-FIN-FILTRO_INVALIDO` | período invertido ou maior que 180 dias |

## 10. Dados — `V61`

- `taxa_do_adquirente` — uma linha por salão, adquirente e faixa: percentual, dias até a primeira
  parcela e quem mexeu por último.
- `pagamento.taxa_prevista` e `.previsao_recebimento` passam a ser preenchidos (as colunas existem desde
  `V36`), e ganham companhia: `dias_para_receber`. Sem o prazo guardado, a segunda parcela teria de sair
  da data da primeira — que já foi empurrada para o dia útil —, e o erro se acumularia a cada parcela.
- `lancamento.origem_tipo` passa a aceitar `ADQUIRENTE`.

`taxa_do_adquirente` aceita `update` (renegociar é o caminho normal) e recusa `delete`: a previsão já
fotografada nos pagamentos continua valendo, e uma faixa sumindo deixaria venda nova sem previsão em
silêncio.

## 11. Observabilidade

- `Taxa da adquirente: N vendas, R$ X retidos` a cada importação.
- A conferência mostra quanto a taxa real passou da contratada no mês — é a observação do dono.

## 12. Testes

**`CalendarioBancarioTest`** (8) — a Páscoa pelo algoritmo em quatro anos; os feriados que andam com
ela; as datas fixas, inclusive 20/11 e os dois dias sem liquidação de dezembro; dia útil que não anda;
fim de semana; Natal na sexta; 31/12 atravessando o ano; e o feriado no meio da semana, em que o salão
abre e o banco não.

**`RecebimentoTest`** (10) — débito em D+1 caindo de sábado para segunda; crédito à vista empurrado de
domingo; **toda parcela contada do dia da venda**, e não da anterior; o rateio fechando exatamente com o
valor da venda; o líquido de cada parcela; zero parcela tratada como uma; a faixa saindo da forma e do
número de parcelas, inclusive acima de 12; o que não passa por adquirente não ter faixa; e o percentual
sendo percentual.

**`RecebiveisIT`** (18), com Postgres de verdade — o cadastro e a leitura das quatro faixas; cadastro
parcial recusado; percentual e prazo fora da régua; renegociar substituindo; a previsão fotografada no
pagamento; a faixa mudando com as parcelas; sem contrato sem previsão; dinheiro e Pix sem taxa; a
previsão somada por dia; o parcelado virando uma linha por parcela; período inválido; a taxa real no
razão na competência da venda; reimportar não lançando duas vezes; cancelada estornando; sem líquido sem
lançamento; taxa zero não virando linha; isolamento entre salões; e as duas permissões.

**Front** — `Conciliacao.test.tsx` (+4): o total que cai na conta com bruto e taxa ao lado; o aviso de
venda sem previsão; as taxas com vírgula e o envio das quatro faixas; e a faixa em branco travando o
botão.

## 13. Mutações verificadas

Duas rodadas, 22 mutações, cada uma sozinha. **22 mortas** — uma só depois de a rodada mostrar que o
teste passava por outro motivo.

| # | Mutação | Morta por |
|---|---|---|
| I01 | Páscoa um dia depois | `CalendarioBancarioTest.pascoa` |
| I02 | carnaval liquida | `CalendarioBancarioTest.feriados_moveis` |
| I03 | 20 de novembro liquida | `CalendarioBancarioTest.feriados_fixos` |
| I04 | 24 e 31 de dezembro liquidam | `CalendarioBancarioTest.natal`, `.virada_do_ano` |
| I05 | domingo liquida | `CalendarioBancarioTest.fim_de_semana` |
| I06 | a data não é empurrada para o dia útil | `RecebimentoTest.debito`, `.credito_a_vista` |
| I07 | a parcela conta da anterior, e não da venda | `RecebimentoTest.parcelas_contam_da_venda` |
| I08 | 31 dias entre parcelas | `RecebimentoTest.parcelas_contam_da_venda` |
| I09 | rateio sem absorver a sobra | `RecebimentoTest.rateio_fecha` |
| I10 | 6 parcelas caem na faixa de 7 a 12 | `RecebimentoTest.faixa` |
| I11 | débito cai na faixa do crédito à vista | `RecebimentoTest.faixa` |
| I12 | percentual tratado como decimal | `RecebimentoTest.percentual`, `RecebiveisIT.faixa_pelas_parcelas` |
| I13 | cadastro pela metade aceito | `RecebiveisIT.cadastro_parcial_recusado` |
| I14 | percentual e prazo fora da régua aceitos | `RecebiveisIT.valores_invalidos` |
| I15 | sem contrato a previsão vira zero | `RecebiveisIT.sem_contrato_sem_previsao` |
| I16 | a taxa é lançada de novo a cada rodada | `RecebiveisIT.taxa_rodar_de_novo` |
| I17 | venda cancelada não devolve a taxa | `RecebiveisIT.taxa_estornada_quando_cancela` |
| I18 | a taxa entra na competência da importação | `RecebiveisIT.taxa_real_no_razao` |
| I19 | venda isenta vira linha de zero | `RecebiveisIT.taxa_zero_nao_vira_linha` |
| I20 | conferência de taxa sem a tolerância de centavo | `RecebiveisIT.taxa_dentro_do_contrato` |
| I21 | cadastrar taxa sem `@PreAuthorize` | `RecebiveisIT.sem_permissao` |
| I22 | previsão sem teto de período | `RecebiveisIT.periodo_invalido` |

**I16 passava pelo motivo errado.** `taxa_uma_vez_so` importa o mesmo arquivo duas vezes e conta uma
linha de taxa — mas a venda repetida **não atualiza a importação que a tocou por último**, então ela
nem chega ao cálculo. O teste provava outra coisa. A guarda do "já lançada" ficou: ela protege a rodada
repetida, e sem ela a taxa dobraria em silêncio no dia em que a reimportação passar a marcar a venda.
`taxa_rodar_de_novo` chama a rotina duas vezes sobre a mesma importação — a mesma propriedade que
`ConciliacaoIT.rodar_de_novo` prova para o casamento.

**Um defeito do método, não do código.** No `-Dtest` do Maven, `+` só separa métodos da **mesma**
classe; entre classes é vírgula. `A#m+B#n` roda um teste só, em silêncio — e a mutação morre por um
teste diferente do documentado. Seis mutações das duas rotinas foram refeitas com o filtro certo para
que esta tabela diga o que aconteceu.

## 14. Pendências

- [ ] **Feriado municipal não entra.** Muda por cidade e não há de onde derivá-lo; a previsão erra por
      um dia no aniversário da cidade e afins.
- [ ] **Sem antecipação.** O salão que antecipa recebíveis recebe em D+1 com taxa maior, e a previsão
      vai mostrar o calendário sem antecipação. Vira configuração quando for o caso.
- [ ] **Uma adquirente só.** O modelo é por adquirente desde `V58`, mas o cadastro e a tela falam Stone.
- [ ] **A previsão não sabe o que já caiu.** Ela mostra o futuro contratado; não há baixa de recebível
      contra o extrato bancário. Conferir o que caiu de verdade é outra rotina.
- [ ] **A taxa real não corrige a previsão do pagamento.** `taxa_prevista` continua sendo o que foi
      prometido; quem quiser o realizado olha o razão.
- [ ] **A conferência de taxa não diz quais vendas.** Mostra quantas e quanto; a lista venda a venda
      ainda não existe.
