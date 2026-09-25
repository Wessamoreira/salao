---
id: RT-EQP-005
titulo: "Tela da equipe: profissional, jornada, exceções, habilidades e duração"
modulo: equipe
fase: 7
perfil: completo
status: implementado
depende_de: [RT-EQP-001, RT-EQP-002, RT-EQP-003, RT-EQP-004, RT-CAT-005, RT-INF-010]
permissoes: [usuario:manage]
eventos: []
regras: [RN-EQP-001, RN-EQP-002, RN-EQP-003, RN-EQP-005, RN-EQP-006, RN-EQP-008, RN-EQP-009, RN-EQP-011, RN-EQP-013, RN-EQP-014, RN-EQP-017]
atualizado_em: 2026-09-24
---

# RT-EQP-005 — A equipe na tela

## 1. Objetivo

Cadastrar profissional, jornada, férias e habilidades pela tela — e fazer a agenda deixar de
abrir vazia.

## 2. Contexto de negócio

A agenda diz, hoje, para quem abre o sistema pela primeira vez:

> **Ninguém para atender ainda** — a agenda aparece quando houver profissional cadastrado com
> jornada.

Está certa, e não havia como consertar: cadastrar profissional e jornada só era possível por
chamada HTTP na mão. É o bloqueio nº 1 do levantamento de `18-o-que-falta-para-usar.md`, e o que
separa "o sistema roda" de "o salão consegue começar".

Doze endpoints de profissional e quatro de bloqueio de jornada estão entregues e testados desde a
Fase 1. Nenhum tem tela.

E, como no catálogo, a falta de tela escondeu um buraco: **não existe editar profissional**. Só
cadastrar, ativar e desativar. Uma letra errada no nome, um telefone que mudou, a comissão
renegociada — nada disso tem conserto, e a saída seria cadastrar outra pessoa e desativar a
primeira, partindo o extrato de comissão em dois.

## 3. Atores e permissões

| Ator | Permissão exigida | Escopo | Observação |
|---|---|---|---|
| Dono / gerente | `usuario:manage` | tenant | cadastrar, editar, jornada, bloqueio, habilidades |
| Recepção | — | — | não entra aqui; a lista de quem atende já chega pela agenda |

O menu "Equipe" aparece para quem tem `usuario:manage`, no grupo **Gestão** — a mesma permissão
que o servidor já exige para cadastrar profissional. Não é decisão da tela.

## 4. Pré-condições

- [x] Estabelecimento configurado (RT-CFG-001)
- [x] Catálogo com serviços (RT-CAT-005) — para restringir habilidade a serviços que existem

## 5. Fluxo principal

1. O dono abre **Equipe**. Vê quem atende, com vínculo, comissão, jornada resumida e o que cada
   uma executa.
2. **Nova profissional**: nome, apelido, documento (CPF ou CNPJ, conforme o vínculo), telefone,
   comissão e contrato.
3. **Jornada**: os sete dias, com os intervalos de cada um. Almoço é a ausência de intervalo
   entre dois blocos (RN-EQP-006) — não existe campo de almoço.
4. Salva. A agenda do dia seguinte abre com a coluna dela.
5. **Habilidades**: por padrão executa tudo (RN-EQP-013). Restringir é marcar quais serviços — e
   é ali que se ajusta a duração própria de cada um (RT-CAT-004).
6. **Férias, curso, atestado**: bloqueio por período, dia inteiro ou um pedaço de um dia.

## 6. Fluxos alternativos

**A1 — Corrigir a ficha.** Editar nome, apelido, telefone, e-mail, comissão e contrato, com a
`versao` lida pela tela. Documento e vínculo **não se editam**: mudar o documento é outra pessoa
jurídica, e o vínculo determina o tipo de documento (RN-EQP-002) — os dois juntos seriam uma
troca de identidade com o mesmo histórico de comissão pendurado.

**A2 — Documento repetido.** O servidor recusa (RN-EQP-004) e a tela mostra a frase dele.

**A3 — Parceira sem contrato assinado.** É registrado, não bloqueado (RN-EQP-003): a tela marca
"contrato pendente" na lista, porque é obrigação legal do salão e some do radar sem um lembrete.

**A4 — Tirar da equipe.** Desativa, nunca apaga (RN-EQP-005). Quem saiu continua no extrato e no
histórico da agenda.

**A5 — Restringir a nada.** Recusado pelo servidor (RN-EQP-014): quem não executa nada sai da
agenda desativando, não zerando as habilidades. A tela mostra a recusa.

**A6 — Bloqueio do salão inteiro.** Sem escolher profissional, vale para todo mundo (RN-EQP-009)
— é o feriado.

## 7. Regras de negócio aplicadas

| ID | Resumo em uma linha | Garantida em |
|---|---|---|
| RN-EQP-001 | Documento validado por dígito verificador | `Documento` |
| RN-EQP-002 | O vínculo determina o tipo de documento | `CadastrarProfissionalUseCase` |
| RN-EQP-003 | Parceria sem contrato é registrada, não bloqueada | `CadastrarProfissionalUseCase` |
| RN-EQP-005 | Profissional é desativado, nunca apagado | `DefinirAtivacaoDoProfissionalUseCase` |
| RN-EQP-006 | Jornada é lista de intervalos; almoço é ausência | `JornadaSemanal` |
| RN-EQP-008 | Sem jornada, o profissional não atende | `ProfissionaisJdbc.intervalosEm` |
| RN-EQP-009 | Bloqueio sem profissional vale para o salão | `RegistrarBloqueioUseCase` |
| RN-EQP-011 | Bloqueio é dia inteiro ou pedaço de um dia só | `BloqueioDeJornada` |
| RN-EQP-013 | Habilidade tem dois regimes, e o padrão é o aberto | `Habilidades` |
| RN-EQP-014 | Restringir a nenhum serviço é recusado | `Habilidades` |
| **RN-EQP-017** | **Identidade não se edita: documento e vínculo são fixos** | `EditarProfissionalUseCase` |

