# Regras de negócio — módulo `financeiro`

---

### RN-FIN-001 — Uma comanda, vários pagamentos

**Enunciado.** O pagamento é 1:N com a comanda. Não existe "forma de pagamento da comanda".

**Motivo.** Metade no cartão e metade no pix é o caso comum do balcão. Um campo único obrigaria a
escolher uma forma, e o salão registraria a maior — perdendo a taxa da maquininha da outra metade,
que é justamente o número que o dono quer ver no fim do mês.

E a conciliação (ADR-0007) casa **pagamento a pagamento**: o extrato do adquirente traz uma linha
por transação.

**Onde é garantida.** Tabela `pagamento` com `comanda_id` sem unicidade · `PagamentoApi.daOrigem`.
**Rotinas.** RT-FIN-001 · **Teste.** `RegistrarPagamentoIT.split`,
`ComandasWebIT.split_de_pagamento`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-002 — O financeiro não conhece a comanda; quem sabe quanto falta é quem a possui

**Enunciado.** `comandaId` é um id de origem opaco para o módulo `financeiro`. A regra "o pagamento
não pode passar do que falta" vive no `atendimento`, que conhece o total.

**Motivo.** Fechar comanda é transacional em estoque, pagamento e comissão, e quem orquestra é o
`atendimento` — ele precisa perguntar ao financeiro quanto já foi pago. Se o financeiro dependesse
do atendimento para validar a comanda, o build reprovaria por ciclo de módulos.

É a mesma forma de `arquivo.dono_id` (RT-ARQ-001) e de `ClienteApi.podeReceber` (RT-NOT-001): o
módulo que possui a pergunta é quem a responde.

Consequência de modelagem: **não há FK** de `pagamento` para `comanda`, ao contrário do desenho em
`04-modelo-de-dados` — que é anterior à regra de fronteira estar aplicada de ponta a ponta, e cuja
FK também não separaria tenant (RN-CAT-011).

**Onde é garantida.** `PagamentoApi` sem nenhuma referência a comanda ·
`RegistrarPagamentoUseCase` (no atendimento) faz a conta do restante · ausência de FK em V36.
**Rotinas.** RT-FIN-001 · **Teste.** `ArquiteturaTest.modulos_respeitam_fronteiras` ·
`RegistrarPagamentoIT.nao_passa_do_total`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-003 — Troco não é pagamento; cortesia e fiado são

**Enunciado.** A soma dos pagamentos nunca passa do valor líquido da comanda
(`ER-ATD-PAGAMENTO_PASSA_DO_TOTAL`). `CORTESIA` e `FIADO` são formas de pagamento e quitam a
comanda.

**Motivo.** Quem entrega cem numa conta de oitenta pagou oitenta — registrar cem faria o
faturamento do dia crescer sozinho e o caixa nunca fecharia. O troco é da gaveta, não do sistema.

Do outro lado, cortesia e fiado não trazem dinheiro (a primeira, nunca), e mesmo assim precisam
quitar: tratá-las como "não pagamento" deixaria a comanda aberta para sempre, e o salão perderia a
única linha que explica por que aquele atendimento não virou dinheiro.

A recusa diz **quanto falta**, e não só "valor inválido": sem o número, a recepção tenta de novo às
cegas com a cliente esperando.

**Onde é garantida.** `RegistrarPagamentoUseCase` compara com o restante ·
`FormaDePagamento.traDinheiro` documenta a diferença sem excluir ninguém da soma.
**Rotinas.** RT-FIN-001 · **Teste.** `RegistrarPagamentoIT.nao_passa_do_total`,
`RegistrarPagamentoIT.segundo_pagamento_respeita_o_restante`,
`RegistrarPagamentoIT.cortesia_quita`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-004 — Só cartão entra na fila da conciliação, e só crédito parcela

**Enunciado.** Pagamento em `CREDITO` ou `DEBITO` nasce `PENDENTE` de conciliação; qualquer outra
forma nasce `NAO_APLICAVEL`. Parcelas acima de 1 só em `CREDITO`. `taxa_prevista` e
`previsao_recebimento` nascem **nulos**.

**Motivo.** Não existe extrato de adquirente para casar com dinheiro — deixá-lo `PENDENTE` encheria
a fila da conciliação com linhas que nunca sairiam de lá, e a tela da Fase 5 nasceria inútil.

Débito em três vezes não existe; aceitar o número faria a previsão de recebimento dividir um
dinheiro que cai de uma vez só.

E nulo não é zero: zero significaria taxa isenta, quando a verdade é que ninguém cadastrou o
contrato do adquirente ainda.

