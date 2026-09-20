---
id: RT-EST-010
titulo: "Sugestão de preço/promoção com guardrail de margem mínima"
modulo: estoque
fase: 6
perfil: completo
status: implementado
depende_de: [RT-EST-003, RT-EST-001]
permissoes: [estoque:read, config:manage]
eventos: []
regras: [RN-EST-030, RN-EST-031, RN-EST-032, RN-EST-033]
atualizado_em: 2026-09-20
---

# RT-EST-010 — Sugestão de preço, com chão de margem

## 1. Objetivo

Responder **"que preço está errado hoje, e por quê"** — sem nunca sugerir vender perto do custo.

A Fase 6 fecha quando "nenhuma sugestão é aplicada sem clique humano". Esta rotina **gera e mostra**;
aprovar e aplicar é RT-EST-011. Nada aqui muda um preço.

## 2. As decisões — do dono, em 20/09/2026

| Pergunta | Decisão |
|---|---|
| Sugerir para o quê | **Só produto** — é quem tem custo médio, e portanto margem calculável |
| Quem calcula o preço | **A regra do sistema.** A IA só escreve a explicação |
| Margem mínima | **Uma só, do salão** |
| Quando sugerir | Margem abaixo do mínimo · encalhado · margem muito acima · preço quebrado |
| Encalhado | **60 dias sem vender, com 3 ou mais em estoque** |
| Margem muito acima | **Acima do dobro da mínima** |
| Régua de preço | **Escada de 9,90**: 9,90 · 19,90 · 29,90 · 39,90… |

## 3. Serviço ficou de fora, e é decisão

O título do plano diz "preço/promoção". Serviço tem preço com vigência (RT-CAT-002), mas **não tem
custo médio**: o custo dele sairia da ficha técnica mais a comissão, e a comissão muda por
profissional. O guardrail do plano — "nunca abaixo do custo médio × (1 + margem mínima)" — é uma frase
sobre produto. Esticá-la para serviço exigiria redefinir o que é custo antes de escrever qualquer conta.

## 4. A regra calcula; a IA escreve

O preço sugerido sai de uma conta **auditável**: custo médio, margem mínima, saldo, dias sem venda.
Qualquer pessoa refaz no papel e chega ao mesmo número.

A IA entra depois, e só para redigir a frase que explica a sugestão a quem vai decidir. É a aplicação
direta da regra do projeto: o modelo nunca escreve no domínio, e um preço é domínio.

**A redação da IA fica para RT-EST-011**, e a coluna `explicacao` já nasce aqui esperando por ela. Três
razões: RT-EST-011 é literalmente "registro do racional da sugestão", e é lá que o texto é lido por
quem decide; o teto de custo do modelo hoje é montado dentro do processador de conversas
(`ProcessadorDeInteracoes`), e extraí-lo para ser reusado mexe no controle de gasto do agente — cirurgia
que merece o próprio passo; e a decisão do dono era sobre **quem calcula o preço**, que já está
honrada: o número sai da conta, e sairia igual com ou sem modelo.

Até lá, o racional que a tela mostra é o que a própria conta escreveu — e ele responde "por quê"
sozinho.

## 5. O chão, que é o ponto da rotina

**Nenhuma sugestão fica abaixo de `custo médio × (1 + margem mínima)`.** É invariante do domínio, não
uma checagem que alguém lembrou de fazer: o motor não sabe produzir um número abaixo dele.

Produto **sem custo médio** — nada em estoque — não recebe sugestão. Sem custo não há margem, e um
chão sobre custo desconhecido seria um chão inventado.

## 6. Os quatro motivos, e só um por produto

Na ordem. O primeiro que se aplica é o que a tela mostra — dois motivos no mesmo produto viram duas
frases concorrentes sobre o mesmo preço.

| # | Motivo | Quando | O que sugere |
|---|---|---|---|
| 1 | `MARGEM_ABAIXO_DO_MINIMO` | a margem de hoje está abaixo do chão, ou o produto não tem preço | subir até o chão, na régua |
| 2 | `ENCALHADO` | 60 dias sem vender e 3+ em estoque | baixar até o chão, na régua — e só se isso for menos que o preço de hoje |
| 3 | `MARGEM_ALTA` | margem acima do dobro da mínima | baixar até o dobro da mínima, na régua |
| 4 | `PRECO_QUEBRADO` | o preço não está na régua, e a margem está sadia | arredondar, na régua |

**Encalhado só desce.** Se o produto está parado mas já é vendido no chão, não há promoção a fazer sem
furar a margem — e a rotina não sugere o que não pode.

