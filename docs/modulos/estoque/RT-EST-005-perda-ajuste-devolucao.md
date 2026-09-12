---
id: RT-EST-005
titulo: Perda, ajuste e devolução com motivo obrigatório
modulo: estoque
fase: 3
perfil: completo
status: implementado
depende_de: [RT-EST-003, RT-EST-004]
permissoes: [estoque:write]
eventos: []
regras: [RN-EST-014, RN-EST-015, RN-EST-016, RN-EST-017]
atualizado_em: 2026-09-12
---

# RT-EST-005 — Perda, ajuste e devolução

## 1. Objetivo

Dar ao estoque as três saídas que não são venda — e exigir que cada uma diga **por quê**.

## 2. Contexto de negócio

Até aqui o estoque só saía por venda ou consumo interno, ambos com origem (item de comanda,
serviço) que explica a saída sozinha. Faltavam as três que dependem de alguém digitar o motivo:
o frasco que quebrou, a contagem que não bate, e a caixa que volta ao fornecedor.

## 3. Atores e permissões

`estoque:write`, nos três. Quem opera o depósito registra perda e ajuste; não há permissão separada
porque ambos são a mesma classe de poder — mudar o saldo sem venda.

## 4. Três caminhos, e a diferença é quem sabe o quê

| Caminho | Entrada | Lote | Sinal |
|---|---|---|---|
| **Perda** | produto + quantidade | **FEFO** | negativo |
| **Ajuste** | lote + quantidade | obrigatório | os dois |
| **Devolução ao fornecedor** | lote + quantidade positiva | obrigatório | negativo |

"Sumiram três shampoos" não diz de qual lote — FEFO é a resposta certa quando ninguém sabe. Quem
conta tem a etiqueta na mão. E quem devolve está devolvendo uma caixa de uma nota específica.

O lote obrigatório no ajuste **positivo** é o que protege o custo médio: atribuir unidades achadas a
um lote escolhido por FEFO inventaria um custo (ver RN-EST-017).

## 5. O motivo está no banco

`check (tipo not in ('PERDA','AJUSTE','DEVOLUCAO_AO_FORNECEDOR') or length(btrim(observacao)) >= 3)`

É o título da rotina, e deixá-lo só no caso de uso significaria que o inventário (RT-EST-006) e a
ficha técnica (RT-EST-007) — que vão gravar ajuste por baixo — poderiam gravar sem explicação.

**Três caracteres, e não "não vazio".** Um campo obrigatório que aceita `"x"` é um campo que a
recepção preenche com `"x"`. Três não resolve o problema; resolve o contorno mais barato dele.

## 6. O que esta rotina consertou de trás

**O movimento de estoque não era imutável.** O glossário já o chamava de *"evento imutável"*, e a
role tinha `UPDATE` e `DELETE`. O razão, o vale e o fechamento todos impõem isso pelo banco, com a
mesma frase escrita três vezes — *"imutabilidade não é convenção, é permissão"*. O movimento de
estoque, que é **onde a ideia começou**, era o único que dependia de disciplina.

Importa mais justamente aqui: perda e ajuste são o que alguém vai querer "consertar" com um
`update`. Conferido que nada no código faz `update` nem `delete` nesta tabela antes de revogar.

**E `DEVOLUCAO` significava duas coisas opostas.** O comentário do enum dizia "voltou para o
fornecedor, ou a cliente devolveu" — sinais opostos, contrapartes diferentes, mesmo rótulo. Agora
são dois tipos (RN-EST-016). O antigo não foi renomeado: a tabela acabou de ficar append-only, e
reescrever histórico para deixar um nome simétrico é pior do que conviver com a assimetria.

## 7. Concorrência

`MovimentoComMotivo` repete o cuidado de RT-EST-004, porque a armadilha morde igual: trava os lotes
do produto **antes** de conferir, e relê o saldo **depois**, em comando separado. Com lock e leitura
no mesmo comando, em READ COMMITTED quem acorda do `for update` reavalia a linha travada mas a
subconsulta do saldo já foi resolvida com o snapshot antigo.

## 8. Contrato de API

Ainda sem rota HTTP — os três casos de uso existem e a tela do depósito é da rotina de frontend de
estoque. É a pendência mais visível desta rotina.

## 9. Testes obrigatórios

- [x] `PerdaAjusteDevolucaoIT` — 13 cenários: perda sai por FEFO · **perda sem motivo é recusada
  antes de tocar o estoque** · motivo de uma letra não passa · ajuste negativo · **ajuste positivo
  usa o custo do lote** · ajuste não deixa o lote negativo, e a recusa diz quanto há · ajuste de
  zero recusado · lote de outro salão não existe · devolução ao fornecedor tira do lote ·
  quantidade negativa recusada · sem `estoque:write` nenhum dos três · **o banco recusa perda sem
  motivo** · **`update` e `delete` em movimento são recusados por permissão**.
- [x] Os 83 testes de integração de estoque e fechamento de comanda continuam verdes.

## 10. Pendências

- [ ] **Não há rota nem tela.** Os três casos de uso existem e ninguém os alcança pelo HTTP.
- [ ] **Perda não aceita lote.** Quando alguém SABE o lote — o frasco vencido na mão, com o código
      na etiqueta — o caminho hoje é o ajuste, e o movimento sai como `AJUSTE` em vez de `PERDA`.
      O relatório de perdas não vê essa.
- [ ] **Não há catálogo de motivos.** Texto livre significa "quebrou", "quebrado", "caiu" e
      "acidente" para o mesmo fato, e nenhum relatório agrupa.
- [ ] **Devolução ao fornecedor não registra o fornecedor.** O lote sabe de que nota veio; o
      movimento não aponta para nada além do motivo em texto.
- [ ] **Perda não tem efeito no razão.** Estoque perdido é custo, e o livro-razão não sabe disso —
      cruzar os dois é do relatório de resultado.
- [ ] **Não há teto nem alçada.** Uma perda de mil unidades passa igual a uma de uma.
