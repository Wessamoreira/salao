---
id: RT-CFG-001
titulo: "Tela de configuração do salão: DDD padrão, fuso, comissão"
modulo: iam
fase: 1
perfil: completo
status: implementado
depende_de: [RT-IAM-007, RT-INF-007, RT-INF-010]
permissoes: [config:manage]
eventos: []
regras: [RN-IAM-017, RN-IAM-018]
atualizado_em: 2026-08-30
---

# RT-CFG-001 — Configuração do salão

## 1. Objetivo

Deixar o dono do salão mudar o que era imutável desde o provisionamento — e, em particular, o
**DDD padrão**, cuja ausência trava o cadastro de cliente.

## 2. O buraco que ela fecha

`ddd_padrao` entrou em RT-CLI-001 e só podia ser definido no provisionamento. Um salão provisionado
sem ele **recusa todo telefone digitado sem DDD** — que é exatamente como quem atende anota o
número — e a única saída era mexer no banco.

Ficou registrado como "a pendência mais urgente" da rotina que o criou. Era.

## 3. Primeiro uso real da invalidação de cache — e o defeito que ele revelou

`EstabelecimentoCacheado` guarda a configuração por cinco minutos, e o javadoc dele dizia que a
invalidação "acontece em outra rotina". Esta é a rotina.

Ao ligar, o teste falhou: salvar o DDD e cadastrar um cliente em seguida **continuava recusando o
telefone**. O motivo é que `InvalidadorDeCache` só mandava `NOTIFY`, e o ouvinte recebe o aviso
pela própria conexão dedicada, de forma assíncrona — inclusive o aviso que a própria instância
acabou de mandar.

Parece detalhe e não é: significa o salão salvar o DDD e o cadastro seguinte ainda falhar. A pessoa
conclui que "não salvou" e tenta de novo.

**A correção ficou no invalidador, não aqui**: ele passou a limpar também o cache local, **ao
commitar**. Limpar antes abriria a pior janela possível — uma leitura concorrente repovoaria o
cache com o valor antigo, ainda não sobrescrito, e ele ficaria lá até o TTL.

As outras instâncias continuam dependendo do `NOTIFY`, e o TTL segue como rede de segurança.

## 4. Uma normalização que estava no lugar errado

`(11)` era recusado na edição e aceito no provisionamento.

A limpeza do DDD (`replaceAll("\D","")`) morava no **command** de provisionamento, enquanto a
validação morava no domínio. A edição constrói o objeto de domínio direto, e por isso só recebia a
validação — sem a limpeza.

Movida para `DadosDoEstabelecimento`, junto da validação que depende dela. Regra de domínio no
objeto de domínio.

O mesmo objeto perdeu o "Novo" do nome (`NovoEstabelecimento` → `DadosDoEstabelecimento`): ele
passou a servir criação e edição, e um nome que diz "novo" num objeto usado para alterar é a
espécie de mentira que faz alguém duplicar a validação em vez de reaproveitá-la.

## 5. Uma ambiguidade que só não quebrava por acidente

`ClienteConfig` declarava dois beans do tipo `ClienteApi` — `clientesJdbc` e `clienteApi` — sem
`@Primary`. Nunca falhou porque o Spring desempata pelo **nome do campo** quando há mais de um
candidato, e todos os testes existentes chamavam o campo de `clienteApi`.

O primeiro teste que usou outro nome quebrou. `IamConfig` já resolvia assim para
`EstabelecimentoApi`; `ClienteConfig` tinha esquecido.

## 6. Na tela: opções que parecem preferência e decidem pagamento

Trocar a base da comissão de bruto para líquido muda quanto **todo profissional recebe** no mês
seguinte. Um rótulo "Base de comissão: [Bruto|Líquido]" não avisa ninguém disso.

Por isso cada campo diz a consequência abaixo do controle, em português comum:

- *"Muda quanto cada profissional recebe. Vale para os atendimentos daqui em diante; fechamentos já
  feitos não são recalculados."*