## 7. A régua de 9,90

Todo preço sugerido termina em 9,90: 9,90 · 19,90 · 29,90 · 39,90… Arredonda **sempre para cima**, e é
isso que faz a régua nunca brigar com o chão de margem.

O efeito conhecido: em produto barato a margem sugerida sobe bem acima da mínima — um custo de R$ 4,00
com 30% dá R$ 5,20, e a régua sugere R$ 9,90. É escolha do dono, e a tela mostra a margem resultante
para a decisão ser tomada com o número à vista.

## 8. Quando roda

**No botão.** O dono pede as sugestões; a tela guarda as da última geração, com a data. Não há job
noturno, e não é só economia: sugestão gerada de madrugada é lida como fato consumado, e esta rotina
existe para provocar uma decisão. Quando a redação da IA entrar (RT-EST-011), o botão também é o que
mantém a chamada do modelo com alguém esperando o resultado.

Gerar de novo **substitui**: uma sugestão aberta por produto. O que deixou de se aplicar some — um
produto que voltou a vender não continua pedindo promoção.

## 9. As regras

| Regra | O quê |
|---|---|
| RN-EST-030 | Nenhuma sugestão abaixo do custo médio × (1 + margem mínima); sem custo médio não há sugestão |
| RN-EST-031 | Um motivo por produto, na ordem; encalhado só sugere para baixo |
| RN-EST-032 | Todo preço sugerido está na régua de 9,90, arredondado para cima |
| RN-EST-033 | A conta é do sistema e fica registrada com a sugestão; explicação de IA é opcional e nunca altera o número |

## 10. Contrato de API

**A política de preço**

```http
GET  /api/v1/politica-de-preco
PUT  /api/v1/politica-de-preco
```

```json
{ "margemMinima": 30.0 }
```

**Gerar e ler as sugestões**

```http
POST /api/v1/sugestoes-de-preco
GET  /api/v1/sugestoes-de-preco
```

```json
{ "geradaEm": "2026-09-20T14:02:00Z", "margemMinima": 30.0,
  "itens": [ { "id": "…", "produtoId": "…", "produto": "Máscara 500g",
               "motivo": "MARGEM_ABAIXO_DO_MINIMO",
               "precoAtual": 38.00, "custoMedio": 31.20, "margemAtual": 21.79,
               "precoSugerido": 49.90, "margemSugerida": 59.94,
               "racional": "Custo médio R$ 31,20; preço R$ 38,00 dá 21,8% de margem, abaixo dos 30% mínimos.",
               "explicacao": null } ] }
```

**Erros**

| Status | Código | Quando |
|---|---|---|
| 403 | `ER-INF-ACESSO_NEGADO` | sem `estoque:read` (ler) ou `config:manage` (política) |
| 422 | `ER-EST-DADOS_INVALIDOS` | margem mínima fora de 0–1000% |
| 409 | `ER-EST-SEM_POLITICA` | gerar sugestões antes de definir a margem mínima |

## 11. Dados — `V62`

- `politica_de_preco` — uma linha por salão: a margem mínima, quem mexeu e quando.
- `sugestao_de_preco` — a sugestão aberta de cada produto, com os números que a geraram, o racional
  por extenso e a explicação da IA quando houve. `unique (estabelecimento_id, produto_id)`.

A política mora no **estoque**, e não na configuração do salão (`iam`): margem mínima sobre custo médio
é vocabulário de estoque, e é o estoque quem a aplica. É a mesma escolha da ficha técnica (RT-EST-007).

## 12. Observabilidade

- `Sugestões de preço: N geradas de N produtos avaliados` a cada geração.
- Auditoria em `POLITICA_DE_PRECO_DEFINIDA` — mexer na margem mínima muda toda sugestão seguinte.

## 13. Sem tela, como o resto do estoque

O módulo inteiro é de servidor: as nove rotinas anteriores entregaram caso de uso, API e teste, e a rota
`/estoque` do front ainda é "Em breve". Esta segue o mesmo padrão, e por uma razão a mais: **a tela de
sugestões é a tela de aprovação**, e aprovar é RT-EST-011. Desenhá-la aqui para refazê-la lá seria
trabalho dobrado — e uma tela que mostra sugestões sem nada a fazer com elas convida o dono a mudar
preço na unha, que é exatamente o que a rotina existe para evitar.

O contrato de API já está fechado e provado por `SugestaoDePrecoIT`, então a tela de RT-EST-011 encontra
o servidor pronto.

## 14. Testes

