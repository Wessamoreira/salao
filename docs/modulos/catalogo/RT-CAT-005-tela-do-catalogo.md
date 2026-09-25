---
id: RT-CAT-005
titulo: Tela do catálogo: serviço com blocos, preço com vigência e recurso
modulo: catalogo
fase: 7
perfil: completo
status: implementado
depende_de: [RT-CAT-001, RT-CAT-002, RT-CAT-003, RT-INF-010]
permissoes: [config:manage, agenda:read:all]
eventos: []
regras: [RN-CAT-001, RN-CAT-002, RN-CAT-003, RN-CAT-004, RN-CAT-005, RN-CAT-008, RN-CAT-009, RN-CAT-010, RN-CAT-012, RN-CAT-015]
atualizado_em: 2026-09-23
---

# RT-CAT-005 — O cardápio na tela

## 1. Objetivo

Cadastrar, corrigir e precificar serviços e recursos pela tela — sem uma chamada HTTP na mão.

## 2. Contexto de negócio

O catálogo tem servidor completo desde a Fase 1: serviço com blocos ATIVO/PAUSA, preço com
vigência e histórico, recurso contável. Sete endpoints de serviço e quatro de recurso, todos
testados. **Nenhum deles tem por onde ser usado.**

Na prática, isso quer dizer que um salão novo não consegue cadastrar o próprio cardápio: sem
serviço não há o que marcar na agenda nem o que lançar na comanda. É o primeiro bloqueio do
levantamento de `18-o-que-falta-para-usar.md`, e é o que abre a Fase 7.

Junto vem um buraco que o servidor tem e ninguém tinha percebido porque não havia tela:
**não existe editar serviço**. Só cadastrar e ativar/desativar. Um erro de digitação no nome, uma
pausa de 40 minutos que deveria ser 45, um recurso que passou a ser exigido — nada disso tem
conserto hoje, e a saída seria cadastrar um serviço novo e desativar o velho, o que espalha o
histórico da agenda por dois cardápios.

## 3. Atores e permissões

| Ator | Permissão exigida | Escopo | Observação |
|---|---|---|---|
| Dono / gerente | `config:manage` | tenant | cadastrar, editar, precificar, ativar/desativar (RN-CAT-004) |
| Recepção | `agenda:read:all` | tenant | **só lê** a lista, que já é o que enche os seletores da agenda e da comanda |

O menu "Catálogo" aparece para quem tem `config:manage`, no grupo **Gestão**. Quem só lê não
recebe o menu: a lista já chega pelas telas que precisam dela, e uma tela inteira em modo
somente-leitura seria uma porta que não leva a nada.

## 4. Pré-condições

- [x] Estabelecimento configurado (RT-CFG-001)
- [x] Usuário com `config:manage`

## 5. Fluxo principal

1. O dono abre **Catálogo**. Vê a lista de serviços com receita resumida, duração total, minutos
   do profissional e preço vigente.
2. Clica em **Novo serviço**. Dá nome, descrição opcional e monta a receita: etapas de trabalho e
   de espera, em minutos, com os recursos que cada etapa exige.
3. Salva. O serviço nasce **ativo e sem preço** — e a lista diz isso, porque serviço sem preço não
   pode ser vendido (RN-CAT-007).
4. Define o preço: valor e a partir de quando vale. O padrão é hoje; data futura é o uso normal.
5. A agenda e a comanda passam a oferecer o serviço no mesmo instante.

## 6. Fluxos alternativos

**A1 — Corrigir a receita.** Editar nome, descrição e etapas de um serviço que já existe. O que
já foi marcado **não muda** (RN-CAT-015): o agendamento materializou os blocos dele quando foi
criado. A tela diz isso antes de salvar, em uma linha, e não pergunta de novo.

**A2 — Preço retroativo.** Vigência anterior a hoje é aceita e marcada (RN-CAT-008). A tela avisa
que o preço passa a valer para trás e que comandas já fechadas **não mudam** (RN-CAT-006).