- desconto: *"sai do salão — a comissão não muda"* / *"é dividido — a comissão cai junto"*, em vez
  de `descontoAfetaComissao: true|false`.

E o campo de DDD diz o que acontece se ficar vazio, que é o único jeito de alguém preenchê-lo antes
de descobrir o problema no balcão.

Três grupos — identidade, atendimento, dinheiro — na ordem em que o dono pensa. Um formulário
corrido de oito campos obrigaria a ler tudo para achar o que se veio mudar.

**Confirmação explícita ao salvar**: num formulário em que nada muda visualmente, salvar em
silêncio deixa a dúvida de se salvou — e a pessoa clica de novo.

## 7. A segunda tela mostrou o que era compartilhado

Os primitivos de formulário nasceram com prefixo `cli-`, dentro da tela de clientes. A configuração
precisou dos mesmos, e é aí que se descobre o que era comum — não antes: promover na primeira
suspeita produz uma biblioteca de coisas que ninguém usa.

Extraídos para `estilo/componentes.css` com nomes neutros.

## 8. Dois defeitos visuais que só o navegador pegou

**O texto de ajuda saía em CAIXA ALTA.** `.campo > span` acertava tanto o rótulo quanto a
explicação — um parágrafo inteiro gritando. É exatamente a armadilha de especificidade de seletor
que a skill de design avisa, e passou por todos os testes de comportamento.

**Rótulos, títulos de seção e ajuda mediam 4.34 e reprovavam AA**, porque o formulário ficava solto
sobre `--papel-fundo`. Mesma causa da lista de clientes, mesma correção: virou superfície. Está
virando padrão do produto — **texto secundário mora sobre papel**.

Depois: todo elemento em AA, mínimo 4.67.

## 9. Testes

- [x] `ConfiguracaoIT` — 9: **define o DDD e destrava o cadastro de cliente** · cache invalidado ·
      DDD normalizado (`(11)` → `11`) · recusa DDD com zero a mais · recusa offset fixo no fuso ·
      altera comissão e fuso · **a trilha guarda a regra de comissão antes e depois** · isolamento
      entre salões · sem `config:manage` é recusado
- [x] `Configuracao.test.tsx` — 7: **avisa que sem DDD o cadastro recusa telefone** · salva o DDD ·
      explica a consequência da comissão · descreve o desconto pelo efeito · confirma que salvou ·
      a confirmação some ao mexer · parte dos valores do servidor
- [x] Contraste medido: mínimo 4.67, todo elemento em AA

## 10. Um teste instável, corrigido em vez de reexecutado

`Ficha.test.tsx` falhou uma vez com a máquina carregada por um build concorrente: `findAllBy`
resolve com o **primeiro** elemento que aparece, e a asserção rodava antes da lista inteira existir.

Agora espera pela contagem esperada. Teste que falha sob carga ensina a rodar de novo em vez de
investigar — e é assim que uma falha real passa despercebida um dia.

## 11. Pendências

- [ ] **A moeda não é editável.** Está fixa em BRL na tela, e o backend aceita trocar. Um salão
      fora do Brasil precisaria dela — e hoje o resto do sistema (DDD, CPF/CNPJ, fusos oferecidos)
      assume Brasil de qualquer forma. Rever se e quando isso deixar de ser verdade
- [ ] **O CNPJ não é lido de volta.** O `GET` não devolve `documento`, então o campo abre vazio e
      salvar em branco o apaga. É um defeito pequeno e real: falta expor o campo em
      `ConfiguracaoDoEstabelecimento`
- [ ] **Trocar o fuso não avisa sobre agendamentos existentes.** Vai deslocar como eles aparecem, e
      a tela só diz "muda também como os horários já marcados aparecem". Quando a agenda existir,
      isso precisa de confirmação que mostre o efeito
- [ ] **A regra de comissão vigente não é gravada em cada fechamento** — RN-IAM-017 registra a
      obrigação para a Fase 2. Enquanto isso, a trilha de auditoria é a única resposta para "qual
      base valia em março?"
