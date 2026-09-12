---
id: RT-FIN-008
titulo: Comprovante de atendimento
modulo: atendimento
fase: 2
perfil: completo
status: implementado
depende_de: [RT-ATD-006, RT-NOT-001]
permissoes: [comanda:open, comanda:close]
eventos: []
regras: [RN-ATD-026, RN-ATD-027, RN-ATD-028, RN-CLI-031]
atualizado_em: 2026-09-12
---

# RT-FIN-008 — Comprovante de atendimento

## 1. Objetivo

Dar à cliente o recibo do que ela pagou — no papel da térmica do balcão ou pela mensagem — sem
fingir que é nota fiscal.

## 2. Por que a rotina está no módulo `atendimento`, e não no `financeiro`

O plano a numerou **RT-FIN-008**, e o número é só um número.

O comprovante mostra os **itens** da comanda. O módulo `financeiro` não sabe o que é uma comanda —
`comandaId` é id de origem opaco (RN-FIN-002), e essa opacidade é o que impede o ciclo:
`atendimento` já depende de `financeiro` para perguntar quanto foi pago. Montar o comprovante lá
exigiria `financeiro → atendimento`, e o Spring Modulith reprovaria o build.

A alternativa seria enfeitar o evento `ComandaFechada` com o recibo inteiro, e ela é pior pelo
motivo que RN-FIN-009 já deu: o payload de `event_publication` **não tem RLS** — e aqui o payload
teria nome de cliente e o que ela consumiu.

Então a rotina mora onde o dado mora. O id fica `RT-FIN-008` para o plano e os cruzamentos
continuarem achando-a.

## 3. Atores e permissões

Emitir: `comanda:open` **ou** `comanda:close` — é leitura de uma comanda fechada, e quem trabalha
o balcão precisa imprimir. Enviar pela mensagem: `comanda:close`, porque sai do salão em nome dele.

## 4. Pré-condições

Comanda existe neste salão e está **`FECHADA`**. Para o envio, a cliente tem telefone (quem recusa
por falta de destino é o `notificacao`).

## 5. Fluxo principal

Lê a comanda com itens · confere o status · busca os pagamentos por `PagamentoApi.daOrigem` · busca
o nome da cliente e o nome/fuso do salão · monta a projeção · renderiza em 48 colunas quando o
pedido é de texto.

Envio: monta a mesma projeção (então a recusa de comanda não fechada vale nos dois caminhos, sem a
regra escrita duas vezes) e enfileira com `referencia = comandaId`.

## 6. Fluxos alternativos

| Situação | Resposta |
|---|---|
| comanda aberta, reaberta ou estornada | `ER-ATD-COMPROVANTE_EXIGE_FECHADA` (409) |
| comanda de outro salão | 404 — a RLS não a enxerga |
| segundo clique em enviar | a **mesma** mensagem, não uma nova |
| cliente sem telefone | recusa do `notificacao`, com a mensagem dele |

## 7. Regras de negócio aplicadas

RN-ATD-026 (projeção, e só de fechada) · RN-ATD-027 (papel leva itens, mensagem não) ·
RN-ATD-028 (48 colunas) · RN-CLI-031 (execução de contrato) · e RN-ATD-010, que é o que torna o
comprovante reprodutível.

## 8. Contrato de API

| Rota | Devolve |
|---|---|
| `GET /api/v1/comandas/{id}/comprovante` | o documento em JSON, para a tela |
| `GET /api/v1/comandas/{id}/comprovante/texto` | `text/plain`, 48 colunas, para a térmica |
| `POST /api/v1/comandas/{id}/comprovante/envio` | `{ "mensagemId": …, "comandaId": … }` |

**Texto, e não bytes de ESC/POS.** A impressora é do salão, está ligada na máquina do balcão, e
todo salão tem outro modelo. Mandar comandos daqui exigiria o servidor conhecer o modelo dela.
Texto de largura fixa é o denominador comum: serve para a impressora local, para a prévia na tela e
para copiar e colar.

## 9. Dados

**Nenhuma tabela.** V44 só estende dois `check` de `finalidade` — o de `cliente_consentimento` e o
de `mensagem`. O segundo é fácil de esquecer: sem ele o envio falharia só na hora de gravar a
mensagem, em produção.