**A3 — Cancelar um aumento anunciado.** Preço futuro pode ser removido enquanto não começou a
valer. O preço vigente nunca é apagado.

**A4 — Recurso em uso.** Desativar um recurso que uma receita ativa exige é recusado pelo servidor
(RN-CAT-012). A tela mostra a recusa do servidor, com as palavras dele.

**A5 — Duas pessoas editando.** A edição leva a `versao` que foi lida. Quem chegar depois recebe
409 e a tela recarrega mostrando o que mudou, em vez de apagar a correção do outro (mesmo padrão
de RT-CLI-007).

## 7. Regras de negócio aplicadas

| ID | Resumo em uma linha | Garantida em |
|---|---|---|
| RN-CAT-001 | Serviço é sequência de blocos ATIVO e PAUSA | `SequenciaDeBlocos` |
| RN-CAT-002 | Começa e termina com ATIVO | `SequenciaDeBlocos` |
| RN-CAT-003 | Blocos do mesmo tipo não se sucedem | `SequenciaDeBlocos` |
| RN-CAT-004 | Alterar o cardápio é do dono; ler é de quem marca | `@PreAuthorize` |
| RN-CAT-005 | Preço tem início e nunca fim | `DefinirPrecoUseCase` |
| RN-CAT-008 | Preço retroativo é permitido e marcado | `DefinirPrecoUseCase` |
| RN-CAT-009 | Recurso é contável e nunca zero | `CadastrarRecursoUseCase` |
| RN-CAT-010 | O recurso é exigido pelo bloco, não pelo serviço | `EtapaBruta` |
| RN-CAT-012 | Não se desativa recurso que receita ativa exige | `DefinirAtivacaoDoRecursoUseCase` |
| **RN-CAT-015** | **Editar a receita não mexe no que já foi marcado** | `EditarServicoUseCase` |

## 8. O que este documento decide de desenho

**A receita é a tela.** Nome e preço são campos; a sequência de blocos é a única coisa deste
cadastro que não existe em nenhum outro sistema de salão, e é o que faz a agenda saber que a
profissional está livre durante a química. Ela ocupa o centro do editor, desenhada como o que é:
uma faixa de tempo com pedaços de trabalho e de espera.

**O front não valida a receita.** "Começa e termina com ATIVO" e "dois iguais não se sucedem" são
regras de domínio (RN-CAT-002, RN-CAT-003) e vivem no servidor. O editor **ajuda** — o botão de
adicionar etapa oferece o tipo que faz sentido em seguida — mas não recusa nada por conta própria,
e mostra a recusa do servidor quando ela vem. Regra copiada para o JavaScript é regra que um dia
diz uma coisa enquanto o servidor faz outra.

**Dois números, não um.** A lista mostra **duração total** e **minutos do profissional**. São
diferentes justamente nos serviços que dão dinheiro: uma coloração de 1h40 ocupa a profissional
por 1h00, e é o segundo número que decide se cabe um corte no meio.

**Serviço sem preço é um estado, não um erro.** Ele nasce assim, e a lista marca — porque a
comanda vai recusar vendê-lo (RN-CAT-007) e é melhor descobrir no cadastro do que no balcão.

## 9. Contrato de API

Tudo já existe, menos a edição.

```http
GET    /api/v1/servicos?incluirInativos=true
POST   /api/v1/servicos
PUT    /api/v1/servicos/{id}          ← NOVO (RN-CAT-015)
PATCH  /api/v1/servicos/{id}/ativacao
GET    /api/v1/servicos/{id}/precos
POST   /api/v1/servicos/{id}/precos
DELETE /api/v1/servicos/{id}/precos/{precoId}
GET    /api/v1/recursos?incluirInativos=true
POST   /api/v1/recursos
PUT    /api/v1/recursos/{id}
PATCH  /api/v1/recursos/{id}/ativacao
```

**Request da edição**

