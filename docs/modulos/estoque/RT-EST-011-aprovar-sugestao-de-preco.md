---
id: RT-EST-011
titulo: "Aprovação humana + registro do racional da sugestão"
modulo: estoque
fase: 6
perfil: completo
status: implementado
depende_de: [RT-EST-010]
permissoes: [estoque:read, produto:price:write]
eventos: []
regras: [RN-EST-034, RN-EST-035, RN-EST-036]
atualizado_em: 2026-09-20
---

# RT-EST-011 — Quem decide é gente, e fica registrado

## 1. Objetivo

Fechar a Fase 6: **nenhuma sugestão é aplicada sem clique humano**, e toda decisão — aplicar ou
recusar — fica registrada com os números que a motivaram.

RT-EST-010 gera e mostra. Aqui o preço muda, ou não muda, e se sabe por quê.

## 2. As decisões — do dono, em 20/09/2026

| Pergunta | Decisão |
|---|---|
| Ao aprovar | **Pode ajustar o número antes de aplicar** — o chão de margem continua valendo |
| Ao recusar | **Motivo escrito**, e o produto não volta a pedir a mesma coisa por 90 dias |
| Quem decide | **Quem define preço** — `produto:price:write`, a mesma da tela de produto |
| Explicação da IA | **Fica para depois** — o racional da conta já responde "por quê" |

## 3. O chão vale também para o humano

O campo vem preenchido com o preço sugerido e **pode ser mudado**. O que não pode é ficar abaixo de
`custo médio × (1 + margem mínima)`: a aprovação recusa, e a mensagem traz o mínimo.

Por que o guardrail vale aqui, se quem decide é o dono: a tela existe justamente para ele decidir com
o número à vista, e aprovar R$ 29,90 num produto que custa R$ 31,20 seria o sistema ajudando a vender
no prejuízo — com a aparência de quem conferiu. Quem quiser mesmo assim tem a tela de produto, que
sempre aceitou qualquer preço. **O caminho de fora existe, e é de propósito: ele deixa rastro em outro
lugar, e não passa por uma tela que diz "sugerido".**

O chão usa o **custo médio congelado na sugestão** — o que está na tela — e não o de agora. Aprovar
deve fazer o que a tela prometeu; se o custo andou desde a geração, a rodada seguinte diz.

## 4. Recusar é decisão, e cala por 90 dias

Recusar pede **motivo escrito**, e some da lista. O produto volta a aparecer quando:

- passarem **90 dias**; ou
- a sugestão for **outra** — motivo diferente, ou preço sugerido diferente.

Sem esse silêncio a mesma linha volta em toda rodada e a tela vira ruído, que é como uma lista de
alertas morre. Com ele, o custo subir de novo — que muda o preço sugerido — **fura o silêncio**, porque
aí é um fato novo, não a mesma conversa.

## 5. A trilha

Toda decisão guarda: o produto, o motivo da sugestão, o custo médio e o preço da época, o preço
sugerido, o **preço aplicado** (que pode diferir do sugerido), o racional que a conta escreveu, a
justificativa da recusa, quem decidiu e quando.

Os números ficam **congelados**. Recalcular depois daria outro resultado — o custo já é outro —, e a
pergunta que a trilha responde é "com que informação esta decisão foi tomada", não "o que seria certo
hoje".

## 6. Aplicar é mudar o preço, e passa por onde sempre passou

A aprovação chama o mesmo caso de uso da tela de produto (`DefinirPrecoDeVendaUseCase`). Um segundo
caminho para escrever preço seria um segundo lugar para esquecer a permissão, a auditoria e a validação.

## 7. As regras

| Regra | O quê |
|---|---|
| RN-EST-034 | Nenhum preço muda sem decisão humana; aprovar aceita ajuste, nunca abaixo do chão de margem |
| RN-EST-035 | Recusar pede motivo e cala o produto por 90 dias; sugestão diferente fura o silêncio |
| RN-EST-036 | Toda decisão fica registrada com os números congelados da sugestão |

## 8. Contrato de API

**Aprovar** — `preco` opcional; sem ele, aplica o sugerido.

```http
POST /api/v1/sugestoes-de-preco/{id}/aprovacao
```

