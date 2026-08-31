---
id: RT-CLI-005
titulo: Anonimização a pedido do titular, preservando o histórico
modulo: cliente
fase: 1
perfil: completo
status: implementado
depende_de: [RT-CLI-004, RT-CLI-003]
permissoes: [cliente:anonymize]
eventos: []
regras: [RN-CLI-022, RN-CLI-023, RN-CLI-024, RN-CLI-025]
atualizado_em: 2026-08-30
---

# RT-CLI-005 — Anonimização

## 1. Objetivo

Atender ao pedido de exclusão do titular (LGPD art. 18, VI) sem quebrar o histórico financeiro que
a legislação fiscal exige guardar.

`05-seguranca` chama esta de "a operação mais delicada do sistema". É.

## 2. Anonimizar, não apagar

O histórico financeiro aponta para o cliente. Apagar a linha quebraria o rastro que sustenta o
extrato de comissão de quem trabalhou — e o direito do titular é sobre o **dado pessoal**, não
sobre o registro contábil do salão.

Então o dado pessoal some e a linha fica, com `anonimizado_em` preenchido. A coluna já existia
desde RT-CLI-001, esperando por esta rotina.

## 3. Uma honestidade sobre a palavra

O `id` permanece, porque é ele que o histórico referencia. Do ponto de vista estrito do art. 12,
isso é **pseudonimização**, não anonimização: a chave continua lá.

O que torna a operação defensável é que **todo dado que permitiria voltar à pessoa é destruído**.
Chamar de anônimo um registro que outra tabela sabe nomear seria mentir para quem confiou no
sistema — e foi essa exigência que revelou o defeito da seção 5.

## 4. Alcança as fichas absorvidas

Uma ficha mesclada (RT-CLI-003) continua guardando o nome e o telefone com que foi cadastrada, e é
a mesma pessoa.

Anonimizar só a sobrevivente deixaria o dado intacto na ficha ao lado — exatamente onde ninguém
pensaria em olhar. A operação alcança `id = :id or mesclado_para = :id`, e funciona porque as
cadeias são planas.

## 5. O defeito que esta rotina obrigou a enxergar

**A trilha de auditoria guardava o nome da cliente.**

RN-CLI-007, escrita em RT-CLI-001, dizia que telefone e e-mail não podiam ir para a auditoria
"para não criar uma segunda base de dados pessoais fora do alcance da anonimização" — e deixava o
nome passar. É a mesma falha, com o mesmo argumento contra, e eu não a apliquei até o fim.

A anonimização a torna impossível de ignorar: apagar o nome da tabela `cliente` deixaria o nome
recuperável em `auditoria` pelo `entidade_id`. E numa tabela que a aplicação **não pode alterar**,
por desenho.

Corrigido em duas frentes:

- o cadastro não manda mais dado pessoal nenhum para a trilha — `entidade_id` já diz de quem se
  trata, e a trilha responde "quem fez o quê";
- as linhas já gravadas são redigidas em V16. É uma escrita deliberada numa tabela append-only,
  feita pelo owner numa migração revisada — o único caminho aceitável, e por isso está escrita e
  não escondida num script.

`AnonimizacaoIT.trilha_nao_guarda_nome` impede a volta.

## 6. O campo livre do consentimento saiu

`cliente_consentimento.observacao` era texto digitado pela recepção, e texto livre recebe dado
pessoal mais cedo ou mais tarde — *"pediu para parar, mudou de número, avisar a irmã"*.

A tabela é append-only por permissão: a anonimização **não conseguiria limpá-lo**. E conceder
`UPDATE` para resolver desfaria a garantia que faz o registro valer como prova.

Restava escolher entre enfraquecer a prova e remover o campo. O campo saiu (V16): canal, data,
decisão e autor já respondem *"onde está a prova de que ela aceitou?"*. O texto livre acrescentava
pouco e criava PII num lugar de onde não havia como tirá-la.

## 7. Permissão própria, porque é irreversível

`cliente:anonymize` não está em `cliente:write`, e nem o `GERENTE` a tem — só o `ADMIN`.

A recepção cadastra e corrige o dia inteiro. Apagar o dado de alguém para sempre é outro tipo de
ato, e o pedido do titular chega a quem responde pelo salão, não a quem está atendendo o balcão.

## 8. Depois de anonimizada

Some da consulta, da busca e da detecção de duplicado. Não participa de mesclagem (RN-CLI-017, que
existia desde RT-CLI-003 esperando por este estado). E **não recebe mensagem nenhuma** — nem o que
seria permitido por padrão pela base legal, porque mandar lembrete para quem pediu para ser
esquecida seria o pedido atendido no banco e ignorado na prática.

Repetir a operação devolve 404, e não "pronto": dizer que deu certo sobre uma ficha que talvez seja
de outro salão contaria algo que não deveria.

## 9. Testes

- [x] `AnonimizacaoIT` — 9: **o dado some do disco**, inclusive da coluna gerada de busca · some
      das leituras · **alcança as fichas absorvidas** · não recebe mais mensagem · **a trilha não
      guarda nome** · anonimizada não mescla · repetir não é silencioso · isolamento entre salões ·
      `cliente:write` não basta
- [x] `ClientesWebIT` — 1 novo: a recepção recebe **403** ao tentar `DELETE`, e a ficha continua lá

## 10. Um terceiro defeito, encontrado no teste acima

**Toda negação de `@PreAuthorize` dentro de um caso de uso devolvia 500.**

Não era de cliente: valia para todos os endpoints do sistema. Passou despercebido porque a negação
que os testes exercitavam era a da cadeia de filtros (segundo fator, token ausente), que o Spring
Security responde antes de chegar ao controller. A do caso de uso acontece depois, já dentro do
`DispatcherServlet`, e caía no `@ExceptionHandler(Exception.class)`.

Não é cosmético: 500 afirma "algo quebrou aqui dentro" sobre o que é um desfecho normal de
autorização, e enche o log de erro interno — escondendo os 500 de verdade, que é exatamente o que
se quer enxergar. Agora é 403 com `ER-INF-ACESSO_NEGADO`, sem dizer qual permissão falta: isso
descreveria o modelo de autorização para quem está sondando.

É o terceiro defeito desta família em duas rotinas (rota inexistente, parâmetro malformado,
permissão negada). O padrão é claro — **o `@ExceptionHandler(Exception.class)` engole tudo que não
tem tratador próprio**, e cada caso só aparece quando alguém escreve o teste que passa por ele.

## 11. Pendências

- [x] ~~Não há como pedir a anonimização pela tela~~ — entregue em RT-CLI-006, discreta e com
      confirmação que explica o que sobra
- [ ] **A ficha técnica de química não é alcançada** — porque ainda não existe. Quando existir,
      esta rotina precisa apagá-la junto, e é dado de saúde: a omissão seria grave
- [ ] **Foto do cliente** idem, quando o módulo `arquivos` entrar
- [ ] **Não há registro do pedido em si** (quem pediu, quando, por qual canal). Hoje a auditoria
      registra a execução, não a solicitação — e é a solicitação que se apresenta à ANPD
- [ ] **Retenção automática não existe.** A LGPD pede eliminação ao fim da finalidade; hoje nada
      anonimiza sozinho uma ficha parada há anos. Depende de política de retenção acordada com o
      dono do salão
- [ ] **Um teste de fronteira que falta**: nenhum teste varre todas as colunas de todas as tabelas
      atrás de PII sobrevivente. Cada tabela nova é uma chance de repetir o erro da auditoria, e
      hoje só a revisão pega
