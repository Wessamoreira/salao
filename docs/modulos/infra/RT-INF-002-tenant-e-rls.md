---
id: RT-INF-002
titulo: Contexto de tenant, RLS e teste de vazamento
modulo: infra
fase: 0
perfil: completo
status: em-implementacao
depende_de: [RT-INF-001]
permissoes: []
eventos: []
regras: [RN-INF-001, RN-INF-002, RN-INF-003, RN-INF-004]
atualizado_em: 2026-08-28
---

# RT-INF-002 — Contexto de tenant, RLS e teste de vazamento

> **A rotina mais importante da Fase 0.** É ela que transforma D1 de intenção em garantia.
> Se o teste de vazamento não existir na primeira semana, ele nunca vai existir — e o isolamento
> vai furar em algum ponto que ninguém consegue apontar.

## 1. Objetivo

Garantir que nenhuma query, esquecida ou maliciosa, retorne dado de um estabelecimento para
usuário de outro.

## 2. Contexto de negócio

O produto nasce para um salão, mas o schema é multi-tenant desde o dia 0 (ADR-0002) porque
colar isolamento depois significa revisar 100% das queries — e a primeira que escapar vaza dado
de um cliente para outro. Comercialmente, isso é irrecuperável: nenhum salão continua num sistema
onde a agenda dele apareceu para o concorrente.

## 3. Atores

Nenhum ator humano direto. É infraestrutura consumida por todas as rotinas.

## 4. Arquitetura da solução — três camadas

Nenhuma sozinha é suficiente. A terceira é a que faz as outras duas sobreviverem ao tempo.

### Camada 1 — Aplicação

```
JwtAuthFilter  →  resolve estabelecimentoId do token
               →  TenantContext.executar(tenantId, () -> cadeia.doFilter(...))
```

`TenantContext` usa `ScopedValue` (não `ThreadLocal`), por causa de virtual threads.

### Camada 2 — Banco

```sql
-- Role da aplicação: NÃO é dona das tabelas (RN-INF-004)
create role salao_app login password :'senha';
grant usage on schema public to salao_app;

-- Flyway conecta como owner; a aplicação, como salao_app
alter table agendamento enable row level security;
alter table agendamento force row level security;   -- sem FORCE, o owner ignora a RLS

create policy tenant_isolado on agendamento
  using      (estabelecimento_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (estabelecimento_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

Dois detalhes descobertos na implementação, ambos necessários:

- **`nullif(..., '')`** — sem ele, `''::uuid` lança erro de cast quando o tenant está vazio, e o
  modo de falha vira uma exceção confusa em vez de "zero linhas". Com ele, tenant ausente **ou**
  vazio produz `NULL`, a comparação resulta em `NULL` e nenhuma linha passa. Falha fechada.
- **`with check`** — `using` sozinho protege só a leitura. Sem o `with check`, um `insert` com
  `estabelecimento_id` de outro tenant passa.

O `SET LOCAL app.tenant_id` é emitido por um hook no início de **toda** transação:

```java
// TenantAwareTransactionManager extends JpaTransactionManager
@Override
protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);
    UUID tenant = TenantContext.atual();
    if (tenant == null && !TenantContext.semTenantPermitido()) {
        throw new TenantNaoDefinidoException();                  // RN-INF-003
    }
    entityManager()
        .createNativeQuery("select set_config('app.tenant_id', :tenant, true)")
        .setParameter("tenant", tenant == null ? "" : tenant.toString())
        .getSingleResult();                                      // 'true' = LOCAL
}
```

**Por que sobrescrever `doBegin` e não usar um `@Aspect`** (decidido na implementação): um aspecto
teria de rodar *dentro* da transação, o que exige reordenar o `TransactionInterceptor` do Spring —
que por padrão usa `Ordered.LOWEST_PRECEDENCE` e portanto não admite ninguém depois dele sem
reconfigurar o `@EnableTransactionManagement`. É frágil e quebra em silêncio numa atualização do
Boot. `doBegin` engancha exatamente no início da transação, para todas elas, sem depender de ordem
de advice.

### Camada 3 — Teste

O que impede a garantia de apodrecer. Ver seção 15.

## 5. Fluxo principal

1. Requisição chega com JWT contendo `estabelecimentoId`.
2. `JwtAuthFilter` valida e abre o escopo de `TenantContext`.
3. Caso de uso abre transação.
4. Hook emite `set_config('app.tenant_id', <id>, true)` — **`true` = `LOCAL`**.
5. Queries rodam. RLS filtra tudo que escapou do `WHERE`.
6. Fim da transação: o `SET LOCAL` morre com ela. A conexão volta limpa ao pool.

## 6. Fluxos alternativos

**A1 — Job de sistema.** Não tem JWT. Abre `TenantContext` explicitamente, por estabelecimento,
em laço. **Nunca** roda sem tenant "porque é sistema".

**A2 — Flyway.** Conecta como owner e ignora RLS de propósito — migration precisa ver tudo.
Por isso a aplicação usa role diferente.

**A3 — Listener de `LISTEN/NOTIFY`.** Conexão dedicada fora do pool do Hikari, sem tenant. Não
lê tabela de negócio; só recebe payload de invalidação.

**A4 — Healthcheck.** `select 1`, sem tabela de negócio, sem tenant. É por isso que
`current_setting` leva o segundo argumento.

## 7. Regras aplicadas

| ID | Resumo | Garantida em |
|---|---|---|
| RN-INF-001 | Toda tabela de negócio tem `estabelecimento_id` | Teste que varre o schema |
| RN-INF-002 | Toda tabela de negócio tem RLS **e** FORCE | Teste que varre o schema |
| RN-INF-003 | Transação sem tenant falha | `TenantTransactionHook` |
| RN-INF-004 | Aplicação não é dona das tabelas | Migration + teste |

## 8. As quatro armadilhas

Cada uma faz a RLS não proteger **nada**, silenciosamente. Estão aqui porque as quatro são fáceis
de introduzir e nenhuma dá erro visível.

| Armadilha | Efeito | Correção |
|---|---|---|
| App conecta como dona da tabela | RLS ignorada em silêncio | Role `salao_app` + `FORCE ROW LEVEL SECURITY` |
| `current_setting('app.tenant_id')` sem o 2º argumento | Estoura `42704` em Flyway, healthcheck e job | `current_setting('app.tenant_id', true)` |
| `SET` em vez de `SET LOCAL` | Tenant fica grudado na conexão e vaza para o próximo que a pegar do pool | `set_config(..., true)` |
| Nenhum teste | Ninguém percebe até o segundo cliente | Seção 15 |

A terceira é a mais perigosa: funciona perfeitamente em desenvolvimento com um usuário só e
vaza em produção sob concorrência.

## 9. Dados

**Duas migrations, não uma** (ajuste da implementação): `V2` cria a role e a função; `V3` cria
`estabelecimento` e `auditoria`. A RLS precisa de uma raiz de tenant e de pelo menos uma tabela de
negócio para ser testável — sem elas, os testes de isolamento não teriam sobre o que rodar.

**`V2__tenant_e_rls.sql`:** cria a role `salao_app`, os grants, e uma função auxiliar
para aplicar RLS padrão a uma tabela — de forma que toda migration futura chame uma linha em vez
de repetir quatro.

```sql
create or replace function aplicar_rls_tenant(nome_tabela text) returns void as $$
begin
  execute format('alter table %I enable row level security', nome_tabela);
  execute format('alter table %I force row level security', nome_tabela);
  execute format('create policy tenant_isolado on %I using
                  (estabelecimento_id = current_setting(''app.tenant_id'', true)::uuid)',
                 nome_tabela);
  execute format('grant select, insert, update, delete on %I to salao_app', nome_tabela);
