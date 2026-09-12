---
id: RT-FIN-006
titulo: Fechamento de período e repasse
modulo: financeiro
fase: 2
perfil: completo
status: implementado
depende_de: [RT-FIN-004, RT-FIN-005]
permissoes: [financeiro:close, financeiro:read:all]
eventos: []
regras: [RN-FIN-016, RN-FIN-017, RN-FIN-018, RN-FIN-019, RN-FIN-020]
atualizado_em: 2026-09-12
---

# RT-FIN-006 — Fechamento de período e repasse

## 1. Objetivo

Transformar o saldo do razão no dinheiro que sai da conta, com um documento imutável por pessoa
que explique o valor e sem possibilidade de pagar duas vezes.

## 2. Contexto de negócio

O fechamento é o momento em que o dono decide se os números do sistema são confiáveis (risco
R-06). A conta é acumulada, não apenas do período:

```text
a repassar = max(saldo acumulado até o fim do período, 0)
```

Quem adiantou mais do que ganhou termina o mês devendo, e a dívida precisa atravessar para o mês
seguinte. Os `REPASSE` anteriores entram na mesma soma e liquidam o que já foi pago. Uma linha
atrasada de um período já fechado é recolhida no fechamento seguinte, sem coluna ou lançamento
artificial de transporte de saldo.

## 3. Atores e permissões

| Ator | Permissão exigida | Escopo | Observação |
|---|---|---|---|
| Administrador / gerente | `financeiro:close` | Estabelecimento atual | Prévia e fechamento mostram salários e exigem MFA |
| Administrador / gerente | `financeiro:read:all` | Estabelecimento atual | Lista todos os contracheques |
| Profissional | `financeiro:read:own` | Próprio documento | Falha fechado até existir vínculo `usuario` → `profissional` |
| Assistente conversacional | Permissão do usuário efetivo | Igual ao usuário | Escrita futura somente por simulação e confirmação explícita |

## 4. Pré-condições

- [x] Estabelecimento resolvido pelo JWT e protegido por RLS
- [x] Período válido, já encerrado ou terminando no dia atual
- [x] Usuário com `financeiro:close` e MFA quando exigido pelo perfil
- [x] Pelo menos uma posição financeira no intervalo ou um saldo anterior a transportar
- [x] Prévia atual apresentada ao usuário

## 5. Fluxo principal

1. A tela consulta `GET /api/v1/fechamentos/previa?de=...&ate=...`.
2. O backend valida o período e calcula todas as posições com `ContasDoPeriodo`.
3. Devolve saldo anterior, movimento, saldo final, valor a repassar e uma `versao` SHA-256.
4. A tela descreve o efeito em números e pede confirmação explícita.
5. O `POST /api/v1/fechamentos` recebe as datas e `versaoDaPrevia`.
6. A transação toma advisory lock do estabelecimento, revalida o período e recalcula a versão.
7. Para cada posição, grava um contracheque congelado.
8. Quando `aRepassar > 0`, grava no razão um `REPASSE` com competência no fim do período.
9. Grava auditoria por documento e confirma tudo no mesmo commit.

## 6. Fluxos alternativos

**A1 — Profissional termina devendo.** O repasse recebe piso zero e a dívida permanece no razão.

**A2 — Lançamento atrasado.** Ele é absorvido pelo próximo fechamento por causa da soma acumulada.

**A3 — Números mudaram após a prévia.** O POST devolve 409, sem gravar, e a tela exige uma nova
conferência.

**A4 — Corrida entre períodos diferentes.** O advisory lock serializa o cálculo por salão. A
segunda transação vê o repasse da primeira e não paga o mesmo saldo novamente.

**A5 — Corrida sobre o mesmo período.** A constraint `EXCLUDE` do PostgreSQL é o árbitro final e
o conflito é traduzido para erro de domínio estável.

## 7. Regras de negócio aplicadas

