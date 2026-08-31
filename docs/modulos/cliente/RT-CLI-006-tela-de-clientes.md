---
id: RT-CLI-006
titulo: "Tela de clientes: busca, ficha e edição"
modulo: cliente
fase: 1
perfil: completo
status: implementado
depende_de: [RT-CLI-002, RT-CLI-005, RT-INF-010]
permissoes: [cliente:read, cliente:write, cliente:anonymize]
eventos: []
regras: [RN-CLI-026, RN-CLI-027, RN-CLI-028]
atualizado_em: 2026-08-30
---

# RT-CLI-006 — Tela de clientes

## 1. Objetivo

Dar à recepção o que as cinco rotinas anteriores construíram: achar quem está ligando, abrir a
ficha, registrar consentimento, juntar duplicados e atender ao pedido de exclusão.

Primeira tela de produto do projeto, e primeira aplicação da skill `frontend-design`.

## 2. O que a skill mandou fazer, e o que ela mandou não fazer

A skill diz para seguir exatamente a direção visual quando o brief já a fixa. Aqui ele fixa: o
design system é o padrão salvo do projeto. **Então o risco não foi para a paleta** — foi para a
interação, que é onde o problema desta tela mora.

O que ela mandou evitar também valeu: a resposta genérica para "tela de clientes" é cabeçalho,
campo de busca no canto, tabela com colunas, paginação e um botão "+ Novo" — o que qualquer
gerador de CRUD produz. Não é o que esta tela é.

## 3. A busca é a tela

O backend concorda, e isso não é metáfora: **não existe endpoint que devolva todos os clientes**
(RT-CLI-002 — sem `q`, lista vazia). Uma tabela de todo mundo é literalmente impossível de montar.

Então o estado vazio é o **padrão**, não a exceção. É a primeira coisa que a recepção vê toda vez,
e por isso a tela não se divide em duas colunas enquanto não há o que pôr na segunda — duas colunas
antes da hora espremem o convite num terço e deixam o resto em branco.

O campo ocupa o alto, em corpo grande, com o foco já nele. Quem abre esta tela vai digitar, sempre.

## 4. A assinatura: o campo diz o que entendeu

Ao digitar `4321`, a linha abaixo escreve **"TELEFONE · procurando pelos últimos dígitos do
telefone · 2 fichas"**. Ao digitar `maria`, **"NOME · procurando por nome e apelido"**.

É a regra RN-CLI-009 visível. Ensina em três segundos a única coisa que a recepção precisa saber —
que dígito significa telefone — sem manual e sem seletor. E dispensa perguntar "buscar por nome ou
por telefone?", que seria pedir uma decisão a quem está com o telefone na outra mão.

**O texto vem do servidor** (RN-CLI-026). Reimplementar a regra em TypeScript seria a mesma
armadilha que RN-CLI-011 recusou no banco: a segunda cópia divergiria e a tela passaria a dizer uma
coisa enquanto a consulta fazia outra. Para isso, `RT-CLI-002` ganhou `ResultadoDaBusca`, que
carrega a interpretação e o aviso — inclusive o texto "informe ao menos 2 letras", que também é do
domínio.

Nos resultados, os dígitos que casaram aparecem em **negrito** dentro do número: é neles que o olho
confere contra o identificador de chamadas, e destacá-los poupa reler o número inteiro. Peso, e não
cor — o sistema reserva cor para status.

## 5. Consentimento: três estados, e por isso não é um interruptor

RN-CLI-018 diz que "sem registro" não é "não pode". A tela precisa disso, senão mente: um
interruptor tem dois estados e mostraria "desligado" para uma cliente que o salão **pode e deve**
avisar sobre o horário dela.

Então a tela separa duas coisas que um interruptor funde:

- o **efeito** — "pode receber" / "não recebe", que é o que vale hoje;
- o **registro** — "Sem registro — faz parte do serviço contratado", "Aceitou em 12/03/2026".

"Aceitou" e "Recusou" têm exatamente o mesmo peso visual. A lei exige que revogar seja tão fácil
quanto consentir (art. 8 §5), e um botão menor seria a letra cumprida e o espírito não. Há teste
comparando as classes CSS dos dois.

**O canal fica acima das decisões, não abaixo.** Na primeira versão ficava embaixo, e era possível
registrar as três finalidades e só então reparar que o canal estava errado — num registro que não
se corrige, porque a tabela é append-only.

## 6. Um erro que a tela cometia contra o próprio domínio

A primeira versão oferecia **"juntar as duas fichas" para o caso mãe e filha**.

Todo o RT-CLI-003 foi construído para distinguir a mesma pessoa cadastrada duas vezes de duas
pessoas que dividem o telefone. A interface desfazia essa distinção e convidava a fundir duas
pessoas diferentes — o estrago exato que o domínio evita, oferecido em um clique.

Agora só `PROVAVEL` e `POSSIVEL` são acionáveis. `MESMO_TELEFONE` aparece como contexto — "este
telefone já está com: Ju" — sem botão e sem destaque de alerta (RN-CLI-027).

## 7. O que só apareceu ao abrir o navegador

Três defeitos passaram por 25 testes de comportamento e por uma revisão de código.

