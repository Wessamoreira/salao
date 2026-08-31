---
id: RT-CLI-004
titulo: "Consentimento LGPD: base legal, data e canal"
modulo: cliente
fase: 1
perfil: completo
status: implementado
depende_de: [RT-CLI-001, RT-CLI-003]
permissoes: [cliente:read, cliente:write]
eventos: []
regras: [RN-CLI-018, RN-CLI-019, RN-CLI-020, RN-CLI-021]
atualizado_em: 2026-08-30
---

# RT-CLI-004 — Consentimento

## 1. Objetivo

Saber, com prova, se o salão pode falar com cada cliente — e **para quê**.

## 2. A armadilha que esta rotina evita

O caminho óbvio seria uma caixa "aceito o tratamento dos meus dados" no cadastro. Isso é
juridicamente errado e operacionalmente pior.

Atender a cliente **não depende de consentimento**: depende de contrato (LGPD art. 7, V). Ela
marcou horário; guardar o nome e o telefone para prestar aquele serviço é execução do que foi
contratado. Pedir consentimento ali sugeriria que ela pode recusar e ainda ser atendida do mesmo
jeito, e criaria uma revogação que o salão **não teria como honrar** sem deixar de prestar o
serviço — uma promessa falsa registrada no banco.

Consentimento é necessário para o que ela **não** pediu: promoção, campanha, felicitação de
aniversário.

Daí a modelagem: **base legal por finalidade**, não por cliente.

## 3. A base legal não é coluna

Não existe `base_legal` na tabela. Ela é propriedade da *finalidade*: mandar lembrete de horário é
execução de contrato para toda cliente do salão, e campanha exige consentimento para toda cliente
do salão.

Guardá-la por linha permitiria que duas linhas discordassem sobre um fato que é o mesmo para todo
mundo — e a linha errada seria descoberta num processo, que é o pior lugar possível para descobrir.

A base vive no enum `Finalidade`, um lugar só. O que varia por pessoa, e por isso está no banco, é
a **decisão dela**.

| Finalidade | Base legal | Padrão sem registro |
|---|---|---|
| `LEMBRETE_DE_AGENDAMENTO` | execução de contrato (art. 7, V) | **pode** |
| `MARKETING` | consentimento (art. 7, I) | **não pode** |
| `PESQUISA_DE_SATISFACAO` | consentimento (art. 7, I) | **não pode** |

`PESQUISA_DE_SATISFACAO` poderia se apoiar em legítimo interesse (art. 7, IX), e **não se apoia**,
de propósito: legítimo interesse exige um teste documentado de necessidade e balanceamento que este
projeto não fez, e invocá-lo sem fazê-lo é base legal de fachada.

## 4. Três estados, não um booleano

`SEM_REGISTRO` é um estado próprio, e não um `concedido = false` (RN-CLI-018). As duas coisas levam
a resultados diferentes conforme a base legal, e confundi-las faria o lembrete de horário parar de
sair para toda cliente que nunca foi perguntada — que são **todas**, no primeiro dia.

O padrão negativo do consentimento é o que impede que uma importação de base antiga vire disparo de
campanha para gente que nunca autorizou nada. Silêncio não é consentimento, e ficha importada
também não — daí o canal `IMPORTACAO` existir: uma decisão importada tem valor probatório menor que
uma coletada aqui, e apagar essa diferença faria o sistema afirmar com a mesma segurança duas
coisas bem diferentes.

## 5. Oposição ao que é execução de contrato

Mesmo onde não é preciso consentir, um "não" explícito é honrado (art. 18 §2, direito de oposição).

Na prática, *"não me manda mais mensagem"* precisa ser respeitado. Um sistema que não sabe
registrar isso empurra o salão para uma de duas saídas ruins: ignorar a cliente, ou anotar num
papel que ninguém vai consultar antes do próximo disparo.

## 6. Revogar é a mesma operação que consentir

A LGPD exige que revogar seja tão fácil quanto consentir (art. 8 §5).

A forma confiável de cumprir isso não é lembrar de facilitar o "não" — é **não ter dois caminhos**.
Mesma rota, mesmo caso de uso, mesma permissão; muda só o `concedido`. Um endpoint separado para
revogação seria, inevitavelmente, o mais estreito dos dois: menos usado, menos testado, e o
primeiro a quebrar sem ninguém perceber.

