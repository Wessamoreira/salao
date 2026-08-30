# Módulo `iam`

- **Sigla:** IAM · **Fase de introdução:** 0 · **Status:** em construção

## Responsabilidade

Quem é o estabelecimento, quem são os usuários e o que cada um pode fazer.

**Não é responsabilidade:** dados do profissional como PJ (contrato, comissão, jornada) — isso é
`equipe`. Um usuário faz login; um profissional executa serviço; nem todo profissional tem login.

## Agregados

| Agregado | Invariantes que protege |
|---|---|
| `Estabelecimento` | Fuso é IANA válido · moeda é ISO 4217 · nome não vazio |
| `Usuario` | E-mail único globalmente · senha em Argon2id · bloqueio progressivo por falhas |
| `Perfil` | (RT-IAM-007) |

## API pública (`iam/api`)

| Operação | Assinatura | Consumido por |
|---|---|---|
| Configuração do tenant | `EstabelecimentoApi.configuracao(UUID)` | todos |
| Fuso do tenant | `EstabelecimentoApi.fusoDe(UUID)` | `agenda`, `financeiro`, `estoque` |
| Catálogo de permissões | `Permissao.*` (constantes) | todos, no `@PreAuthorize` |
| Perfil e token | `Perfil`, `TokenDeAcesso` | `web`, `conversacional` |

`ConfiguracaoDoEstabelecimento`, `BaseDeComissao`, `PeriodicidadeDeFechamento` e `ErrosDoIam` são
parte do contrato. Nenhum módulo consulta a tabela `estabelecimento` diretamente.

## Tabelas

| Tabela | Agregado | RLS |
|---|---|---|
| `estabelecimento` | Estabelecimento | sim — policy pelo próprio `id` |
| `usuario` | Usuario | sim |
| `refresh_token` | Sessao | sim |
| `mfa_credencial` | Usuario | sim |
| `mfa_codigo_recuperacao` | Usuario | sim |

## Rotinas

| ID | Título | Fase | Status |
|---|---|---|---|
| RT-IAM-001 | Provisionar estabelecimento | 0 | implementado |
| RT-IAM-002 | Login com Argon2id e bloqueio progressivo | 0 | implementado |
| RT-IAM-003 | Refresh rotativo com detecção de reuso | 0 | implementado |
| RT-IAM-004 | Logout e revogação de sessão | 0 | implementado |
| RT-IAM-005 | MFA TOTP | 0 | implementado |
| RT-IAM-006 | `/me/capabilities` e autorização por permissão | 0 | implementado |
| RT-IAM-007 | CRUD de usuário e atribuição de perfil | 0 | implementado |
| RT-IAM-008 | Auditoria append-only | 0 | rascunho |

## Dependências

Só `shared`. É o módulo mais baixo da pilha — se algum dia ele precisar de outro módulo de
negócio, a fronteira está errada.