## 8. O que este documento decide de desenho

**A jornada é uma semana, não um formulário.** Sete linhas, uma por dia, cada uma com os
intervalos que existirem. Quem trabalha 9–13 e 14–18 vê duas faixas e o buraco entre elas — que é
o almoço, e é assim que o domínio o representa. Um campo "almoço: de ___ até ___" seria uma
terceira representação da mesma coisa, e a primeira a divergir.

**Copiar o dia é o atalho que faz a tela valer.** Jornada de salão é quase sempre a mesma de
terça a sexta. Preencher cinco dias iguais na mão é o tipo de trabalho que faz alguém desistir da
tela e voltar para a planilha.

**Habilidade abre fechada só quando se pede.** O padrão é executar tudo; a lista de serviços só
aparece depois de escolher restringir. Mostrar quarenta caixas de seleção para quem executa tudo
é pedir uma decisão que 90% dos salões não precisa tomar.

**A lista mostra o que decide o dia:** quem atende, quando, e o que executa. Vínculo e comissão
vêm depois — são do contrato, não da agenda.

## 9. Contrato de API

```http
GET    /api/v1/profissionais
POST   /api/v1/profissionais
PUT    /api/v1/profissionais/{id}          ← NOVO (RN-EQP-017)
PATCH  /api/v1/profissionais/{id}/ativacao
GET    /api/v1/profissionais/{id}/jornada
PUT    /api/v1/profissionais/{id}/jornada
GET    /api/v1/profissionais/{id}/habilidades
PUT    /api/v1/profissionais/{id}/habilidades
GET    /api/v1/bloqueios-de-jornada?inicio=&fim=[&profissionalId=]
POST   /api/v1/bloqueios-de-jornada
DELETE /api/v1/bloqueios-de-jornada/{id}
```

**Request da edição**

```http
PUT /api/v1/profissionais/9c2a…
{
  "nome": "Ana Ribeiro",
  "apelido": "Ana",
  "telefone": "11987654321",
  "email": "ana@salao.test",
  "comissaoPercentual": 45.00,
  "contratoInicio": "2026-01-10",
  "contratoFim": null,
  "contratoAssinadoEm": "2026-01-08",
  "versao": 2
}
```

Sem `documento` e sem `vinculo`: os dois são identidade (RN-EQP-017).

**Erros**

| Código | Quando |
|---|---|
| `ER-EQP-DADOS_INVALIDOS` | 422 — nome vazio, telefone impossível, comissão fora de 0–100 |
| `ER-EQP-PROFISSIONAL_NAO_ENCONTRADO` | 404 — id de outro salão ou inexistente |
| `ER-EQP-CONFLITO_DE_VERSAO` | 409 — alguém editou antes |

## 10. Dados

Nenhuma migration: a edição escreve nas colunas que já existem em `profissional`.

## 11. Testes

| O que prova | Onde |
|---|---|
| Editar troca os dados e sobe a versão | `EditarProfissionalIT` |
| Documento e vínculo não mudam por edição (RN-EQP-017) | `EditarProfissionalIT` |
| Versão velha perde para quem salvou antes | `EditarProfissionalIT` |
| Profissional de outro salão não é encontrado | `EditarProfissionalIT` |
| Quem não gerencia usuário não edita | `EditarProfissionalIT` |
| A tela lista, cadastra, edita, define jornada e habilidades | `Equipe.test.tsx` |
| Copiar o dia repete os intervalos nos dias escolhidos | `Equipe.test.tsx` |
| A recusa do servidor aparece com as palavras dele | `Equipe.test.tsx` |
| Sem violação estrutural de acessibilidade | `Equipe.test.tsx` |

## 12. O que foi construído

| Peça | Onde |
|---|---|
| Editar profissional (com versão, sem identidade) | `EditarProfissionalUseCase` · `PUT /v1/profissionais/{id}` |
| Validação de nome, telefone e comissão, comum a cadastrar e editar | `CamposDaFicha` |
| Menu "Equipe" para quem tem `usuario:manage` | `ConsultarCapacidadesUseCase` |
| A tela: lista, ficha, jornada, habilidades e ausências | `frontend/src/telas/equipe/` · rota `/equipe` |
| Prévia sem backend | `npm run previa:equipe` |

**Testes.** 8 de integração (`EditarProfissionalIT`) e 14 de tela (`Equipe.test.tsx`), incluindo
acessibilidade com axe. Rodada de mutação: 7 mutações, 7 mortas — e uma delas mostrou a regra da
identidade escrita em dois lugares (tela e gancho), o que virou um lugar só.

## 13. Pendências

- [ ] **Foto do profissional** não existe — a agenda mostra inicial, e é suficiente por enquanto.
- [ ] **Jornada com vigência** (a partir de tal dia a jornada muda) não existe: hoje ela é uma
      só, e mudá-la vale para o passado também em qualquer consulta que reconstrua disponibilidade.
- [ ] **Bloqueio recorrente** ("toda segunda de folga") seria jornada, não bloqueio — e é assim
      que deve ser feito. Fica o registro para quem tentar o contrário.
- [ ] **Vínculo não muda**, e um dia vai precisar mudar (parceira que vira CLT). Hoje o caminho é
      desativar e cadastrar de novo; quando o caso aparecer, ele vira rotina própria.
