---
id: RT-XXX-000
titulo:
modulo:
fase:
perfil: completo          # completo | leve
status: rascunho          # rascunho | especificado | em-implementacao | implementado | obsoleto
depende_de: []
permissoes: []
eventos: []
regras: []
atualizado_em:
---

# RT-XXX-000 — <título>

## 1. Objetivo
_Uma frase. Que problema de negócio resolve._

## 2. Contexto de negócio
_O que acontece hoje sem isso. Quem pediu. O que quebra se não existir._

## 3. Atores e permissões
| Ator | Permissão exigida | Escopo | Observação |
|---|---|---|---|

## 4. Pré-condições
- [ ]

## 5. Fluxo principal
1.

## 6. Fluxos alternativos
**A1 —**

## 7. Regras de negócio aplicadas
| ID | Resumo em uma linha | Garantida em |
|---|---|---|
_(enunciado completo em `regras.md` — não repita aqui)_

## 8. Contrato de API
**Request**
```http
```
**Response 2xx**
```json
```
**Erros**
| HTTP | Código | Quando |
|---|---|---|

## 9. Dados
**Tabelas tocadas:**
**Migration:** `V___`
**Índices exigidos:**
**Orçamento de queries:** _N — justificar cada uma_

## 10. Efeitos colaterais
| Efeito | Quando | Onde |
|---|---|---|
_(eventos publicados, movimento de estoque, lançamento no razão, notificação enviada)_

## 11. Casos de borda e erros
| Situação | Comportamento esperado | HTTP | Código |
|---|---|---|---|

## 12. Concorrência e idempotência
_Dois cliques no mesmo botão. Duas instâncias. Webhook repetido. Versão desatualizada._

## 13. Observabilidade
| O quê | Tipo | Nome | Alerta |
|---|---|---|---|

## 14. UX e front
- **Estados de tela:** carregando · vazio · erro · sucesso
- **Atalho de teclado:**
- **Feedback:** otimista? undo? confirmação?
- **Texto exibido no erro:** _por código, não por `detail`_

## 15. Testes obrigatórios
- [ ] `<Classe>IT.caminho_feliz`
- [ ] `<Classe>IT.viola_RN_XXX_retorna_erro`
- [ ] `<Classe>IT.usuario_de_outro_tenant_recebe_404`
- [ ] `<Classe>IT.orcamento_de_queries_respeitado`

## 16. Como testar manualmente
1.

## 17. Decisões e trade-offs
| Decisão | Alternativa descartada | Por quê |
|---|---|---|

## 18. Pendências
- [ ]
