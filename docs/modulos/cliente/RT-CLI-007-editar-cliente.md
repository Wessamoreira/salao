---
id: RT-CLI-007
titulo: Editar cliente (corrigir telefone, nome, apelido)
modulo: cliente
fase: 1
perfil: completo
status: implementado
depende_de: [RT-CLI-001, RT-CLI-006]
permissoes: [cliente:write]
eventos: []
regras: [RN-CLI-029, RN-CLI-030]
atualizado_em: 2026-08-30
---

# RT-CLI-007 — Editar cliente

## 1. Objetivo

Corrigir um telefone digitado errado. É o erro mais comum do balcão, e o sistema não sabia
consertar.

## 2. Como a lacuna apareceu

Seis rotinas sabiam cadastrar, achar, juntar, consentir e anonimizar. Nenhuma sabia **corrigir**.

Só ficou evidente quando a tela (RT-CLI-006) precisou de um botão que não existia — e o padrão se
repete: é a terceira rotina descoberta escrevendo outra, depois de consentimento e anonimização.
O plano é bom para o que foi imaginado e cego para o que só aparece ao construir.

## 3. Trava otimista, e por que ela finalmente foi usada

`ER-INF-VERSAO_DESATUALIZADA` existia desde RT-INF-003 e nunca tinha sido usado. A coluna `versao`
existia desde RT-CLI-001 e era incrementada sem que ninguém a conferisse. Esta é a primeira rotina
em que os dois têm sentido, e usá-los torna reais duas coisas que eram decorativas.

O cenário concreto: duas recepções abrem a mesma ficha, uma corrige o telefone, a outra corrige o
nome e salva depois. **Sem a trava, a segunda gravação devolve o telefone antigo** — e ninguém
descobre até a cliente não atender.

Trava otimista e não bloqueio: o caso é raro, e segurar linha por um caso raro custa em todos os
outros (RN-CLI-029).

Na tela, o conflito precisa dizer o que fazer, não só o que houve: quem está com a ficha aberta não
sabe que outra pessoa salvou, e "conflito" sozinho não orienta.

## 4. Substitui, não mescla

Recebe todos os campos editáveis, sempre. Distinguir "veio nulo" de "não veio" em JSON exige
envelope ou `Optional` e é fonte conhecida de bug — e a tela manda o formulário inteiro de qualquer
jeito. Campo apagado é campo vazio, dito explicitamente.

Por isso é `PUT` e não `PATCH`.

## 5. A trilha registra o que mudou, nunca o que era

`{"campos": "telefone"}`, e não o telefone antigo (RN-CLI-030).

É consequência direta de RN-CLI-007: a auditoria é append-only e a anonimização não a alcança.
Guardar ali o telefone antigo criaria exatamente a segunda base de dado pessoal que RT-CLI-005
teve de sair caçando.

**O custo está declarado:** a trilha responde "quem alterou o telefone e quando", e não "qual era".
É a perda que a LGPD impõe, e preferi perder isso a criar um vazamento fora de alcance.

## 6. O que a extração revelou

A normalização de telefone estava dentro de `CadastrarClienteUseCase`. A edição precisava da mesma
coisa, e duas cópias divergiriam na primeira mudança de regra — a divergência apareceria como um
número que entra certo pelo cadastro e errado pela correção, ou o contrário.

Extraída para `NormalizadorDeTelefone`, compartilhada pelos dois.

## 7. Na tela: a ficha deixou de ter cópia própria

A `Ficha` guardava o cliente em estado local, vindo do resultado da busca. Depois de salvar, ela
atualizava esse estado com a resposta.

Isso é uma segunda fonte de verdade para o mesmo cliente, e quebrou de verdade na prévia: a ficha
mostrava o valor antigo até alguém buscar de novo, e uma resposta inesperada derrubava a tela.

Agora a ficha lê do servidor (`useCliente`), usando o cliente da busca só como dado inicial — abre
instantânea e se corrige sozinha quando a invalidação chega. O `useCliente` já existia e estava sem
uso, que era o sinal.

**O telefone abre formatado no formulário**, não em E.164: mostrar `5511987654321` para quem vai
trocar um dígito obrigaria a decifrar o número antes de editá-lo, e o domínio aceita máscara na
entrada de qualquer jeito.

## 8. Uma falha na ficha não derruba mais a busca

A prévia mostrou a tela inteira em branco por causa de um erro ao desenhar a ficha — levando junto
a busca, que é a única coisa de que a recepção precisa naquele segundo, com o telefone tocando.

`Isolado` contém o estrago. O escopo é estreito de propósito: envolve a ficha, não a tela. Um
limite em volta de tudo esconderia a falha inteira em vez de contê-la.

## 9. Testes

- [x] `EdicaoDeClienteIT` — 11: corrige o telefone · **mesma normalização do cadastro** ·
      telefone impossível recusado · a correção aparece na busca (a coluna gerada acompanha) ·
      campo apagado fica vazio · **versão vencida é recusada e a correção alheia sobrevive** ·
      nome vazio recusado · anonimizada não volta a ter dados · **a trilha não guarda valores** ·
      sem `cliente:write` é recusado · isolamento entre salões
- [x] `Ficha.test.tsx` — 2 novos: reapresenta a versão ao salvar e abre o telefone formatado ·
      conflito explica o que fazer

## 10. Duas armadilhas conhecidas que morderam de novo

**`tsc --noEmit` passou onde `tsc -b` reprovou.** Parâmetro de construtor com modificador é
proibido por `erasableSyntaxOnly`, e só o build completo pega. Já estava registrado em RT-INF-010 e
mordeu igual — vale rodar `npm run build`, não só o typecheck.

**Um `replace` global apagou o parâmetro errado.** Ao remover `observacao` do consentimento, a
mesma linha sumiu do `insert` de cliente. Os testes de cadastro pegaram na hora, que é o que eles
existem para fazer.

## 11. Pendências

- [ ] **Não há como editar a observação pela tela.** O campo existe no backend e no `PUT`, e o
      formulário não o traz — porque `observacao` é texto livre e a ficha técnica de química ainda
      não existe. Enquanto não existir, dar um campo grande de texto livre convida a escrever ali
      informação clínica, que ficaria em claro no banco
- [ ] **Sem histórico de alterações visível.** A trilha existe, mas ninguém a consulta pela
      interface; e como ela não guarda valores, mostrá-la responderia "mudou o telefone" sem dizer
      de quê
- [ ] Editar não verifica duplicado: mudar o nome de uma ficha para o de outra existente passa sem
      aviso. A detecção existe (RT-CLI-003) e não está ligada aqui