```http
PUT /api/v1/servicos/6f1c…
{
  "nome": "Coloração",
  "descricao": "raiz e comprimento",
  "versao": 3,
  "etapas": [
    { "tipo": "ATIVO", "duracaoMinutos": 30, "recursosExigidos": ["a1b2…"] },
    { "tipo": "PAUSA", "duracaoMinutos": 40, "recursosExigidos": [] },
    { "tipo": "ATIVO", "duracaoMinutos": 30, "recursosExigidos": [] }
  ]
}
```

**Response 200** — o `ServicoResumo` já com a receita nova e `versao` incrementada.

**Erros**

| Código | Quando |
|---|---|
| `ER-CAT-RECEITA_INVALIDA` | 400 — a sequência viola RN-CAT-001/002/003 |
| `ER-CAT-SERVICO_NAO_ENCONTRADO` | 404 — id de outro salão ou inexistente |
| `ER-INF-CONFLITO_DE_VERSAO` | 409 — alguém editou antes (A5) |
| `ER-CAT-RECURSO_NAO_ENCONTRADO` | 400 — etapa exige recurso de outro salão (RN-CAT-011) |

## 10. Dados

Nenhuma migration. A edição reescreve `servico` e `servico_bloco` na mesma transação, e o
histórico de preços não é tocado.

## 11. Testes

| O que prova | Onde |
|---|---|
| Editar troca a receita e sobe a versão | `EditarServicoIT` |
| Editar não mexe em agendamento já criado (RN-CAT-015) | `EditarServicoIT` |
| Receita inválida é recusada com código estável | `EditarServicoIT` |
| Versão velha perde para quem salvou antes | `EditarServicoIT` |
| Recurso de outro salão é recusado (RN-CAT-011) | `EditarServicoIT` |
| A tela lista, cadastra, edita, precifica e desativa | `Catalogo.test.tsx` |
| A tela mostra a recusa do servidor, não uma inventada | `Catalogo.test.tsx` |
| Sem violação estrutural de acessibilidade | `Catalogo.test.tsx` |

## 12. O que foi construído

| Peça | Onde |
|---|---|
| Editar serviço (nome, descrição, receita, versão) | `EditarServicoUseCase` · `PUT /v1/servicos/{id}` |
| Montagem e validação da receita, comum a cadastrar e editar | `MontadorDeReceita` |
| Cardápio com preço vigente numa consulta só | `ListarCardapioUseCase` · `ServicosJdbc.precosVigentesEm` |
| O serviço na forma que a tela lê (dois tempos + preço + etapas) | `ServicosController.ServicoNaTela` |
| Menu "Catálogo" para quem tem `config:manage` | `ConsultarCapacidadesUseCase` |
| A tela | `frontend/src/telas/catalogo/` · rota `/catalogo` |
| Prévia sem backend | `npm run previa:catalogo` |

**Testes.** 10 de integração (`EditarServicoIT` com 8, `PrecoDoServicoIT` com 2 novos) e 15 de
tela (`Catalogo.test.tsx`), incluindo acessibilidade com axe.

## 13. Pendências

- [ ] **Não há categoria** (cabelo, unha, estética) — herdada de RT-CAT-001. Um cardápio de
      quarenta itens sem agrupamento é uma lista para rolar, e a tela só adia o problema.
- [ ] **Nada diz quantos agendamentos futuros usam a receita antiga.** A contagem exige perguntar
      ao módulo `agenda` pela API pública dele; enquanto não existe, a tela afirma o que é verdade
      (o que está marcado não muda) e não tenta quantificar.
- [ ] **Preço por profissional continua fora** (RT-CAT-002), e é comum: sênior cobra mais.
- [ ] **Bloqueio de recurso** ("o lavatório quebrou hoje") continua sem existir (RT-CAT-003).
- [ ] **Duração por profissional** (RT-CAT-004) é editada na tela da equipe, não aqui — a relação
      é profissional × serviço, e o lugar dela é RT-EQP-005.
