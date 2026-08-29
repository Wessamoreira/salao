# ADR-0005: `cliente` e `equipe` são módulos próprios

- **Status:** aceita · **Data:** 2026-08-28

## Contexto

O desenho inicial listava dez módulos e nenhum era dono de `Cliente` — que é referenciado por
`agenda`, `atendimento`, `financeiro` e `conversacional`. `Jornada` estava em `catalogo`, e
`Profissional` não tinha lugar declarado.

Agregado sem dono acaba duplicado: nasce uma tabela de cliente em `agenda` e outra em
`atendimento`, e a partir daí ninguém sabe qual é a verdadeira.

## Opções consideradas

| Opção | Prós | Contras |
|---|---|---|
| `Cliente` dentro de `atendimento` | Menos módulos | Agenda passa a depender de atendimento sem motivo; inverte a direção natural |
| `Profissional` dentro de `catalogo` | Menos módulos | Profissional tem CNPJ, contrato, comissão e ciclo de vida — cadência de mudança completamente diferente de `Servico` |
| **Módulos `cliente` e `equipe`** | Dono claro; dependências na direção certa | Dois módulos a mais |

## Decisão

- `cliente`: Cliente, contatos, preferências e ficha técnica (dado sensível, com permissão própria).
- `equipe`: Profissional, vínculo PJ, jornada, exceções de jornada, habilidades.
- `catalogo` fica só com Serviço, Preço e Recurso.
- `iam` fica só com identidade e autorização — `Usuario` é quem faz login, `Profissional` é quem
  executa serviço, e nem todo profissional tem login.

## Consequências

**Positivas.** A ficha técnica do cliente (alergia, histórico de química) fica isolada num módulo
que pode ter criptografia de campo e permissão dedicada, sem contaminar o resto. `agenda` depende
de `equipe` e `cliente` na direção natural, nunca o contrário.

**Negativas.** Duas fronteiras a mais para manter, e a separação `Usuario`/`Profissional` exige um
vínculo explícito que precisa ser resolvido na `RT-EQP-001`.
