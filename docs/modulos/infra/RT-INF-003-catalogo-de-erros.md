---
id: RT-INF-003
titulo: Catálogo de erros e handler global
modulo: infra
fase: 0
perfil: leve
status: implementado
depende_de: [RT-INF-001]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-08-29
---

# RT-INF-003 — Catálogo de erros e handler global

## 1. Objetivo

Um único lugar que traduz exceção em resposta HTTP, com código estável que o front possa mapear
para texto.

## 2. Contexto

Sem isto, cada controller monta o seu próprio erro, os formatos divergem e o front acaba fazendo
`if` sobre a mensagem — o que quebra na primeira vez que alguém melhora o texto. O código estável
é o que permite trocar a mensagem sem tocar no front, e traduzir depois sem reescrever nada.

## 3. Contrato

RFC 9457 (Problem Details), acrescido de `codigo` e `traceId`:

```json
{
  "type": "https://api.salao.app/erros/er-agd-conflito_horario",
  "title": "Horário indisponível",
  "status": 409,
  "detail": "A profissional Ana já tem atendimento das 10:00 às 11:00.",
  "codigo": "ER-AGD-CONFLITO_HORARIO",
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "parametros": { "profissional": "Ana", "inicio": "10:00", "fim": "11:00" },
  "campos": [{ "campo": "inicio", "codigo": "...", "mensagem": "..." }]
}
```

`parametros` alimenta a interpolação da mensagem no front (R-UX-20: a mensagem diz o que
aconteceu **e** o que fazer). **Nunca coloque PII em `parametros`** — o valor atravessa log e
telemetria.

## 4. Estrutura

| Classe | Papel |
|---|---|
| `CodigoDeErro` | Interface: `codigo()`, `status()`, `titulo()`. Cada módulo implementa num enum próprio |
| `ErroDeDominio` | Exceção de negócio, com código, parâmetros e campos |
| `CampoInvalido` | Um campo rejeitado, com o mesmo contrato de código estável |
| `ErrosDaInfra` | Os transversais. Erro de negócio mora no enum do módulo dono da regra |
| `ManipuladorGlobalDeErros` | `@RestControllerAdvice`: o único lugar que monta erro |

## 5. Decisões

**404 e não 403 para recurso de outro tenant.** `ErrosDaInfra.NAO_ENCONTRADO` cobre "não existe" e
"existe mas fora do seu escopo", deliberadamente indistinguíveis: 403 confirmaria a existência, o
que já é vazamento de informação.

**Erro inesperado nunca devolve a mensagem original.** Stack trace e detalhe de infraestrutura
(host, porta, nome de tabela) são informação útil para quem estiver sondando a API. O detalhe vai
para o log com o `traceId`; o cliente recebe "Erro interno." e o trace para citar no suporte.

**`TenantNaoDefinidoException` vira 500, não mensagem amigável.** É bug — um caso de uso rodou
fora do escopo de uma requisição autenticada (RN-INF-003). Tratar como erro de usuário faria
alguém achar que é comportamento esperado e conviver com ele.

## 6. Testes

- [x] `ManipuladorGlobalDeErrosTest.erro_de_dominio` — código e status corretos
- [x] `ManipuladorGlobalDeErrosTest.erro_inesperado_nao_vaza_detalhe`
- [x] `ManipuladorGlobalDeErrosTest.tenant_ausente_e_bug`

## 7. Pendências

- [ ] `traceId` hoje vem do MDC, que só é populado quando `RT-INF-008` (OpenTelemetry) entrar.
      Até lá o campo vai nulo — e é melhor nulo e honesto do que um valor inventado
- [ ] Teste de contrato com MockMvc quando existir o primeiro controller
- [ ] Handler de `OptimisticLockingFailureException` → `VERSAO_DESATUALIZADA` quando houver a
      primeira entidade com `@Version`
