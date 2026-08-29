# Padrão de documentação

O objetivo é concreto: **qualquer pessoa ou IA que abra uma rotina consegue implementá-la sem
perguntar nada que já foi decidido.** Tudo que for perguntado e respondido volta para cá.

## O ciclo de uma rotina

```
1. Escreve a doc da rotina (template abaixo), com as seções 1–7 preenchidas
2. Lê em voz alta para o dono do salão → corrige regras
3. Registra cada regra descoberta em regras.md com ID RN-XXX
4. Implementa
5. Completa a doc (seções 8–17) com o que a implementação revelou
6. Marca a DoD e muda status para `implementado`
```

A ordem importa: **a doc vem antes do código**. Não porque é bonito, mas porque escrever a seção
"casos de borda" descobre metade dos bugs antes de existirem.

## Onde cada coisa mora — e por que separado

| Arquivo | Contém | Por que separado |
|---|---|---|
| `regras.md` | **Catálogo de regras (RN)** do módulo | Uma regra vale para várias rotinas. Se cada rotina redefinir a regra, elas divergem em três meses |
| `RT-*.md` | Uma rotina: fluxo, contrato, dados, testes | É a unidade de trabalho |
| `_modulo.md` | Responsabilidade, agregados, API pública, eventos | Contexto que toda rotina do módulo assume |
| `adr/` | Decisão estrutural, com alternativas descartadas | Impede que a decisão seja "melhorada" de volta ao estado ruim |

**Rotina referencia regra, nunca redefine.** Na rotina você escreve `RN-AGD-004` e o resumo em
uma linha; o enunciado completo, o motivo e o teste vivem no catálogo.

## Regra de negócio — formato do catálogo

```markdown
### RN-AGD-004 — Blocos ativos do mesmo profissional não podem se sobrepor

**Enunciado.** Dois blocos que ocupam o mesmo profissional, em agendamentos com status
SOLICITADO, CONFIRMADO ou EM_ATENDIMENTO, não podem ter interseção de período.

**Motivo.** Duas clientes na mesma cadeira ao mesmo tempo. Foi o motivo nº 1 de reclamação no
caderno de papel.

**Onde é garantida.**
- Banco: constraint `bloco_sem_sobreposicao_profissional` (fonte da verdade)
- Aplicação: `AgendaPolitica.validarConflito` — apenas para dar erro amigável antes de gravar

**Rotinas que aplicam.** RT-AGD-002, RT-AGD-003, RT-AGD-008, RT-CNV-005

**Teste que prova.** `AgendamentoConcorrenteIT.duas_threads_no_mesmo_horario_uma_falha`

**Erro exposto.** `ER-AGD-CONFLITO_HORARIO` (409)

**Configurável?** Não.

**Origem.** Decidido com o dono em 2026-08-28. · **Revisada em:** 2026-08-28
```

Os campos que as pessoas cortam e depois fazem falta:

- **Motivo** — sem ele, alguém "otimiza" a regra fora em seis meses.
- **Onde é garantida** — distingue o que o banco garante do que é só UX. Confundir os dois é
  como nascem os bugs de concorrência.
- **Teste que prova** — regra sem teste é comentário.
- **Configurável?** — separa regra de produto de preferência de cliente. Preferência vira coluna
  de configuração, não `if`.
- **Origem** — quem decidiu e quando. Resolve discussão em vez de reabri-la.

## Rotina — as 17 seções e por que cada uma existe

Template completo em `_templates/rotina.md`. Exemplo real preenchido em
`modulos/agenda/RT-AGD-002-criar-agendamento.md`.

| # | Seção | Existe para |
|---|---|---|
| 1 | Objetivo | Uma frase. Se não couber em uma, a rotina faz duas coisas |
| 2 | Contexto de negócio | O que quebra se não existir. Justifica a prioridade |
| 3 | Atores e permissões | Quem chama e com qual permissão e escopo |
| 4 | Pré-condições | O que já tem que existir. Revela dependência entre rotinas |
| 5 | Fluxo principal | Passo a passo do gatilho ao efeito |
| 6 | Fluxos alternativos | O que mais acontece de verdade no balcão |
| 7 | Regras aplicadas | Tabela de `RN-` referenciados |
| 8 | Contrato de API | Request, response, todos os erros possíveis |
| 9 | Dados | Tabelas, colunas, migration, índices, **orçamento de queries** |
| 10 | Efeitos colaterais | Eventos, estoque, razão, notificação — o que mais acontece |
| 11 | Casos de borda | Tabela situação → comportamento → HTTP → código |
| 12 | Concorrência e idempotência | O que acontece com dois cliques e dois pods |
| 13 | Observabilidade | Métrica, log, o que alertar |
| 14 | UX e front | Estados de tela, atalho, feedback, texto de erro |
| 15 | Testes obrigatórios | Lista nomeada. Cada linha vira um `@Test` |
| 16 | Como testar manualmente | Para o dono do salão validar sem você |
| 17 | Decisões e trade-offs | O que foi descartado e por quê |

### Perfil leve

Rotina de CRUD simples (`RT-CAT-003 Cadastrar recurso`) preenche só 1, 3, 7, 8, 9, 11, 15.
Marque `perfil: leve` no cabeçalho. Não force 17 seções onde não há 17 seções de conteúdo —
documentação inflada deixa de ser lida, e documentação não lida é pior que ausente.

Perfil completo é obrigatório para: qualquer rotina que **mexa em dinheiro**, qualquer uma que
**escreva na agenda**, e todas do módulo `conversacional`.

### Orçamento de queries (seção 9)

Cada rotina declara quantas queries o caso de uso pode disparar:

```
Orçamento: 4 queries (1 carga do serviço, 1 da jornada, 1 insert cabeça, 1 insert blocos em lote)
```

O teste de integração conta com `generate_statistics` e **falha se passar**. É o único jeito
prático de N+1 não voltar depois de corrigido.

## Cabeçalho YAML — obrigatório em toda rotina

```yaml
---
id: RT-AGD-002
titulo: Criar agendamento
modulo: agenda
fase: 1
perfil: completo          # completo | leve
status: rascunho          # rascunho | especificado | em-implementacao | implementado | obsoleto
depende_de: [RT-CAT-001, RT-EQP-002, RT-AGD-001]
permissoes: [agenda:write:own, agenda:write:all]
eventos: [EV-AGD-AGENDAMENTO_CRIADO]
regras: [RN-AGD-001, RN-AGD-004, RN-AGD-007]
atualizado_em: 2026-08-28
---
```

O cabeçalho é o que permite gerar o índice, montar o grafo de dependência e responder "quais
rotinas quebram se eu mudar RN-AGD-004?" com um `grep`.

## Quando a implementação contradiz a doc

**A doc está errada até prova em contrário — corrija a doc no mesmo commit.** Doc que descreve um
sistema que não existe é pior que doc nenhuma, porque a próxima pessoa confia nela.

Se a contradição for uma regra de negócio, `regras.md` também muda, e o campo **Revisada em**
recebe a data nova.

## Higiene

- Toda rotina `implementado` tem link para os arquivos de código principais.
- Toda regra tem link para o teste que a prova.
- Rotina `obsoleta` não é apagada: recebe `status: obsoleto` e uma linha dizendo o que a
  substituiu. Histórico de por que algo deixou de existir vale ouro.
- Revisão mensal: rode `grep -L "atualizado_em: 2026" docs/modulos/**/*.md` e olhe o que ficou
  para trás.