| ID | Resumo | Garantida em |
|---|---|---|
| RN-FIN-016 | Saldo acumulado atravessa períodos; repasse tem piso zero | Aplicação / consulta do razão |
| RN-FIN-017 | Competência do repasse é o fim do período | Aplicação |
| RN-FIN-018 | Períodos não sobrepõem, não voltam no tempo e concorrência é serializada | Banco + aplicação |
| RN-FIN-019 | Fechamento exige prévia atual e não aceita período vazio | Aplicação + borda |
| RN-FIN-020 | Contracheque é congelado e imutável | Banco |

## 8. Contrato de API

**Prévia**

```http
GET /api/v1/fechamentos/previa?de=2026-08-01&ate=2026-08-31
Authorization: Bearer <token-com-mfa>
```

```json
{
  "de": "2026-08-01",
  "ate": "2026-08-31",
  "totalARepassar": { "valor": 3480.0000 },
  "quantosRecebem": 4,
  "versao": "2d9f5a8e7c1b0000000000000000000000000000000000000000000000000000",
  "linhas": [
    {
      "profissionalId": "7b21f4c1-1111-4444-8888-123456789abc",
      "profissionalNome": "Ana",
      "saldoAnterior": { "valor": -40.0000 },
      "movimentoDoPeriodo": { "valor": 1000.0000 },
      "saldoFinal": { "valor": 960.0000 },
      "aRepassar": { "valor": 960.0000 }
    }
  ]
}
```

**Confirmação**

```http
POST /api/v1/fechamentos
Authorization: Bearer <token-com-mfa>
Content-Type: application/json

{
  "de": "2026-08-01",
  "ate": "2026-08-31",
  "versaoDaPrevia": "2d9f5a8e7c1b0000000000000000000000000000000000000000000000000000"
}
```

**201 Created** devolve uma lista de contracheques com os mesmos números confirmados. A consulta
posterior é `GET /api/v1/fechamentos?de=...&ate=...&profissionalId=...`; o último parâmetro é
opcional para quem possui `financeiro:read:all`.

**Erros**

| HTTP | Código | Quando |
|---|---|---|
| 400 | erro de validação | Datas ausentes ou versão fora do SHA-256 esperado |
| 409 | `ER-FIN-PERIODO_JA_FECHADO` | Sobreposição, tentativa retroativa ou corrida perdida |
| 409 | `ER-FIN-PREVIA_DESATUALIZADA` | Valores mudaram desde a prévia |
| 422 | `ER-FIN-DADOS_INVALIDOS` | Período invertido ou no futuro |
| 422 | `ER-FIN-SEM_POSICOES_PARA_FECHAR` | Nada a documentar no período |
| 403 | resposta de autorização | Permissão ausente ou MFA pendente |

## 9. Dados

**Tabelas tocadas:** `fechamento` (insert/select), `lancamento_financeiro` (select/insert de
`REPASSE`) e `auditoria` (insert).

**Migration:** `V42__fechamento.sql` cria a tabela imutável, RLS, índice cronológico e a
constraint `fechamento_sem_sobreposicao` com `daterange`.

**Índices:** `(estabelecimento_id, periodo_fim desc, periodo_inicio desc)` e os índices existentes
do razão por estabelecimento, contraparte e competência.

**Orçamento de queries:** constante por fechamento: 1 advisory lock + validação do último período
+ leituras agregadas do razão + N inserts de documento + até N inserts de repasse + N auditorias.
As leituras não fazem uma consulta por profissional; os inserts crescem necessariamente com o
número de documentos produzidos.

## 10. Efeitos colaterais

| Efeito | Quando | Onde |
|---|---|---|
| Contracheque congelado | Mesmo commit | `fechamento` |
| Linha de repasse | Mesmo commit, apenas quando há valor positivo | `lancamento_financeiro` |
| Auditoria por profissional | Mesmo commit | `auditoria` |

Não publica evento nem envia comprovante nesta rotina.

## 11. Casos de borda e erros