## 7. Append-only, por permissão

Cada decisão é uma linha nova. A pergunta que esta tabela responde não é "o que vale hoje", é
**"o que valia no dia em que a mensagem foi enviada?"** — e uma linha editável só sabe responder a
primeira, que é justamente a resposta inútil num questionamento.

A garantia é permissão de banco, como na auditoria: `salao_app` não tem `UPDATE` nem `DELETE` aqui,
e há teste que tenta os dois e exige `permission denied`.

## 8. Interação com a mesclagem, que saiu de graça

Se a pessoa revogou numa ficha e a outra ainda dizia "sim", mesclar **não pode** ressuscitar o
consentimento.

A consulta de decisão vigente aceita também as fichas absorvidas (`mesclado_para = :id`), então a
decisão mais recente vence, venha de qual ficha vier — que é a única leitura defensável. A
mesclagem não precisou saber que consentimento existe.

Funciona porque RT-CLI-003 mantém as cadeias com um salto só.

## 9. Uma pergunta só para o resto do sistema

`ClienteApi.podeReceber(clienteId, finalidade)` é o que o módulo `notificacao` vai chamar antes de
qualquer envio, e a única coisa que ele deve perguntar.

Quem conhece base legal, consentimento e revogação é este módulo. Um `if` sobre um campo booleano
do outro lado seria a regra da LGPD escrita duas vezes — e a segunda cópia é a que continuaria
mandando promoção depois da revogação.

Falha fechada: cliente inexistente, de outro tenant ou anonimizado devolve `false`, inclusive para
o que seria permitido por padrão.

## 10. Testes

- [x] `ConsentimentoIT` — 10: **padrão depende da base legal** · lista inclui o que falta
      perguntar · consentir e revogar · **oposição ao lembrete é honrada** · finalidades
      independentes · **append-only por permissão** (`UPDATE`/`DELETE` recusados) · revogação
      sobrevive à mesclagem · isolamento entre tenants · sem `cliente:write` é recusado · enum e
      `check` do banco concordam
- [x] `ClientesWebIT` — 2 novos: conceder e revogar pela mesma rota · parâmetro de tipo errado

## 11. Dois defeitos encontrados, nenhum desta rotina

**Sequência inacessível para toda tabela `bigserial` futura.** V3 concedeu com
`on all sequences in schema public`, que vale só para as que existiam naquele instante. Toda tabela
nova nasceria com a sequência bloqueada para `salao_app`, e o sintoma seria `permission denied for
sequence` no primeiro `INSERT` — em execução, não na migração. Corrigido com
`alter default privileges`, que cobre também as próximas.

**500 em qualquer URL com parâmetro malformado.** `GET /clientes/nao-e-uuid` devolvia 500, e não é
específico do consentimento: as rotas com id só eram exercitadas com ids válidos, então ninguém
tinha visto. É o mesmo defeito que RT-INF-003 corrigiu para rota inexistente, num caso que passou
despercebido. Agora é 400 com o parâmetro apontado — e sem o valor recebido, que veio de fora e
atravessaria log e telemetria.

## 12. Pendências

- [ ] **Nada consulta `podeReceber` ainda**, porque o módulo `notificacao` não existe. A regra está
      pronta e sem uso — e um envio construído sem chamá-la passaria no build. Amarrar quando
      `notificacao` chegar
- [x] ~~Não há tela de consentimento~~ — entregue em RT-CLI-006, com efeito e registro
      separados (RN-CLI-028)
- [ ] **A pergunta não aparece no cadastro.** O momento natural de perguntar sobre marketing é o
      balcão, ao cadastrar — e hoje isso depende de alguém lembrar de chamar outra rota
- [ ] **Histórico completo não é exposto.** A API devolve só a decisão vigente; a trilha existe no
      banco e num questionamento seria consultada à mão. Decidir junto de RT-CLI-006
- [ ] Legítimo interesse para pesquisa de satisfação exigiria um LIA documentado. Enquanto não
      houver, `PESQUISA_DE_SATISFACAO` continua exigindo consentimento
