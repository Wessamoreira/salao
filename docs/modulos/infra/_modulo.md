# Transversal `infra` (sigla INF)

Não é um módulo de negócio: é o conjunto de rotinas transversais que sustentam todos os módulos.
Fisicamente mora em `shared/` e na configuração da aplicação.

## Responsabilidade

Contexto de tenant, isolamento, erros, paginação, dinheiro, relógio, idempotência, outbox, cache,
observabilidade e pipeline.

**Não é responsabilidade:** nenhuma regra de negócio. Se aparecer uma aqui, ela pertence a um
módulo.

## Rotinas

| ID | Título | Fase | Status |
|---|---|---|---|
| RT-INF-001 | Bootstrap do projeto | 0 | implementado |
| RT-INF-002 | Contexto de tenant, RLS e teste de vazamento | 0 | em-implementacao |
| RT-INF-003 | Catálogo de erros e handler global | 0 | implementado |
| RT-INF-004 | Money, paginação keyset e relógio | 0 | implementado |
| RT-INF-005 | Idempotência de escrita | 0 | implementado |
| RT-INF-006 | Outbox transacional | 0 | implementado |
| RT-INF-007 | Cache local com invalidação por LISTEN/NOTIFY | 0 | implementado |
| RT-INF-008 | Observabilidade | 0 | implementado |
| RT-INF-009 | CI/CD, imagem e deploy | 0 | implementado-parcial |
| RT-INF-010 | Shell do front | 0 | implementado |

## Invariantes que este transversal garante

| ID | Invariante | Garantida por |
|---|---|---|
| RN-INF-001 | Toda tabela de negócio tem `estabelecimento_id` | Teste de arquitetura que varre o schema |
| RN-INF-002 | Toda tabela de negócio tem RLS habilitada **e forçada** | Teste de arquitetura que varre o schema |
| RN-INF-003 | Toda transação que toca tabela de negócio tem `app.tenant_id` definido | `TenantTransactionHook`; falha se ausente |
| RN-INF-004 | A aplicação nunca conecta como dona das tabelas | Migration cria role separada; teste verifica |
| RN-INF-005 | Dinheiro nunca é `double` ou `float` | Teste de arquitetura |
| RN-INF-006 | Instante nunca vem de `Instant.now()` direto | Teste de arquitetura; usar o port `Relogio` |
| RN-INF-011 | O front nunca dispara duas renovações de sessão ao mesmo tempo | `http.ts`; `http.test.ts` — paralelas revogariam a família (RN-IAM-007) |
| RN-INF-012 | `X-Forwarded-For` só é considerado atrás de proxy declarado | `EnderecoDoCliente`; `EnderecoDoClienteTest` — confiar nele sem proxy anula o limite |
| RN-INF-013 | Nenhuma senha dentro de SQL versionado | `SchemaIT.migrations_nao_carregam_senha` — com `log_statement` ligado ela vai para o log em texto claro |
| RN-INF-007 | Operação cross-tenant usa a role `salao_manutencao`, nunca `salao_app` | Policy `manutencao` + [ADR-0010](../../adr/0010-role-de-manutencao.md) |
| RN-INF-008 | Registro de idempotência commita na mesma transação do efeito de negócio | `IdempotenciaJdbc`; `IdempotenciaIT.falha_no_negocio_libera_a_chave` |
| RN-INF-009 | Evento carrega ID, nunca PII — o outbox não tem RLS | `EventoDeDominio`; `ArquiteturaTest.listener_assincrono_so_recebe_evento_de_dominio` |
| RN-INF-010 | Toda chave de cache começa pelo tenant | `GeradorDeChaveComTenant`; `CacheIT.chave_inclui_o_tenant` |

## Nota sobre o front

`RT-INF-010` vive em `frontend/`, fora da árvore Java. Os tokens de design são compartilhados por
symlink com `design/tokens.css` — a mesma fonte que alimenta o canvas de design, para que produto
e desenho não divirjam.