| Situação | Comportamento | HTTP | Código |
|---|---|---|---|
| Saldo final negativo | Documento com repasse zero e dívida transportada | 201 | — |
| Profissional sem posição | Não produz documento | 201 | — |
| Salão inteiro sem posição | Não cria falso fechamento vazio | 422 | `ER-FIN-SEM_POSICOES_PARA_FECHAR` |
| Prévia ficou velha | Nenhuma escrita; exige atualização | 409 | `ER-FIN-PREVIA_DESATUALIZADA` |
| Fechar março depois de abril | Recusa para não pagar saldo novamente | 409 | `ER-FIN-PERIODO_JA_FECHADO` |
| Dois fechamentos concorrentes | Serializa por tenant; constraint arbitra sobreposição | 201/409 | conforme período |
| `read:own` com ID escolhido | Falha fechado e não revela salário alheio | 403 | autorização |

## 12. Concorrência e idempotência

- O advisory lock transacional usa uma chave derivada do UUID do estabelecimento e vem antes de
  toda conferência e cálculo.
- A `EXCLUDE USING gist` impede sobreposição por estabelecimento e profissional mesmo com várias
  instâncias do backend.
- `versaoDaPrevia` torna a confirmação otimista: o mesmo número apresentado precisa ser o número
  gravado.
- Repetir o POST depois do sucesso não duplica pagamento: o período já ocupado é recusado.

**A trava exige transação, e isso é explícito.** `pg_advisory_xact_lock` é liberado **no commit**.
Chamado fora de uma transação, o lock seria adquirido e devolvido no mesmo instante: o método
retornaria normalmente, sem proteger nada, e o cenário concorrente continuaria verde por acidente
de temporização. Daí o `@Transactional(propagation = MANDATORY)` em `travarEstabelecimento` — ele
transforma o engano em exceção na primeira chamada. Mesma família de lição de RT-EST-004 e
RT-INF-002: anotação de transação só vale onde o Spring consegue interceptar.

*(A chave é `hashtextextended` do uuid em texto. Colisão entre dois estabelecimentos é possível em
teoria e inofensiva na prática: o efeito máximo é um salão esperar o fechamento do outro por
alguns milissegundos, sem perda de correção.)*

**A ordem das posições é parte do contrato da versão.** `VersaoDaPrevia` faz SHA-256 da lista, e a
lista nasce de dois `group by` **sem** `order by` — onde o PostgreSQL não promete ordem alguma: com
`HashAggregate` ela depende do layout do hash, e o planejador troca de estratégia conforme
estatística e `work_mem`. Prévia e confirmação são consultas **separadas**; sem ordem estável, o
mesmo dado produz resumos diferentes e o fechamento recusa dizendo que os valores mudaram sem nada
ter mudado. O dono atualizaria a tela para receber a mesma recusa, sem conseguir pagar ninguém.

`ContasDoPeriodo.em` ordena por id antes de devolver, e a linha leva comentário dizendo que é
contrato — sozinha ela parece estética, e o próximo a passar por ali a apagaria.

**O guarda dela precisou ser unitário.** A primeira versão do teste era de integração e
**passou com a ordenação removida**: com quatro linhas, o banco devolveu numa ordem que por sorte
estava crescente. Um teste que só falha em 96% das execuções não é guarda, é ruído — o que ele
media era o humor do planejador. `ContasDoPeriodoTest` dubla o repositório para devolver as chaves
**ao contrário**; removida a ordenação, ele falha sempre, em 2 segundos e sem container.

## 13. Observabilidade

| O quê | Tipo | Nome/conteúdo | Alerta |
|---|---|---|---|
| Fechamento concluído | Log estruturado | período, total, quantidade de repasses e documentos | Falha repetida por tenant |
| Erros de domínio | Resposta padronizada + log global | código estável e `traceId` | Pico de conflitos/desatualizações |
| Auditoria | Registro imutável | profissional, período, saldos, valor e autor | Consulta operacional |

O log não contém nome, documento, telefone nem salário individual.

## 14. UX e front

- **Estados:** carregando com skeleton; vazio explicativo; prévia; confirmação; sucesso; erro por
  código.
