---
id: RT-CLI-002
titulo: Busca de cliente por nome, apelido e 4 últimos dígitos
modulo: cliente
fase: 1
perfil: completo
status: implementado
depende_de: [RT-CLI-001]
permissoes: [cliente:read]
eventos: []
regras: [RN-CLI-009, RN-CLI-010, RN-CLI-011, RN-CLI-012]
atualizado_em: 2026-08-30
---

# RT-CLI-002 — Busca de cliente

## 1. Objetivo

Achar a pessoa certa **enquanto o telefone toca**. É o uso real: alguém liga, a recepção precisa
saber quem é antes de atender pelo nome.

## 2. Um campo só, e o sistema entende

Buscar por nome falha no balcão — oito Marias, apelidos, grafia. Os quatro últimos dígitos
resolvem na primeira tentativa (R-UX-03). Mas ninguém vai escolher num seletor entre "buscar por
nome" e "buscar por telefone": seria pedir uma decisão a quem está com o telefone na outra mão.

A regra que dispensa o seletor é simples e não tem exceção prática: **nome não tem dígito**
(RN-CLI-009). Se veio dígito, é telefone.

`TermoDeBusca` é um `sealed interface` com três casos — `PorNome`, `PorTelefone`, `Insuficiente` —
e o repositório casa sobre eles. Um `boolean ehTelefone` teria funcionado hoje e deixado o terceiro
caso sem lugar para existir.

## 3. Termo curto não é erro

`Insuficiente` devolve lista vazia, nunca 422 (RN-CLI-010). É o estado normal enquanto a pessoa
digita a primeira letra — um erro por tecla faria a tela piscar.

Os limites são diferentes por tipo, e por um motivo: dois dígitos casariam com metade do salão,
enquanto duas letras já estreitam bastante. Três dígitos, duas letras.

## 4. A normalização mora em **um** lugar

Nome e apelido vivem numa coluna `busca` **gerada pelo banco**, minúscula e sem acento. O termo
digitado passa pela **mesma função** na consulta.

Isto é o ponto do desenho, não um detalhe de implementação. A normalização do que está guardado e
a do que foi digitado precisam ser idênticas; mantê-las em dois lugares garante que um dia
divirjam — e no dia em que divergissem, a busca simplesmente pararia de achar gente, **sem erro
nenhum**. É o pior tipo de defeito: silencioso e plausível.

Foi por isso que normalizar em Java foi recusado, mesmo sendo mais testável em unidade.

O preço está declarado: `imutavel_unaccent` marca como `IMMUTABLE` uma função que o Postgres
considera `STABLE`. Se o dicionário `unaccent` mudar no servidor, o índice fica desatualizado em
silêncio. Mitigação em [runbook/reindexar-busca.md](../../runbook/reindexar-busca.md); o dicionário
é estático na prática.

Nome e apelido ficam no **mesmo** campo: quem procura "ju" quer achar tanto "Juliana" quanto a "Ju"
do apelido, e não sabe — nem deveria saber — em qual coluna o salão registrou isso.

## 5. Índices: o trigram estava certo para um caso e errado para o outro

**Nome:** GIN trigram. É exatamente o caso para o qual `pg_trgm` existe — substring em texto.

**Telefone:** btree sobre o número **invertido**, com `text_pattern_ops`. A busca é por sufixo, e
btree ordena pelo começo — que no telefone é o país e o DDD, iguais para todo o salão. Invertendo,
o sufixo vira prefixo e o btree serve exatamente.

Trigram foi tentado no telefone primeiro e **é a ferramenta errada**: trigrama de dígito tem
seletividade péssima, porque todo número do salão compartilha os mesmos pedacinhos. Medido com
`EXPLAIN`: o planejador preferia varrer a tabela.

Os dois índices são **parciais** por `anonimizado_em is null`, o mesmo predicado da consulta. Sem
isso o planejador usava um índice parcial qualquer só para resolver o predicado e aplicava o `LIKE`
como filtro sobre tudo.

