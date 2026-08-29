# ADR-0002: Multi-tenant por coluna + Row Level Security desde o dia 0

- **Status:** aceita · **Data:** 2026-08-28

## Contexto

O produto nasce para um salão, com intenção declarada de servir outros. Isolamento colado depois
significa revisar 100% das queries — e a primeira que escapar vaza dado de um cliente para outro,
o que é irrecuperável comercialmente.

## Opções consideradas

| Opção | Prós | Contras | Custo de reverter |
|---|---|---|---|
| Banco por tenant | Isolamento físico | N migrations, N pools, N backups. Insustentável para um dev | Alto |
| Schema por tenant | Isolamento razoável | Migration multiplicada; conexão precisa trocar `search_path` | Médio |
| **Coluna + RLS** | Uma migration, um pool, um backup; RLS como rede de segurança | Exige disciplina e teste | Altíssimo se adiado |
| Só coluna, sem RLS | Simples | Um `WHERE` esquecido vaza tudo | — |

## Decisão

`estabelecimento_id` em toda tabela de negócio, RLS habilitada **e forçada**, com
`SET LOCAL app.tenant_id` no início de cada transação.

## Consequências

**Positivas.** Uma migration serve todos. RLS pega o `WHERE` esquecido. O caminho para banco
dedicado a um cliente grande continua aberto (é um dump filtrado).

**Negativas, assumidas.** Quatro armadilhas conhecidas precisam de código explícito e teste, ou a
RLS não protege nada: conectar como dono da tabela (RLS ignorada em silêncio), `current_setting`
sem o segundo argumento (estoura em Flyway e healthcheck), `SET` em vez de `SET LOCAL` (tenant
grudado na conexão do pool) e ausência de teste. As quatro estão documentadas em
`05-seguranca-multitenancy-lgpd.md` e cobertas por testes que varrem o schema e quebram o build.