- **Confirmação:** “Isso vai gerar R$ 3.480,00 de repasse para 4 profissionais”. Um “tem certeza?”
  sem números não atende R-UX-05.
- **Prévia desatualizada:** atualiza os valores, realça as linhas alteradas e exige novo clique.
- **Período vazio:** explica que não há comissão, vale ou saldo anterior; não mostra sucesso falso.
- **Responsividade:** tabela vira cartões de contracheque em telas estreitas; total e botão de
  confirmação permanecem visíveis sem rolagem horizontal.

## 15. Testes obrigatórios

- [x] `FechamentoDePeriodoIT` — 25 cenários: repasse zera a conta; vale desconta; competência no
  fim; pessoa sem posição não entra; dívida atravessa; saldo anterior aparece; prévia bate e não
  grava; prévia desatualizada; **versão estável em duas leituras**; período repetido, retroativo,
  futuro, invertido e vazio; permissões; isolamento de tenant; documento imutável; constraint de
  sobreposição; mês sintético centavo a centavo; `read:own` falha fechado; meses concorrentes não
  pagam o mesmo saldo duas vezes.
- [x] `ContasDoPeriodoTest` — 2 cenários unitários: **a ordem não depende do banco** (repositório
  dublado devolvendo as chaves ao contrário) e quem só tem saldo anterior entra na lista. É o
  guarda da linha de ordenação, e falha sempre sem ela — ver seção 12.
- [x] `FechamentosWebIT` — 3 cenários pela porta HTTP: prévia, confirmação e consulta preservam os
  números; confirmação sem a versão para na borda com 400; administrador sem segundo fator recebe
  403 na prévia, que mostra salário de todo mundo.
- [x] **O cenário concorrente passa** — reexecutado em 12/09/2026 no PostgreSQL 18, junto dos
  outros 24. A pendência anterior era da coleção do próprio teste, já corrigida.

## 16. Como testar manualmente

1. Entre como administrador com o segundo fator concluído.
2. Registre uma comissão de R$ 60 e um vale de R$ 40 para a profissional.
3. Abra a prévia do período: o repasse deve ser R$ 20.
4. Confirme usando a versão devolvida: deve criar o documento e zerar o saldo.
5. Repita a confirmação: deve responder conflito, sem duplicar lançamento.
6. Gere outra prévia, registre uma nova comissão e confirme a versão antiga: deve pedir revisão.
7. Simule duas confirmações concorrentes: nunca podem pagar o mesmo saldo duas vezes.

## 17. Decisões e trade-offs

| Decisão | Alternativa descartada | Por quê |
|---|---|---|
| Saldo acumulado até o fim | Conta limitada ao período | Não perde dívida nem lançamento atrasado |
| Piso zero no repasse | Repasse negativo | Dinheiro não volta automaticamente ao caixa |
| Competência no fim do período | Data da execução | O extrato fechado continua conciliável |
| Documento congelado | Recalcular ao consultar | Passado corrigido não altera o que foi pago |
| Hash da prévia | Confirmar somente datas | Garante que a pessoa aceitou aqueles valores |
| Advisory lock por tenant | Apenas `EXCLUDE` por período | Períodos diferentes também compartilham saldo acumulado |
| `read:own` falha fechado | Aceitar `profissionalId` do cliente | Evita IDOR até existir o vínculo real |

## 18. Pendências

- [ ] Estorno de fechamento e liberação do período pertencem à RT-FIN-007.
- [ ] `periodicidade_fechamento` ainda não sugere as datas na tela.
- [ ] Não há fechamento parcial por profissional.
- [ ] Criar o vínculo `usuario` → `profissional` para habilitar `financeiro:read:own` com segurança.
- [ ] Não há tela nem comprovante; a experiência será entregue na rotina de frontend financeiro.
- [ ] Registrar a forma pela qual o repasse saiu para permitir conciliação de caixa.
- [ ] Paginar listagens quando o volume real justificar.
- [ ] Adicionar teste HTTP de prévia, confirmação e consulta.
