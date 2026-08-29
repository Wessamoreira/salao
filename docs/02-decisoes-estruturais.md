# Decisões estruturais

Oito decisões que definem o resto. Mudar depois custa reescrita. Cada uma tem ADR própria.

| # | Decisão | Escolha | Motivo | Custo de reverter | ADR |
|---|---|---|---|---|---|
| D1 | Multi-tenant no dia 0? | **Sim.** `estabelecimento_id` em toda tabela + RLS | Isolamento colado depois vira vazamento entre clientes | Altíssimo — toca 100% das queries | [0002](adr/0002-multi-tenant-rls.md) |
| D2 | Provedor WhatsApp | **Cloud API oficial da Meta** | Biblioteca não-oficial derruba o número do salão. Não dá para vender produto em cima disso | Médio — troca de adapter | [0006](adr/0006-whatsapp-cloud-api.md) |
| D3 | Adquirente | **Definir qual antes de prometer automação** | Viabilidade depende de haver API/webhook. Plano B é importar CSV/OFX | Baixo, com anticorruption layer | [0007](adr/0007-conciliacao-adquirente.md) |
| D4 | Cache | **Caffeine local + invalidação por `LISTEN/NOTIFY`** | Custo zero de infra e resolve o problema real, que é coerência entre instâncias | Baixo | [0004](adr/0004-cache-caffeine-notify.md) |
| D5 | Identidade | **Auth próprio** com Spring Security (JWT + refresh rotativo) | Keycloak é mais um contêiner comendo RAM. SSO corporativo não é requisito | Médio | [0008](adr/0008-auth-proprio.md) |
| D6 | Fuso e dinheiro | `timestamptz` UTC + fuso **por estabelecimento**; `BigDecimal`/`numeric(19,4)` | `double` em dinheiro e data local em agenda são bugs garantidos | Altíssimo | [0009](adr/0009-tempo-e-dinheiro.md) |
| D7 | Modelo da agenda | **Blocos por agendamento**, não uma janela única | Sem isso, tempo de processamento e encaixe na pausa são impossíveis | Altíssimo — muda o schema da agenda | [0003](adr/0003-agenda-em-blocos.md) |
| D8 | Fronteira de módulo | Monólito modular verificado por teste; `cliente` e `equipe` são módulos próprios | `Cliente` não tinha dono no desenho original; `Profissional` não é catálogo | Médio | [0001](adr/0001-monolito-modular.md), [0005](adr/0005-modulos-cliente-equipe.md) |

## Correções aplicadas sobre o rascunho inicial

Registradas aqui para que ninguém "reintroduza" o desenho antigo achando que é melhoria.

### C1 — Agenda com janela única era incompatível com tempo de processamento
O rascunho definia serviço com blocos `ATIVO → PAUSA → ATIVO` e profissional liberado na pausa,
mas a exclusion constraint usava **um** `tstzrange` por agendamento. As duas coisas não coexistem:
com um range só, o encaixe na pausa é rejeitado pelo banco. Corrigido em D7 e ADR-0003.

Efeito colateral resolvido de brinde: durante a química o cliente ocupa a **cadeira** mas não o
**profissional** — janelas diferentes no mesmo agendamento, que só o modelo de blocos expressa.

### C2 — Fuso fixo em `America/Sao_Paulo` contradizia o multi-tenant
D1 existe para vender a outros estabelecimentos; D6 fixava um fuso. Agora o fuso é coluna do
`estabelecimento` (IANA) e toda conversão de borda usa o fuso do tenant da requisição. Afeta
agenda do dia, corte do fechamento e os jobs diários de validade.

### C3 — "Zero join entre módulos" não tinha válvula para relatório
O relatório de repasse por PJ item a item cruza `atendimento` + `financeiro` + `equipe`. Sem
exceção nomeada, ou vira N+1 de chamadas em processo, ou alguém quebra a regra em silêncio.
A válvula é o schema `relatorio` (views e materialized views), documentada em `03-arquitetura.md`
e codificada como exceção no teste de ArchUnit.

### C4 — Preço não era congelado na comanda
Havia evento `PrecoAlterado` e catálogo cacheado, mas nada dizia que o item de comanda guarda o
preço e o percentual aplicados. Sem snapshot, reprocessar um fechamento passado dá número
diferente do repasse já pago. Agora é invariante do agregado `Comanda` (RN-ATD-010).

### C5 — Pagamento era 1:1 com a comanda
Cliente paga metade no cartão e metade no pix o tempo todo. `Pagamento` é 1:N com `Comanda`,
e a conciliação casa pagamento a pagamento, não comanda a comanda.

### C6 — RLS sem `FORCE` não protege
RLS é ignorada para o dono da tabela. A aplicação conecta com role própria, não-dona, e as
tabelas têm `FORCE ROW LEVEL SECURITY`. Detalhes em `05-seguranca-multitenancy-lgpd.md`.

### C7 — `NOTIFY` no `AFTER_COMMIT` abre janela de cache velho
O Postgres já entrega o `NOTIFY` só no commit e descarta no rollback. Emitir depois do commit,
pela aplicação, cria a janela "commit passou, processo morreu, ninguém invalidou". O `NOTIFY`
vai **dentro** da transação.

## Decisões deliberadamente adiadas

| Assunto | Gatilho para decidir |
|---|---|
| Redis | Mais de 3–4 instâncias, ou hit rate local < 70%, ou rate limit distribuído preciso |
| Particionamento de tabela | Quando `agendamento` ou `movimento_estoque` doer de verdade em `EXPLAIN` |
| MCP como transporte de tools | Fase 4 concluída; MCP não é pré-requisito do bot |
| Extração de microserviço | Nunca, até haver equipe separada. A fronteira é o que importa, não o deploy |
| Event sourcing | Não. O livro-razão append-only já dá a auditabilidade necessária |