**`ReguaDePrecoTest`** (5) — sobe até o próximo degrau; quem já está na régua fica; o que é preço
quebrado; e a varredura que prova que arredondar **nunca baixa** o preço, que é o que impede a régua de
furar a margem.

**`MotorDePrecoTest`** (13) — os quatro motivos, um a um; produto sem preço; sem custo médio não sugere;
encalhado que só desce; saldo baixo não encalha; produto que nunca se moveu; um motivo por produto; e
`nunca_abaixo_do_chao`, que varre custo × preço × dias × saldo × margem e confere o chão e a régua em
cada combinação.

**`SugestaoDePrecoIT`** (14), com Postgres de verdade — sem margem a rotina recusa; margem fora da régua;
definir e redefinir; o custo médio dos lotes chegando ao motor; produto sem estoque; os dias sem venda
saindo dos movimentos; a venda zerando o relógio do encalhe; o chão valendo para todos; gerar de novo
substituindo; gerar duas vezes; mudar a margem mudando a sugestão; isolamento entre salões; as duas
permissões; e a sugestão sendo imutável.

## 15. Pendências

- [ ] **A explicação da IA** — RT-EST-011. A coluna existe e nasce nula; falta extrair o teto de custo
      do modelo de dentro do processador de conversas para poder reusá-lo.
- [ ] **A tela** — RT-EST-011, junto com a aprovação.
- [ ] **Serviço fica de fora.** Precisaria de uma definição de custo de serviço (ficha técnica +
      comissão) antes de qualquer conta.
- [ ] **A régua de 9,90 é fixa.** Salão que trabalha com preço cheio (R$ 48,00) não tem como dizer isso.
- [ ] **"Encalhado" não olha sazonalidade.** Protetor solar parado em julho é normal, e a rotina vai
      sugerir promoção assim mesmo.
- [ ] **Sem histórico de sugestão recusada.** Gerar de novo substitui, então um produto cuja sugestão o
      dono recusou volta a aparecer na rodada seguinte. Guardar a recusa é de RT-EST-011.

## 16. Mutações verificadas

Uma rodada, 18 mutações, cada uma sozinha. **18 mortas**, todas pelo teste esperado.

| # | Mutação | Morta por |
|---|---|---|
| J01 | a régua arredonda para baixo | `ReguaDePrecoTest.nunca_baixa`, `.sobe` |
| J02 | degrau de cinco em cinco | `ReguaDePrecoTest.sobe` |
| J03 | régua sem o centavo: termina em zero | `ReguaDePrecoTest.ja_na_regua` |
| J04 | produto sem custo médio recebe sugestão | `MotorDePrecoTest.sem_custo_medio` |
| J05 | produto sem preço não é margem abaixo do mínimo | `MotorDePrecoTest.sem_preco` |
| J06 | encalhado ignora o saldo | `MotorDePrecoTest.encalhado_precisa_de_saldo` |
| J07 | encalha só depois de 120 dias | `MotorDePrecoTest.encalhado`, `SugestaoDePrecoIT.encalhado_pelos_movimentos` |
| J08 | encalhado sugere mesmo sem baixar o preço | `MotorDePrecoTest.encalhado_ja_no_chao` |
| J09 | margem alta é qualquer coisa acima da mínima | `MotorDePrecoTest.nada_a_sugerir` |
| J10 | margem calculada sobre o preço, e não sobre o custo | `MotorDePrecoTest.margem_abaixo_do_minimo` |
| J11 | preço fora da régua não é detectado | `MotorDePrecoTest.preco_quebrado` |
| J12 | o chão não passa pela régua | `MotorDePrecoTest.margem_abaixo_do_minimo`, `.nunca_abaixo_do_chao` |
| J13 | gerar de novo não substitui | `SugestaoDePrecoIT.gerar_de_novo_substitui`, `.gerar_duas_vezes` |
| J14 | sem política, assume margem zero | `SugestaoDePrecoIT.sem_politica` |
| J15 | os dias sem venda olham qualquer movimento | `SugestaoDePrecoIT.venda_recente_nao_encalha` |
| J16 | margem mínima sem teto | `SugestaoDePrecoIT.margem_invalida` |
| J17 | definir a régua sem `@PreAuthorize` | `SugestaoDePrecoIT.sem_permissao` |
| J18 | a aplicação pode reescrever a sugestão (sem `revoke`) | `SugestaoDePrecoIT.imutavel` |

O driver desta rodada confere a contagem de testes executados contra o filtro e marca
`(sem teste!)` quando não bate — a correção do defeito de método encontrado em RT-FIN-012, em que
`A#m+B#n` rodava um teste só, em silêncio.
