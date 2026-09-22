---
id: RT-EST-012
titulo: "Campanha de queima de lote próximo do vencimento"
modulo: estoque
fase: 6
perfil: completo
status: implementado
depende_de: [RT-EST-010, RT-EST-011, RT-EST-008]
permissoes: [estoque:read, produto:price:write]
eventos: []
regras: [RN-EST-037, RN-EST-038, RN-EST-039]
atualizado_em: 2026-09-21
---

# RT-EST-012 — Queimar o que vai vencer, sem perder dinheiro

## 1. Objetivo

Transformar em dinheiro o lote que ia para o lixo. Dez máscaras com custo de R$ 31,20 são R$ 312 que
viram zero no dia do vencimento; vender a preço de custo recupera os R$ 312.

## 2. As decisões — do dono, em 21/09/2026

| Pergunta | Decisão |
|---|---|
| Pode vender abaixo da margem mínima? | **Até o custo, sem prejuízo** — a única exceção ao chão, e só aqui |
| Quando o lote entra | **30 dias antes do vencimento** — a janela do alerta que o estoque já usa |
| Avisa as clientes? | **Não.** Só o preço muda |
| Como desconta | **Escalona com o prazo**: começa leve, chega ao custo na última semana |

## 3. Não é uma campanha nova: é o quinto motivo

A rotina não cria tela, aprovação nem trilha. A queima entra no motor de RT-EST-010 como o motivo
`QUEIMA_DE_VENCIMENTO`, e daí para frente é o fluxo que já existe: aparece em **Preços**, o dono aprova
ou recusa em RT-EST-011, e a decisão vai para a mesma trilha.

É a forma certa, e não só a mais barata. "Nenhuma sugestão é aplicada sem clique humano" é a frase da
Fase 6, e uma segunda porta para mudar preço seria uma segunda porta para esquecê-la.

## 4. O chão desce para o custo — e só aqui

O chão de RT-EST-010 é `custo médio × (1 + margem mínima)`. Na queima ele desce, em degraus, até o
**custo médio** — margem zero, sem prejuízo. Nunca abaixo.

É a única exceção ao chão no sistema inteiro, e ela fica **visível**: o motivo aparece no cartão, na
decisão e na trilha. Ninguém descobre depois que um preço foi aprovado abaixo da régua sem saber por
quê.

**Custo médio, e não o custo do lote que vence.** O preço é do produto, e a prateleira pode ter
unidades de lotes diferentes. Vender a queima pelo custo do lote barato faria as unidades do lote caro
saírem no prejuízo quando ele acabasse. O médio é conservador de propósito: nenhuma unidade sai abaixo
do que o estoque, em média, custou.

## 5. A escada do desconto

O chão da queima desce em quatro degraus, pela distância até o vencimento do lote mais próximo:

| Dias até vencer | Margem mínima aplicada | Com margem de 30% |
|---|---|---|
| 30 a 22 | 3/4 da margem mínima | 22,5% |
| 21 a 15 | metade | 15% |
| 14 a 8 | 1/4 | 7,5% |
| 7 ou menos | **zero — o custo** | 0% |

**Degraus, e não uma linha contínua.** Um preço que muda todo dia exigiria uma aprovação por dia. Em
degrau, o dono decide quatro vezes no pior caso.

O preço resultante passa pela **régua de 9,90**, arredondado para cima — então nunca fica abaixo do
degrau. O efeito é o mesmo de RT-EST-010: em produto barato os degraus colapsam (custo de R$ 31,20
vai de R$ 49,90 para R$ 39,90 e para aí), e em produto caro eles aparecem todos.

## 6. Produto que vai vencer não sobe de preço

A queima vem **antes** de todos os outros motivos. E se o preço de queima não fica abaixo do de hoje —
o produto já é vendido barato —, o motor **não sugere nada**, em vez de cair nos motivos seguintes.

A razão: o motivo seguinte seria "margem abaixo do mínimo", que sugere **subir** o preço. Subir o preço
de algo que vence em duas semanas é trabalhar contra a queima.

## 7. O que não entra

- **Lote vencido.** Cosmético vencido não se vende; a queima olha só o lote mais próximo **ainda
  válido** e com unidades. O vencido continua no alerta de RT-EST-008, que é onde ele pede atenção.
- **Mensagem para clientes.** Decisão do dono: não há provedor de WhatsApp ligado, e uma campanha de
  marketing que enche a fila sem chegar a ninguém é pior que nenhuma. O plano listava RT-NOT-001 como
  dependência — ela fica para quando houver provedor.

## 8. FEFO é o que faz o preço por produto funcionar

A baixa sai sempre do lote que vence primeiro (RT-EST-004). Então baixar o preço **do produto** vende
primeiro **o lote que está vencendo** — e é isso que torna honesto aplicar a queima no preço de
prateleira, que não sabe de lote.

## 9. As regras

| Regra | O quê |
|---|---|
| RN-EST-037 | Na queima de vencimento o chão desce, em degraus, até o custo médio — e só nela |
| RN-EST-038 | A queima vem antes dos outros motivos, e produto que vai vencer nunca recebe sugestão de subir |
| RN-EST-039 | A queima olha o lote válido mais próximo com unidades, até 30 dias; vencido fica de fora |

## 10. Contrato de API

Nenhuma rota nova. O motivo novo aparece nas rotas de RT-EST-010 e RT-EST-011:

```json
{ "motivo": "QUEIMA_DE_VENCIMENTO", "rotulo": "Queima de vencimento",
  "precoAtual": 49.90, "custoMedio": 31.20, "precoSugerido": 39.90,
  "margemSugerida": 27.88,
  "racional": "Lote vence em 5 dias, com 10 em estoque. Na última semana a queima vai até o custo médio de R$ 31,20; R$ 39,90 na régua." }
```

