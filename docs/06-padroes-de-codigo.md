# Padrões de código

## Pacotes

```
br.com.<nome>/
├── shared/            Money, TenantContext, IDs, erros, paginação, Relogio
├── iam/
│   ├── api/           interface + DTO consumidos por outros módulos
│   └── internal/
│       ├── domain/        agregado, VO, política, evento, port
│       ├── application/   um caso de uso por classe
│       ├── infra/         adapter JPA/S3/HTTP, entidade JPA
│       └── web/           controller, DTO de request/response
├── equipe/  cliente/  catalogo/  agenda/  atendimento/  financeiro/
├── estoque/  conversacional/  notificacao/  arquivos/
```

Só `api/` é visível fora do módulo. `internal/` é invisível — e o Spring Modulith reprova o build
se alguém importar.

## Nomes

| Elemento | Padrão | Exemplo |
|---|---|---|
| Caso de uso | `<Verbo><Substantivo>UseCase` | `CriarAgendamentoUseCase` |
| Entrada do caso de uso | `<Verbo><Substantivo>Command` | `CriarAgendamentoCommand` |
| Saída | `<Substantivo>Resultado` ou record próprio | `AgendamentoCriado` |
| Request HTTP | `<Verbo><Substantivo>Request` | `CriarAgendamentoRequest` |
| Response HTTP | `<Substantivo>Response` | `AgendamentoResponse` |
| Port | `<Substantivo>Repositorio`, `<Substantivo>Gateway` | `AgendamentoRepositorio` |
| Adapter | `Jpa<Port>`, `S3<Port>` | `JpaAgendamentoRepositorio` |
| Entidade JPA | `<Agregado>Entity` | `AgendamentoEntity` |
| Política de domínio | `<Assunto>Politica` | `ComissaoPolitica` |
| Erro de domínio | `<Assunto>Exception` | `ConflitoHorarioException` |
| Teste de integração | `<Assunto>IT` | `CriarAgendamentoIT` |

Domínio em **português**. Bibliotecas e anotações ficam em inglês porque não são nossas.
Proibido `Service`, `Manager`, `Helper`, `Util`, `Handler` genéricos.

## Estrutura do caso de uso

```java
public class CriarAgendamentoUseCase {

    private final AgendamentoRepositorio repositorio;
    private final CatalogoApi catalogo;          // API pública de outro módulo
    private final Relogio relogio;               // nunca Instant.now() direto

    @Transactional
    @PreAuthorize("hasAuthority('agenda:write:all') or hasAuthority('agenda:write:own')")
    public AgendamentoCriado executar(CriarAgendamentoCommand cmd) {
        // 1. carrega o que precisa (API de outro módulo, nunca join)
        // 2. checagem de posse e escopo
        // 3. delega a decisão ao domínio — aqui não tem regra de negócio
        // 4. persiste
        // 5. registra evento (outbox cuida da saída do processo)
    }
}
```

O caso de uso **orquestra**. Se ele tem `if` de regra de negócio, a regra está no lugar errado:
ela pertence ao agregado ou a uma política.

## Datas e relógio

Nunca `Instant.now()`, `LocalDate.now()` ou `new Date()` espalhado. Injete `Relogio` (port). É o
que torna testável "cancelamento com menos de 24h cobra taxa" sem `Thread.sleep`.

Conversão de fuso **só na borda**, com o fuso do estabelecimento:

```java
ZoneId fuso = estabelecimento.fuso();            // nunca constante
LocalDate diaCivil = instante.atZone(fuso).toLocalDate();
```

## Dinheiro

`BigDecimal` com escala 4 e `RoundingMode.HALF_UP` explícito em toda divisão. `Money` do `shared`
encapsula isso — não faça aritmética de `BigDecimal` solta. Nunca `equals` para comparar valor
(`compareTo() == 0`).

Rateio de desconto entre itens: o último item absorve a diferença de arredondamento, para que a
soma bata exatamente. Sem isso, um centavo se perde e o fechamento não fecha.

## Erros

Domínio lança exceção de domínio com **código estável**. `@RestControllerAdvice` traduz para o
formato de `07-contratos-de-api.md`. O controller nunca monta erro na mão.

```java
throw new ConflitoHorarioException(profissionalId, periodo);   // → ER-AGD-CONFLITO_HORARIO, 409
```

Violação de constraint do banco é traduzida **pelo nome da constraint**:

```java
if (e.getConstraintName().equals("bloco_sem_sobreposicao_profissional"))
    throw new ConflitoHorarioException(...);
```

## Testes

| Nível | O quê | Como |
|---|---|---|
| Unitário | Regra de domínio pura | JUnit 5, sem Spring, sem banco |
| Integração | Caso de uso ponta a ponta | Testcontainers, **Postgres real**, nunca H2 |
| Arquitetura | Fronteira, camada, tenant, RLS | ArchUnit + Modulith |
| Contrato | OpenAPI não quebrou | springdoc + diff no CI |
| E2E | 5 fluxos críticos | Playwright |

Nome do teste é uma frase: `nao_permite_agendamento_sobreposto_do_mesmo_profissional`.

Todo caso de uso tem, no mínimo: caminho feliz · violação de cada regra listada na doc ·
tentativa de acesso cross-tenant · concorrência quando houver invariante disputada.

Cobertura: 80%+ no pacote `domain`. Número global não é meta.

## Migrations

`V<numero>__<descricao_em_snake_case>.sql`. Uma migration por PR.
**Migration aplicada nunca é editada** — corrija com uma nova.
Migration destrutiva (`drop column`) exige duas etapas em releases diferentes: parar de usar,
depois remover.

## Commits

```
<tipo>(<modulo>): <resumo no imperativo>

Refs: RT-AGD-002
```

Tipos: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`.
Todo commit de feature referencia a rotina. Rastro de "por que este código existe" é a doc, não
a mensagem de commit.