**O nome da ficha estava em Instrument Serif.** O sistema diz que a serifada aparece em exatamente
dois lugares — nome do estabelecimento e totais — e "em texto operacional, nunca". Ficou bonito;
era violação do mesmo jeito. Alargar a regra na primeira vez em que ela incomoda é como ela deixa
de restringir. Virou Instrument Sans, com presença vinda de corpo e peso.

**O telefone da lista estava em `--tinta-tenue`.** A regra do sistema é explícita: o tênue nunca em
"informação que o usuário precise ler para decidir algo" — e aquele número é literalmente o que a
recepção confere contra o identificador de chamadas. É o **mesmo erro** que a revisão do design
system pegou antes, em outro campo.

**A ação de apagar dados era vermelha**, contradizendo o próprio argumento de que ela deve sumir no
meio das outras até ser procurada.

## 8. Contraste: medido, e a medição corrigiu o design system

A doc do sistema trazia uma tabela de contraste **estimada** a partir da luminosidade, com a
ressalva de que a verificação real estava pendente. Medi no navegador.

A estimativa errava por um motivo específico: **só considerava `--papel` como fundo**.
`--tinta-fraca` dá 4.67 sobre papel e passa, mas 4.34 sobre `--papel-fundo` e 4.20 sobre
`--papel-afundado` — e reprova nos dois. Foi por isso que a lista de resultados virou uma superfície
com fundo próprio em vez de ficar solta sobre a página.

A medição também mostrou que **nenhum status passa em corpo de texto** (3.25 a 3.62), consequência
direta de todos compartilharem luminosidade 0.62 para pesarem igual. Continuam certos como borda e
fundo claro; como texto, não.

Resultado: **todo elemento que a tela pinta passa em AA**, com mínimo de 4.67. A tabela de
`16-design-system.md` foi substituída pelos números reais.

## 9. A prévia visual

`frontend/preview/` roda a tela **real**, com o CSS real, sem backend — só a rede é falsa.

Existe porque teste de comportamento não olha para a tela: os três defeitos da seção 7 passaram por
todos os testes. É o mecanismo que torna executável a lição que já estava escrita no projeto —
*documentar não substitui olhar o resultado*.

```bash
npm run previa --prefix frontend
```

## 10. Decisões menores que importam

**Pausa de 220ms antes de buscar.** Sem ela, "Maria" dispara cinco consultas e a quinta pode chegar
antes da terceira — a lista pisca e às vezes assenta no resultado errado.

**O apelido é o nome principal.** "Dona Zilda" é o que a recepção procura; o nome do documento
aparece só quando difere, porque repetir a mesma linha duas vezes é ruído numa lista lida de
relance.

**`Esc` limpa a busca** sem tirar a mão do teclado (R-UX-02).

**Hover e seleção mudam a barra lateral, não o fundo.** Trocar o fundo mudaria o contraste do texto
a cada estado, e o de hover cairia num par pior que o de repouso.

**Cadastro já preenchido** com o que foi digitado, e qual campo recebe o termo vem da interpretação
do servidor. Ela procurou "Marcia", não achou, e vai cadastrar Marcia.

**Juntar fichas pede confirmação**, contrariando a regra geral R-UX-05 de executar e oferecer
desfazer: mesclar não tem desfazer (pendência aberta de RT-CLI-003), e onde não há desfazer,
perguntar antes é o que sobra.

## 11. Testes

- [x] `Clientes.test.tsx` — 9: abre ensinando a buscar e **sem pedir nada ao servidor** · mostra a
      interpretação do servidor mesmo quando ela contraria o que o front adivinharia · conta as
      fichas · usa o texto do domínio para o que falta · **destaca os dígitos que casaram** ·
      apelido como nome principal · cadastro preenchido · campo certo conforme a interpretação ·
      `Esc` limpa
- [x] `Ficha.test.tsx` — 8: **efeito separado do registro** · nada marcado sem registro · recusar
      com o mesmo peso de aceitar · registra com o canal escolhido · **mesmo telefone não é
      duplicidade e não oferece juntar** · duplicado provável exige confirmação · sem permissão,
      apagar não aparece · com permissão, exige confirmação e explica o que sobra
- [x] Contraste medido no navegador: todo elemento em AA, mínimo 4.67

## 12. Pendências

- [x] ~~Editar a ficha não existe~~ — RT-CLI-007, acrescentada ao plano e entregue em seguida
- [ ] **Sem navegação por setas na lista.** `Tab` funciona, mas a recepção espera ↑↓ numa lista de
      resultados. Vale junto de RT-AGD-011, que traz a grade
- [ ] **A prévia usa dados fixos.** Ela mostra a tela, não o sistema — um erro de integração entre
      front e backend não aparece ali. Cobrir com E2E quando houver ambiente de homologação
      (RT-INF-009)
- [ ] **`design:accessibility-review` não foi rodada.** Medi contraste e conferi foco e navegação
      por teclado, que era a lacuna conhecida; a revisão completa (leitor de tela, ordem semântica,
      alvos de toque) continua pendente
- [ ] Sem estado de carregamento visível na busca: com placeholder da consulta anterior, a lista
      não pisca, mas também não avisa que está atualizando