A aprovação (`POST /api/v1/sugestoes-de-preco/{id}/aprovacao`) aceita, para este motivo, qualquer preço
a partir do **custo médio** — e não do chão de margem.

## 11. Dados — `V64`

`sugestao_de_preco.motivo` e `decisao_de_preco.motivo` passam a aceitar `QUEIMA_DE_VENCIMENTO`.
Nenhuma tabela nova.

## 12. Observabilidade

- O log da geração conta as queimas à parte: `Sugestões de preço: N geradas (N de queima) de N
  produtos avaliados (N caladas por recusa)` — é a única sugestão abaixo da margem mínima.
- A trilha mostra o motivo em cada decisão — a exceção ao chão fica à vista.

## 13. Testes

**`QueimaDeVencimentoTest`** (10) — a última semana chegando ao custo; os quatro degraus; as bordas de
cada degrau (30, 22, 21, 15, 14, 8, 7); a janela de 30 dias; lote vencido fora; produto que vai vencer
não subindo de preço; produto sem preço recebendo o de queima; a queima vindo antes do encalhado; só a
queima com o chão no custo; e a varredura `nunca_abaixo_do_custo`, que cruza custo × preço × dias ×
margem e confere, em cada combinação, que a queima nunca fica abaixo do custo **e nunca sobe o preço**.

**`QueimaDeVencimentoIT`** (11), com Postgres de verdade — a validade dos lotes chegando ao motor;
fora da janela; lote vencido; dois lotes, manda o mais próximo; lote sem validade; a queima primeiro na
lista; aprovar abaixo da margem e acima do custo; abaixo do custo recusado; a exceção valendo só para
a queima; a trilha mostrando o motivo; e produto que vai vencer sem sugestão de subir.

**Front** — `Precos.test.tsx` (+1): o cartão da queima com a etiqueta, a conta do degrau e a margem.

`MotorDePrecoTest` continua com os 13 de RT-EST-010, agora com o campo novo nulo — a varredura do chão
de margem segue provando que, sem lote vencendo, nada mudou.

## 14. O que apareceu no caminho

- **A lista punha a queima por último.** A ordenação das sugestões não conhecia o motivo novo e o jogava
  no `else` — o mais urgente aparecendo depois do preço quebrado. `queima_primeiro` prova a ordem, com
  nomes escolhidos para que a ordem alfabética dissesse o contrário.
- **Um comentário de teste mentia.** Escrevi que a entrada de lote recusa lote vencido; não recusa. O
  comentário agora diz o motivo real de mexer no banco: simular o tempo passando.
- **"R$" num fim de linha, o número na seguinte.** O racional é texto do servidor, com espaço comum; no
  celular ele quebrava no meio do dinheiro. Passou a usar espaço inseparável, como o `Intl` do front.

## 15. Mutações verificadas

Uma rodada, 13 mutações, cada uma sozinha. **13 mortas**, todas pelo teste esperado.

| # | Mutação | Morta por |
|---|---|---|
| L01 | janela de 31 dias | `QueimaDeVencimentoTest.janela` |
| L02 | lote vencido entra na queima | `QueimaDeVencimentoTest.vencido_fica_de_fora` |
| L03 | a última semana não chega ao custo | `.ultima_semana_no_custo`, `.bordas_dos_degraus` |
| L04 | a borda do degrau de 14 dias escorrega | `.bordas_dos_degraus` |
| L05 | todos os degraus exigem a margem cheia | `.quatro_degraus`, `.ultima_semana_no_custo` |
| L06 | a queima pode subir o preço | `.nunca_sobe`, `.nunca_abaixo_do_custo` |
| L07 | sem queima possível, cai nos outros motivos e manda subir | `.nunca_sobe`, `QueimaDeVencimentoIT.nao_sobe_o_que_vence` |
| L08 | a aprovação da queima usa o chão de margem | `QueimaDeVencimentoIT.queima_aprova_abaixo_da_margem` |
| L09 | todo motivo desce ao custo na aprovação | `.so_a_queima_desce_ao_custo`, `QueimaDeVencimentoIT.excecao_so_da_queima` |
| L10 | a queima aprova abaixo do custo | `QueimaDeVencimentoIT.queima_nao_vai_abaixo_do_custo` |
| L11 | manda o lote que vence por último | `QueimaDeVencimentoIT.lote_mais_proximo` |
| L12 | a consulta não descarta lote vencido | `QueimaDeVencimentoIT.vencido_nao_queima` |
| L13 | a queima não aparece primeiro na lista | `QueimaDeVencimentoIT.queima_primeiro` |

## 16. Pendências

- [ ] **Mensagem para as clientes** — decisão do dono: fica para quando houver provedor de WhatsApp
      ligado. Aí entram o modelo de marketing (aprovado previamente pela Meta), o consentimento que
      RT-NOT-001 já confere, e a pergunta de para quem mandar.
- [ ] **A queima não acaba sozinha.** Quando o lote que vencia é todo vendido, o preço de queima
      continua no produto até alguém mudar. A próxima geração deixa de sugerir a queima, mas não sugere
      voltar ao preço normal — o motor olharia a margem nova e poderia pedir para subir, que é o certo,
      só que como "margem abaixo do mínimo", sem dizer que é o fim da queima.
- [ ] **Degraus fixos.** 3/4, 1/2, 1/4 e zero da margem mínima, e a janela de 30 dias, são constantes.
- [ ] **Sem dizer quanto foi recuperado.** Quanto dinheiro a queima salvou de virar lixo é o número que
      mostraria que ela funciona, e ele não existe.