```json
{ "preco": 49.90 }
```

**Recusar**

```http
POST /api/v1/sugestoes-de-preco/{id}/recusa
```

```json
{ "motivo": "cliente fiel leva esse produto toda semana; não quero mexer agora" }
```

**A trilha** — do mais recente para trás, por cursor.

```http
GET /api/v1/decisoes-de-preco?cursor=…&limite=50
```

```json
{ "itens": [ { "id": "…", "produto": "Máscara 500g", "decisao": "APLICADA",
               "motivo": "MARGEM_ABAIXO_DO_MINIMO",
               "custoMedio": 31.20, "precoAnterior": 38.00, "precoSugerido": 49.90,
               "precoAplicado": 44.90, "racional": "Custo médio R$ 31,20; preço R$ 38,00 dá 21,8%…",
               "justificativa": null, "quemDecidiu": "Wesley",
               "decididaEm": "2026-09-20T14:30:00Z" } ],
  "proximoCursor": null, "temMais": false }
```

**Erros**

| Status | Código | Quando |
|---|---|---|
| 403 | `ER-INF-ACESSO_NEGADO` | sem `produto:price:write` (decidir) ou `estoque:read` (ler) |
| 404 | `ER-EST-SUGESTAO_NAO_ENCONTRADA` | sugestão que não existe, já decidida, ou de outro salão |
| 422 | `ER-EST-PRECO_ABAIXO_DO_MINIMO` | preço informado abaixo do chão de margem |
| 422 | `ER-EST-DADOS_INVALIDOS` | motivo vazio, preço zero ou negativo |

## 9. Dados — `V63`

`decisao_de_preco` — uma linha por decisão, com os números congelados. Append-only: `revoke update,
delete`. A sugestão decidida sai de `sugestao_de_preco` na mesma transação.

Índice por `(estabelecimento_id, produto_id, decidida_em desc)`: é a consulta do silêncio de 90 dias,
feita a cada geração para cada produto.

## 10. A tela

Primeira tela do estoque, em **Preços** (`/precos`), no grupo Gestão — gated por
`produto:price:write`, que é quem pode fazer algo com ela.

Três blocos: a régua do salão (a margem mínima), as sugestões abertas, e a trilha do que já foi
decidido. Cada sugestão mostra o motivo, a conta que a gerou e os dois preços lado a lado, com as duas
margens — é o número que o dono precisa para discordar.

## 11. Observabilidade

- `Preço do produto X: sugerido R$ A, aplicado R$ B (motivo)` ao aprovar; `warn` ao recusar, com o
  motivo escrito.
- Auditoria em `SUGESTAO_DE_PRECO_APLICADA` e `SUGESTAO_DE_PRECO_RECUSADA`.

## 12. Testes

**`DecisaoDePrecoIT`** (17), com Postgres de verdade — aprovar o sugerido; aprovar com ajuste; o chão
valendo para quem digita, com o mínimo na mensagem; exatamente no chão passando; preço zero ou
negativo; recusar com motivo; recusa sem motivo; a recusa calando o produto; o custo que sobe furando
o silêncio; o silêncio acabando em 90 dias; aprovar **não** calando; o preço revertido depois de
aprovado voltando a aparecer; decidir duas vezes; isolamento entre salões; a permissão de precificar;
a decisão imutável; e a trilha congelando os números e o racional da época.

**Front** — `Precos.test.tsx` (9): os dois preços com as duas margens; produto sem preço mostrando
"sem preço" e não zero; a busca travada sem margem mínima, dizendo por quê; aprovar vindo preenchido e
aplicando o que estiver no campo; recusar exigindo motivo e avisando do silêncio de 90 dias; a recusa
do servidor aparecendo sem fechar o painel; a margem indo com a vírgula convertida; e a trilha
mostrando o ajuste e o motivo da recusa.

## 13. O que a prévia pegou

- **Dois botões "Recusar" no mesmo cartão.** O que abre o painel e o que confirma tinham o mesmo nome —
  o teste não conseguia distinguir, e quem usa teclado também não. O que confirma virou **"Confirmar
  recusa"**, que além de resolver a ambiguidade diz o que faz.