end;
$$ language plpgsql;
```

`V3__estabelecimento_e_auditoria.sql` cria a raiz do tenant (política sobre o próprio `id`) e a
trilha de auditoria (`aplicar_rls_tenant('auditoria')`, seguido de
`revoke update, delete` — trilha é append-only, e a permissão é a garantia, não a convenção).

**Duas conexões diferentes, e isso é o núcleo da rotina.** O Flyway conecta como *owner*
(`spring.flyway.user`) porque precisa criar tabela, role e política; a aplicação conecta como
`salao_app` (`spring.datasource.username`), que não é dona de nada. Se os dois usassem a mesma
role, o Postgres ignoraria a RLS e **todo teste de isolamento passaria sem provar nada**.

**Orçamento de queries:** +1 por transação (`set_config`). Aceito — é o preço do isolamento.

## 10. Efeitos colaterais

Nenhum evento. Afeta **toda** transação da aplicação, para sempre.

## 11. Casos de borda

| Situação | Comportamento | Erro |
|---|---|---|
| Transação sem tenant tocando tabela de negócio | Falha imediata | `TenantNaoDefinidoException` → 500 (é bug, não erro de usuário) |
| JWT sem `estabelecimentoId` | Rejeita autenticação | 401 |
| Token de tenant A com id de recurso do tenant B | RLS devolve zero linhas → 404 | `ER-INF-NAO_ENCONTRADO` |
| Migration nova sem `estabelecimento_id` | **Build quebra** | Teste de arquitetura |
| Migration nova sem RLS | **Build quebra** | Teste de arquitetura |
| Conexão devolvida ao pool | `SET LOCAL` já morreu com a transação | — |

## 12. Concorrência

O ponto crítico é o pool. Duas requisições de tenants diferentes pegam a mesma conexão em
sequência: sem `LOCAL`, a segunda herda o tenant da primeira. Teste dedicado com pool de
**tamanho 1** e duas requisições de tenants distintos — é a única forma de provocar isso de
propósito.

## 13. Observabilidade

| O quê | Nome | Alerta |
|---|---|---|
| Transação sem tenant | `tenant.ausente` (contador) | Qualquer ocorrência — é bug |
| `tenantId` em todo log | campo estruturado | — |
| Falha de política RLS | log em nível `error` com `traceId` | Qualquer ocorrência |

## 14. UX

Invisível ao usuário — e é assim que tem que ser. O único sintoma visível é 404 em recurso de
outro tenant, indistinguível de recurso inexistente. **Deliberado:** 403 confirmaria a existência.

## 15. Testes obrigatórios

Estes sete são o entregável real da rotina.

> **Status honesto em 28/08/2026:** os testes estão escritos e compilam, mas **ainda não foram
> executados** — o daemon do Docker não subiu (diálogo de aceite de licença do Docker Desktop
> pendente). Pela DoD do projeto, rotina só é `implementado` com teste de integração **passando**,
> então o status desta rotina segue `em-implementacao`. Marcar os itens abaixo só depois de
> `mvn verify` verde.

- [ ] `TenantIsolamentoIT.usuario_do_tenant_a_nao_le_auditoria_do_tenant_b`
- [ ] `TenantIsolamentoIT.query_sem_tenant_retorna_zero_linhas_e_nao_todas`
      _(o mais importante: prova que a falha é fechada, não aberta)_
- [ ] `TenantIsolamentoIT.conexao_reusada_do_pool_nao_herda_tenant_anterior`
      _(pool de tamanho 1, três leituras alternando A → B → A)_
- [ ] `TenantIsolamentoIT.transacao_sem_escopo_falha`
- [ ] `TenantIsolamentoIT.insert_com_tenant_alheio_e_bloqueado` _(prova o `with check`)_
- [ ] `TenantIsolamentoIT.aplicacao_nao_e_dona_das_tabelas`
- [ ] `SchemaIT.toda_tabela_de_negocio_tem_estabelecimento_id_rls_e_force`
      _(varre `pg_class` e `pg_policies`; quebra o build em migration nova esquecida)_

O último é o que faz a garantia sobreviver a seis meses de desenvolvimento. Sem ele, os outros
provam apenas que o isolamento funcionava no dia em que foram escritos.

O `maximum-pool-size: 1` está fixado em `AbstractPostgresIT` para **toda** a suíte de integração,
não só no teste de reuso: assim qualquer rotina futura que introduza vazamento por conexão falha
no próprio teste dela, não num teste distante de infraestrutura.

## 16. Como testar manualmente

1. Crie dois estabelecimentos e um agendamento em cada.
2. Autentique como usuário do tenant A.
3. `GET /api/v1/agendamentos/{id-do-tenant-B}` → **404**.
4. No banco, como `salao_app` e **sem** definir `app.tenant_id`:
   `select count(*) from agendamento;` → **0**, nunca o total.
5. Como owner, o mesmo select devolve tudo — é o comportamento esperado, e é por isso que a
   aplicação não conecta como owner.

## 17. Decisões e trade-offs

| Decisão | Alternativa | Por quê |
|---|---|---|
| RLS além do filtro na aplicação | Só filtro na aplicação | Um `WHERE` esquecido vaza tudo. RLS é a rede |
| `ScopedValue` | `ThreadLocal` | Virtual threads; `ThreadLocal` pesado é o que resta de problema depois do JEP 491 |
| Falhar sem tenant | Assumir um default | Default silencioso é como vazamento nasce |
| Função `aplicar_rls_tenant` | Repetir 4 comandos por tabela | Repetição é onde alguém esquece um |
| 404 para recurso de outro tenant | 403 | 403 confirma a existência |

## 18. Pendências

- [ ] **Login é cross-tenant e ainda não tem solução.** Autenticar exige achar o usuário pelo
      e-mail **antes** de saber o tenant, e a RLS bloqueia exatamente isso. Resolver em
      `RT-IAM-002`, por uma destas vias: função `SECURITY DEFINER` restrita à busca de
      credencial, e-mail globalmente único, ou login escopado por estabelecimento (subdomínio).
      **Não resolver afrouxando a política.**
- [ ] **Provisionar estabelecimento** (`RT-IAM-001`) é a outra operação legitimamente
      cross-tenant. Roda como owner ou via `SECURITY DEFINER`.
- [ ] `ResolvedorDeTenantPorCabecalho` é de dev/test. Fora desses perfis o resolvedor devolve
      sempre `null` e toda transação falha — barulhento de propósito, até `RT-IAM-002` entregar o
      resolvedor por JWT.
- [ ] Senha de `salao_app` em prod: hoje vem do placeholder Flyway `${senha_app}` via variável de
      ambiente. Antes do go-live, gerenciador de segredos.
- [ ] Avaliar `pg_advisory_lock` por tenant quando surgir a primeira necessidade de lock.
