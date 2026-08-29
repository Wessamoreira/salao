# ADR-0008: Autenticação própria com Spring Security

- **Status:** aceita · **Data:** 2026-08-28

## Contexto

O sistema precisa de login, perfis, MFA e um bot que age em nome de usuários. A alternativa
padrão seria um provedor de identidade dedicado.

## Opções consideradas

| Opção | Prós | Contras |
|---|---|---|
| Keycloak | Pronto, com MFA e federação | Mais um contêiner comendo RAM da VM; upgrade é evento; SSO corporativo não é requisito |
| Auth0 / Cognito | Sem operação | Custo por usuário; dependência externa numa função crítica |
| **Spring Security próprio** | Sem infra nova; controle total sobre capabilities e escopo `:own` | MFA, rotação e detecção de reuso ficam por sua conta |

## Decisão

Auth próprio: JWT curto (15 min) + refresh rotativo (30 dias) com detecção de reuso, Argon2id,
MFA TOTP para `ADMIN` e para quem tem `financeiro:read:all`.

## Consequências

**Positivas.** O contrato `/me/capabilities` — que é o que elimina regra de negócio do front — fica
sob controle total. O bot herdando permissões do usuário efetivo também.

**Negativas, assumidas.** Detecção de reuso de refresh, lockout progressivo e recuperação de MFA
são código a escrever e testar. `Argon2PasswordEncoder` exige BouncyCastle no classpath.

**Revisitar quando** houver cliente exigindo SSO corporativo.