O estado do envio é a própria `mensagem` (V30), pela referência da comanda.

## 10. O que o papel diz

```
================================================
                STUDIO BERTOLDO
                  Comanda #128
              12/09/2026 às 23:50
------------------------------------------------
Maria Silva
------------------------------------------------
Coloração
  1 x 170,00                              153,00
  desconto                                -17,00
Shampoo pós-coloração 300ml
  1 x 45,00                                40,50
  desconto                                 -4,50
Escova modeladora com finalização e brilho
  2 x 60,00                               120,00
------------------------------------------------
Bruto                                     215,00
Desconto                                  -21,50
TOTAL                                     193,50
------------------------------------------------
Pix                                       100,00
Crédito 3x                                213,50
================================================
             NÃO É DOCUMENTO FISCAL
```

Sem "R$" nas linhas: em 48 colunas, dois caracteres por linha são um item a mais que cabe. O
símbolo aparece na mensagem, onde não há limite de largura.

## 11. Concorrência e idempotência

Emitir é leitura pura e reprodutível. Enviar é idempotente pela referência da comanda — o mesmo
mecanismo que impede o job de varredura de mandar seis lembretes do mesmo horário.

## 12. UX e front

Ainda não há tela. Quando houver, o botão de imprimir chama a rota de texto e manda para a
impressora local; o de enviar cai sob R-UX-05, com confirmação e o número na frase.

## 13. Testes obrigatórios

- [x] `TextoDoComprovanteTest` — 9 cenários unitários: nenhuma linha passa de 48 colunas · **toda
  linha com valor fecha na coluna 48** · sempre avisa que não é documento fiscal · a data é a do
  salão, não a do servidor · desconto zero não vira linha · **o detalhe é recuado e o total não** ·
  corte sem reticências · quantidade inteira sem casas · parcela única não aparece.
- [x] `ComprovanteIT` — 8 cenários: documento completo · texto pronto para a térmica · **aberta não
  tem comprovante** · **reabrir invalida** · dois comprovantes são idênticos · **segundo clique não
  manda segunda mensagem** · enviar de reaberta é recusado · isolamento entre salões.

## 14. Decisões e trade-offs

| Decisão | Alternativa descartada | Por quê |
|---|---|---|
| rotina no `atendimento` | no `financeiro`, como o id sugere | `financeiro → atendimento` é ciclo |
| projeção, nada gravado | tabela de comprovante | a cópia é a que discorda depois de um estorno |
| texto de 48 colunas | bytes de ESC/POS | todo salão tem outro modelo de impressora |
| mensagem sem os itens | recibo completo no WhatsApp | minimização: é informação sobre o corpo de alguém |
| só comanda fechada | emitir de qualquer status | recibo de comanda aberta é desmentido no item seguinte |
| aviso de "não fiscal" no renderizador | deixar para quem chama | numa das saídas ele sumiria |

## 15. Pendências

- [ ] **Não avisa quando deixa de valer.** Reabrir recusa a emissão de um novo comprovante, mas a
      cliente que já tem o papel não é avisada — é a pendência de RT-ATD-007, ainda aberta.
- [ ] **Fechar comanda não oferece o comprovante.** Hoje alguém precisa pedir; o natural é a tela
      de fechamento oferecer imprimir e enviar no mesmo passo.
- [ ] **58mm não é suportado.** A largura é fixa em 48 colunas (80mm). Térmicas de 58mm usam 32.
- [ ] **Acentos dependem da página de código da impressora.** O texto sai em UTF-8 correto; quem
      transliterar, se precisar, é o driver no balcão. Pode sair "Colora‡Æo" em impressora mal
      configurada.
- [ ] **O modelo da mensagem não está aprovado no provedor.** WhatsApp exige modelo pré-aprovado, e
      isso é da Fase 4 (RT-CNV-001).
- [ ] **Não há teste HTTP das três rotas.**
- [ ] **Não há reimpressão registrada.** Imprimir duas vezes não deixa rastro; para recibo não
      fiscal é aceitável, mas o dono pode querer saber.
