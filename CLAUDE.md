# Contexto fixo do projeto — leia antes de escrever qualquer linha

Sistema de gestão para salão de beleza. Monólito modular Java, multi-tenant, com agente
conversacional no WhatsApp. Um desenvolvedor. Documentação é parte do entregável, não enfeite.

## Como trabalhar neste repositório

1. **Uma rotina por vez.** Toda tarefa começa por um arquivo `docs/modulos/<modulo>/RT-XXX-*.md`.
   Se a rotina não tem doc, o primeiro passo é escrever a doc — não o código.
2. **Nunca implemente "o sistema".** Se o pedido for amplo, quebre em rotinas e confirme a ordem.
3. **Antes de escolher a próxima rotina, rode `python3 ops/scripts/checklist.py`.** Ele diz o que
   falta, em que ordem, e acusa salto de ordem. Não confie na memória nem no que "parece ser o
   próximo" — já houve um bloco inteiro pulado assim (ver `docs/14-checklist-de-execucao.md`).
4. **Se faltar regra de negócio, PERGUNTE.** Não assuma. Regra assumida errada custa reescrita
   de fechamento financeiro.
5. **Acabamento profissional é padrão, não extra.** Antes de entregar, passe o trabalho por uma
   crítica honesta e aja sobre ela — não narre o que faria. Rigor métrico (escala de espaçamento,
   nunca números arbitrários), hierarquia deliberada, nada decorativo. Mais profissional é mais
   rigor e menos ruído, nunca mais coisas.
6. **Toda regra descoberta vira registro** em `docs/modulos/<modulo>/regras.md` com ID `RN-XXX`,
   antes ou junto com o código. Regra que só existe dentro de um `if` está perdida.

## Stack (não troque sem ADR)

Java 25 · Spring Boot 4.1 · Spring Modulith · Spring Data JPA/Hibernate 7 · PostgreSQL 18 ·
Flyway · Caffeine · Spring Security · Testcontainers · Maven.
Front: React 19 + TypeScript + Vite + TanStack Query + Zustand + Tailwind (PWA).

## Arquitetura

Monólito modular. Módulos: `shared`, `iam`, `equipe`, `cliente`, `catalogo`, `agenda`,
`atendimento`, `financeiro`, `estoque`, `conversacional`, `notificacao`, `arquivos`.

- Módulo só acessa outro pela **API pública** (`<modulo>/api`) ou por **evento**. Zero join
  entre tabelas de módulos diferentes. Exceção única e documentada: o schema `relatorio`
  (ver `docs/03-arquitetura.md`).
- Dentro do módulo: `web → application (caso de uso) → domain → port` / `infra` implementa port.
- **Um caso de uso por classe**, verbo no nome: `CriarAgendamentoUseCase.executar(cmd)`.
  Proibido `AgendaService` com 40 métodos.
- DTO específico por endpoint. **Entidade JPA nunca sai do módulo nem aparece no controller.**

## Invioláveis

| # | Regra | Por quê |
|---|---|---|
| 1 | `estabelecimento_id` em toda tabela de negócio + RLS ativa | Isolamento de tenant |
| 2 | Dinheiro é `BigDecimal` / `numeric(19,4)`. Nunca `double`, nunca `float` | Erro de centavo composto |
| 3 | Tempo é `timestamptz` em UTC. Conversão só na borda, com o fuso **do estabelecimento** | Agenda quebra no horário de verão |
| 4 | Financeiro é append-only. Nunca `UPDATE` em saldo. Estorno é lançamento novo | Auditabilidade |
| 5 | Autorização no caso de uso (`@PreAuthorize`) + checagem de posse do recurso | IDOR é o bug nº 1 |
| 6 | Front não decide nada. Ele desenha `/me/capabilities`. Zero `if (perfil === 'ADMIN')` | Esconder botão não é segurança |
| 7 | Evento que sai do processo passa por outbox transacional | WhatsApp de agendamento inexistente |
| 8 | Sem sobreposição de agenda é garantido por `EXCLUDE` no banco, não por `if` | Duas instâncias concorrentes |
| 9 | Migration Flyway versionada. `ddl-auto=validate` em todo ambiente | `update` em prod destrói dado |
| 10 | Endpoint de escrita exposto a bot/webhook aceita `Idempotency-Key` | Meta reenvia webhook |

## Proibido

- Lombok `@Data` em entidade · `ddl-auto=update` · paginação com `OFFSET` · segredo em
  `.properties` versionado · retornar entidade JPA no controller · `localStorage` para token ·
  `System.out.println` · regra de negócio no front · trigger de banco para regra de negócio
  (regra fica no domínio; trigger é invisível e não testável em unidade).

## Definition of Done — nenhuma rotina fecha sem os 8

- [ ] Caso de uso implementado com teste de integração (Testcontainers, Postgres real)
- [ ] Migration Flyway versionada e irreversível-por-edição
- [ ] Regras registradas em `regras.md` com ID e link para o teste que as prova
- [ ] Permissão criada e devolvida em `/me/capabilities`
- [ ] Endpoint no OpenAPI com exemplo de request, response e erro
- [ ] Métrica ou log estruturado relevante instrumentado
- [ ] Doc da rotina atualizada (template `docs/_templates/rotina.md`)
- [ ] ADR escrita se houve decisão estrutural

## Onde achar

| Preciso de | Vá para |
|---|---|
| **O que falta fazer, em ordem** | `python3 ops/scripts/checklist.py` |
| Visão, personas, escopo | `docs/00-visao-e-escopo.md` |
| Termo do negócio | `docs/01-glossario.md` |
| Por que foi decidido assim | `docs/02-decisoes-estruturais.md`, `docs/adr/` |
| Camadas, módulos, fronteiras | `docs/03-arquitetura.md` |
| Tabelas e DDL | `docs/04-modelo-de-dados.md` |
| Tenant, RLS, LGPD, auth | `docs/05-seguranca-multitenancy-lgpd.md` |
| Convenção de código e nome | `docs/06-padroes-de-codigo.md` |
| Formato de erro, paginação, capabilities | `docs/07-contratos-de-api.md` |
| Como documentar uma rotina | `docs/08-padrao-de-documentacao.md` |
| O que fazer agora, em que ordem | `docs/09-plano-de-implementacao.md` |
| Regras de UX que não são opinião | `docs/10-usabilidade.md` |
| O que pode dar errado | `docs/11-fragilidades-e-riscos.md` |
| O que ainda não foi decidido | `docs/13-perguntas-em-aberto.md` |