- **"49,9" no campo de preço.** `String(49.9)` corta o centavo, e número de dinheiro pela metade se lê
  errado. Agora vem com as duas casas.

## 14. Mutações verificadas

Duas rodadas, 17 mutações, cada uma sozinha. **17 mortas** — duas só depois de a rodada mostrar o que
os testes não cobriam.

| # | Mutação | Morta por |
|---|---|---|
| K01 | aprovar ignora o preço informado | `DecisaoDePrecoIT.aprovar_com_ajuste` |
| K02 | aprovar não confere o chão | `DecisaoDePrecoIT.chao_vale_para_o_humano` |
| K03 | exatamente no chão é recusado | `DecisaoDePrecoIT.exatamente_no_chao` |
| K04 | preço zero ou negativo aceito | `DecisaoDePrecoIT.preco_invalido` |
| K05 | recusa sem motivo aceita | `DecisaoDePrecoIT.recusa_sem_motivo` |
| K06 | a recusa não é registrada | `DecisaoDePrecoIT.recusar` |
| K07 | decidir não tira a sugestão da lista | `DecisaoDePrecoIT.decidir_duas_vezes`, `.aprovar_o_sugerido` |
| K08 | aprovar não aplica o preço | `DecisaoDePrecoIT.aprovar_o_sugerido`, `.aprovar_com_ajuste` |
| K09 | o chão usa margem zero | `DecisaoDePrecoIT.chao_vale_para_o_humano` |
| K10 | a trilha guarda o sugerido no lugar do aplicado | `DecisaoDePrecoIT.aprovar_com_ajuste` |
| K11 | o silêncio não é aplicado | `DecisaoDePrecoIT.recusa_cala` |
| K12 | o silêncio ignora o preço sugerido | `DecisaoDePrecoIT.fato_novo_fura_o_silencio` |
| K13 | aprovar também cala o produto | `DecisaoDePrecoIT.preco_revertido_volta` |
| K14 | o silêncio não tem prazo | `DecisaoDePrecoIT.silencio_acaba_em_90_dias` |
| K15 | aprovar sem `@PreAuthorize` | `DecisaoDePrecoIT.sem_permissao_de_preco` |
| K16 | recusar sem `@PreAuthorize` | `DecisaoDePrecoIT.sem_permissao_de_preco` |
| K17 | a aplicação pode reescrever a decisão (sem `revoke`) | `DecisaoDePrecoIT.imutavel` |

**As duas que sobreviveram na primeira rodada:**

- **K13 — `aprovar_nao_cala` passava por acaso.** Depois de aprovar R$ 44,90, a sugestão seguinte é
  "preço quebrado" — outro motivo —, e o silêncio nem se aplicaria mesmo que aprovar calasse. O caso
  que o filtro protege de verdade é outro: **alguém reverte o preço na tela de produto depois da
  aprovação**. Aí a sugestão volta idêntica — mesmo motivo, mesmo preço —, e se aprovar calasse, o
  produto voltaria a vender no prejuízo sem ninguém ver. `preco_revertido_volta` prova.
- **K14 — nenhum teste via o silêncio acabar.** `fato_novo_fura_o_silencio` prova que sugestão
  diferente fura; faltava provar que a mesma volta depois de 90 dias. `silencio_acaba_em_90_dias`
  envelhece a recusa em 91 dias e confere.

## 15. Pendências

- [ ] **A explicação da IA.** A coluna existe e nasce nula. Falta extrair o teto de custo do modelo de
      dentro do processador de conversas (`ProcessadorDeInteracoes`) para reusá-lo — decisão do dono
      (20/09/2026): fica para rotina própria.
- [ ] **A rota `/estoque` continua "Em breve".** Esta é a primeira tela do módulo, e é só de preço. O
      resto do estoque (lotes, saldo, inventário, ficha técnica) segue sem tela.
- [ ] **Aprovar não oferece desfazer.** Voltar atrás é mudar o preço de novo na tela de produto, e a
      trilha registra só a primeira decisão.
- [ ] **O silêncio é por produto, não por pessoa.** Recusa do gerente cala para o dono também.
- [ ] **Sem filtro na trilha.** Lista tudo, do mais recente para trás.