**Onde é garantida.** `Pagamentos.conciliacaoInicial` · `Pagamentos.parcelasValidas` ·
`FormaDePagamento.ehCartao`.
**Rotinas.** RT-FIN-001 · **Teste.** `RegistrarPagamentoIT.status_de_conciliacao`,
`RegistrarPagamentoIT.so_credito_parcela`, `RegistrarPagamentoIT.taxa_ainda_nao_existe`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-005 — Comissão nula é "usa a cascata"; zero é uma comissão de zero

**Enunciado.** `profissional.comissao_percentual` é anulável. Nulo significa que a ficha não decide
e a cascata continua; zero significa comissão de zero por cento, decidida.

**Motivo.** Enquanto a coluna era `not null default 0` e o cadastro convertia nulo em zero, todo
profissional tinha percentual — e o nível "padrão do salão" nunca alcançaria ninguém. A cascata
nasceria decorativa.

É a mesma distinção de `precoEm` (*"zero é um preço"*), de `taxa_prevista` (*"zero seria taxa
isenta"*) e de `custoMedio` (*"zero significaria unidades que não custaram nada"*). Aqui: zero é a
comissão de quem trabalha por salário fixo.

Linhas existentes não foram convertidas: um zero gravado pode ter sido deliberado, e reescrever
intenção alheia é pior do que deixar como está.

**Onde é garantida.** V39 relaxa a coluna · `CadastrarProfissionalUseCase.comissaoValida` não
coage mais · a auditoria registra `null`, não `0`.
**Rotinas.** RT-FIN-002 · **Teste.** `CascataDeComissaoIT.sem_regra`,
`CascataDeComissaoIT.zero_configurado`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-006 — A cascata: par, serviço, ficha, salão — nessa ordem

**Enunciado.** O percentual de um executante é resolvido, do mais específico para o mais geral:
regra do par profissional × serviço, regra do serviço, percentual da ficha do profissional, padrão
do salão. Nada configurado devolve zero com origem `SEM_REGRA`.

**Motivo.** O ponto discutível é o serviço vencer a ficha, e ele tem duas razões: sem isso o nível
seria decoração — toda profissional tem percentual na ficha, então uma regra por serviço só
alcançaria quem estivesse com o campo vazio; e a regra de serviço existe para proteger a margem de
um serviço caro, que é onde trabalham as pessoas com percentual alto. Se a ficha vencesse, a
proteção falharia exatamente para quem precisa alcançar.

A exceção tem o nível mais específico, que existe para isso.

`SEM_REGRA` é distinto de um zero configurado: sem a distinção, "por que não recebi comissão?"
teria a mesma resposta para a decisão do salão e para o cadastro esquecido.

**Onde é garantida.** `CascataDeComissao.resolver` · a ordem no `order by` de
`RegrasDeComissaoJdbc.candidatas`, conferida contra `EscopoDaRegra.peso` por teste.
**Rotinas.** RT-FIN-002 · **Teste.** `CascataDeComissaoIT.servico_vence_a_ficha`,
`CascataDeComissaoIT.par_vence_tudo`, `CascataDeComissaoIT.ficha_vence_o_padrao`,
`CascataDeComissaoIT.ordem_do_sql_bate_com_o_peso`
**Configurável?** As regras, sim · **Origem.** 2026-09-05

---

### RN-FIN-007 — Quem decide o percentual é o financeiro, e o snapshot sobrevive à regra

**Enunciado.** `atendimento` pergunta o percentual ao `ComissaoApi` e grava o par
(percentual, origem) no executante. Não lê a ficha do profissional. Alterar ou remover a regra
depois não muda nada já lançado.

**Motivo.** Ler a ficha do lado do atendimento reescreveria, fora da fronteira, a regra que só o
financeiro conhece — e a segunda cópia é a que continua pagando errado depois que a primeira mudar.
Pela mesma razão `OrigemDaComissao` mudou de módulo: o enum pertence a quem decide.

E o snapshot (RN-ATD-010) é o que faz *"por que a minha comissão foi 30%?"* continuar tendo resposta
depois que a regra deixou de existir.

**Onde é garantida.** `AdicionarItemDeServicoUseCase` chama `ComissaoApi.resolver` ·
`OrigemDaComissao` vive em `financeiro.api` · nada recalcula executante já gravado.
**Rotinas.** RT-FIN-002 · **Teste.** `CascataDeComissaoIT.item_usa_a_cascata`,
`CascataDeComissaoIT.snapshot_sobrevive_a_mudanca`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-008 — O razão é append-only, e isso é permissão do banco

**Enunciado.** `lancamento` recebe apenas `INSERT` e `SELECT` da role da aplicação. Correção é linha
nova; estorno é linha nova apontando para a original (`estorna_lancamento_id`). Saldo é sempre
`sum(valor)` filtrado, nunca coluna.

**Motivo.** *"Imutabilidade não é convenção, é permissão"* — o `update` não é recusado por
disciplina de quem escreve o código, é recusado pelo banco, para quem escrever o código errado
amanhã. Mesma decisão da auditoria (V3).

E sem coluna de saldo não existe "recalcular o razão", pelo mesmo motivo que não existe "recalcular
o estoque" (RN-EST-009).

**Onde é garantida.** V40: `revoke update, delete on lancamento from salao_app` ·
`LancamentosJdbc` não tem método de alteração.
**Rotinas.** RT-FIN-003 · **Teste.** `ApuracaoDeComissaoIT.update_em_lancamento_falha`,
`ApuracaoDeComissaoIT.delete_em_lancamento_falha`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-009 — Quem traz o que apurar é o atendimento; quem decide as linhas é o financeiro

**Enunciado.** O fechamento da comanda chama `RazaoApi.apurar` passando receitas e comissões já
calculadas. O financeiro decide tipo, sinal, contraparte e competência. A apuração acontece **na
transação do fechamento**.

**Motivo.** Se o financeiro buscasse os itens da comanda, dependeria de `atendimento` — que já
depende dele para pagamento e comissão. Seria ciclo, e o build reprovaria.

Enfeitar o evento seria a outra saída, e é pior: o payload de `event_publication` não tem RLS, e a
apuração ficaria assíncrona quando o plano a pediu transacional junto com estoque e pagamento.

O atendimento não sabe que comissão entra negativa nem a que mês a linha pertence — e não deveria.

**Onde é garantida.** `RazaoApi.apurar` recebe `ApuracaoDaComanda` ·
`FecharComandaUseCase.apuracaoDe` monta · `Razao` decide tipo, sinal e competência.
**Rotinas.** RT-FIN-003 · **Teste.** `ApuracaoDeComissaoIT.receita_e_comissao` ·
`ArquiteturaTest.modulos_respeitam_fronteiras`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-010 — A competência é o dia do salão, e desfazer é anular

**Enunciado.** `data_competencia` sai do relógio no fuso do estabelecimento. Reabrir e estornar
comanda escrevem linhas `ESTORNO` de sinal contrário para tudo que ainda não foi anulado —
operação idempotente.

**Motivo.** A comanda fechada às 23h50 de 31 de março num salão em Manaus pertence a março, e o
servidor em UTC já está em abril. Errar isso joga faturamento para o mês seguinte, e só aparece
quando o dono compara com o caderno.

E anular em vez de apagar não é escolha: a role não tem `delete` (RN-FIN-008). A idempotência vem do
próprio razão — quem já foi estornado tem uma linha apontando para si —, sem marca para manter em
dia. É a mesma forma da devolução de estoque (RN-ATD-022).

**Onde é garantida.** `Razao.apurar` usa `relogio.hojeEm(estabelecimentos.fusoDe(tenant))` ·
`Razao.estornarOrigem` · `LancamentosJdbc.pendentesDeEstorno`.
**Rotinas.** RT-FIN-003 · **Teste.** `ApuracaoDeComissaoIT.reabrir_anula`,
`ApuracaoDeComissaoIT.estorno_idempotente`, `ApuracaoDeComissaoIT.refechar_apura_de_novo`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-011 — O extrato inverte o sinal e mostra tudo, inclusive o estorno

**Enunciado.** O extrato de um profissional lista os lançamentos da conta dele no período, com o
**sinal invertido** (comissão positiva, estorno negativo), e soma o total a receber. Nenhuma linha é
escondida. O período é obrigatório.

**Motivo.** No razão a comissão é negativa porque sai do salão; na conta da pessoa ela entra. A
inversão acontece uma vez, no ponto de leitura, para ninguém precisar lembrar disso ao ler a tela.

Esconder estornos faria o total não bater com nada que a pessoa consiga conferir contra as próprias
comandas — e "recebi menos esse mês" é exatamente a conversa que este extrato existe para encerrar.
Um total sozinho obriga a acreditar.

Período obrigatório porque um padrão de "mês corrente" produziria duas pessoas olhando telas com
períodos diferentes, cada uma concluindo que a outra está errada.

**Onde é garantida.** `ConsultarExtratoUseCase` nega o valor de cada linha ·
`LancamentosJdbc.daContraparte` traz todos os tipos.
**Rotinas.** RT-FIN-004 · **Teste.** `ExtratoDoProfissionalIT.comissao_positiva`,
`ExtratoDoProfissionalIT.estorno_aparece`, `ExtratoDoProfissionalIT.reaberta_e_refechada`,
`ExtratoDoProfissionalIT.periodo_invertido`
**Configurável?** Não · **Origem.** 2026-09-05
