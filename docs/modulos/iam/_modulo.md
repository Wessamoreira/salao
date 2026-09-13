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
| `Usuario` | E-mail único globalmente · senha em Argon2id · bloqueio progressivo por falhas · telefone único por salão (RN-IAM-022) |
| `Perfil` | (RT-IAM-007) |

## API pública (`iam/api`)

| Operação | Assinatura | Consumido por |
|---|---|---|
| Configuração do tenant | `EstabelecimentoApi.configuracao(UUID)` | todos |
| Fuso do tenant | `EstabelecimentoApi.fusoDe(UUID)` | `agenda`, `financeiro`, `estoque` |
| Catálogo de permissões | `Permissao.*` (constantes) | todos, no `@PreAuthorize` |
| Trilha de auditoria | `AuditoriaApi.registrar(RegistroDeAuditoria)` | todos os módulos |
| Perfil e token | `Perfil`, `TokenDeAcesso` | `web`, `conversacional` |
| Teto de alçada | `LimitesDoUsuarioApi.descontoMaximoPercentual(UUID)` | `atendimento` (RT-ATD-004) |
| Usuário efetivo do agente | `UsuarioEfetivoApi.identificarPorTelefone(String)` · `emNomeDe(String, Function)` | `conversacional` (RT-CNV-004) |

> `LimitesDoUsuarioApi` devolve o **teto**, nunca o perfil. Quem consome não deve saber que alçada
> se decide por perfil — sabendo, repetiria a tabela do lado de fora, e a segunda cópia é a que
> continua autorizando depois que a primeira mudar.
>
> O perfil sai do **banco**, não do token: o token é autocontido e vale até expirar, então um
> rebaixamento no meio do expediente ainda apareceria como alçada antiga. Para desenhar menu isso
> passa; para autorizar dinheiro, não.

> `UsuarioEfetivoApi` é a única forma de o agente agir. Ela recebe o **telefone** e resolve na
> hora — não há como guardar um usuário efetivo e usá-lo depois (RN-IAM-019).

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
| `auditoria` | — (append-only) | sim |

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
| RT-IAM-008 | Auditoria append-only | 0 | implementado |
| [RT-CNV-002](RT-CNV-002-usuario-efetivo.md) | Usuário efetivo pelo telefone — rotina do plano conversacional, mora aqui | 4 | implementado |

## Dependências

Só `shared`. É o módulo mais baixo da pilha — se algum dia ele precisar de outro módulo de
negócio, a fronteira está errada.