## 6. O teste de índice que não provava nada (duas vezes)

Vale registrar porque o erro é fácil de repetir.

**Primeira versão:** `EXPLAIN` com a tabela vazia. Com zero linhas todo índice custa o mesmo, e o
planejador escolheu um qualquer. O teste passaria com os índices trigram **ausentes**.

**Segunda versão:** cinco mil fichas. O planejador preferiu varrer um índice parcial inteiro — e
estava *certo*, porque nesse tamanho é mesmo mais barato.

**Versão final:** cem mil fichas e `ANALYZE`. Aí o plano vira e os índices são escolhidos por
mérito. O número não é decorativo: é a resposta honesta para "a partir de quando o índice paga o
próprio custo", e o teste é o único lugar do projeto onde isso está medido.

## 7. Sem paginação, contrariando a regra do projeto

Uma busca que devolve a segunda página é uma busca que **falhou**: a resposta útil é refinar o
termo. O limite (20, teto de 50) existe para proteger o banco de um termo genérico demais, não para
navegar.

É diferente da listagem de clientes (RT-CLI-006), que vai precisar de cursor porque ali o objetivo
é percorrer tudo.

Sem `q`, a resposta é lista vazia — não a base inteira. O endpoint de busca não é o de listagem, e
devolver todo mundo entregaria o cadastro a quem só sabe chamar a rota sem parâmetro.

## 8. Curinga neutralizado

`%` e `_` são escapados antes do `LIKE` (RN-CLI-012). Não é injeção — o valor vai como parâmetro —,
é **resultado errado**: quem digitasse `%` veria o salão inteiro. É o tipo de falha que ninguém
reporta e todo mundo acaba usando.

O escape mora no repositório, não no domínio: `LIKE` é detalhe de como este repositório consulta, e
o domínio não deve saber que existe SQL do outro lado.

## 9. Testes

- [x] `TermoDeBuscaTest` — 14: dígito significa telefone · máscara descartada · sem dígito é nome ·
      curto não é erro · limites diferentes por tipo · espaço não conta como letra
- [x] `BuscaDeClienteIT` — 11: **quatro últimos dígitos** · número inteiro com e sem máscara ·
      acento nos dois sentidos · apelido · sobrenome no meio do nome · ordem por relevância ·
      curto devolve vazio · **curinga não vaza a agenda** · limite padrão e teto · isolamento
      entre salões · sem `cliente:read` é recusado
- [x] `BuscaIndexadaIT` — 2: os dois caminhos usam índice a cem mil fichas · `busca` é coluna
      gerada pelo banco
- [x] `ClientesWebIT` — 3 novos: busca por 4 dígitos num parâmetro só · curto devolve `[]` com
      200 · sem `q` não despeja a base

## 10. Pendências

- [ ] **Busca por nome com 2 letras não usa índice** (`LIKE '%ab%'` precisa de 3 caracteres para o
      trigram). Hoje cai em varredura; num salão de dezenas de milhares de fichas isso vai doer.
      Rever junto de RT-CLI-006
- [ ] **Ordenação não considera frequência**: a cliente que vem toda semana deveria aparecer antes
      de uma homônima que veio uma vez em 2019. Depende do histórico de atendimento (Fase 2)
- [ ] **Enumeração da base com token válido.** O limitador de RT-INF-011 já cobre esta rota — ele
      vale para todo `/api/**` —, mas com a faixa "geral", dimensionada para uso normal de tela
      (uma agenda dispara várias chamadas ao abrir). Ela não constrange quem varre o cadastro
      quatro caracteres por vez, e conta **por IP**, não por usuário. O que falta é um limite por
      usuário nas rotas de leitura de dado pessoal. Decidir junto de RT-CLI-004
- [ ] Busca não registra em auditoria quem consultou a ficha de quem — relevante para LGPD, decidir
      junto de RT-CLI-004
