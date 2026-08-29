# ADR-0010: Uma terceira role, só para manutenção cross-tenant

- **Status:** aceita · **Data:** 2026-08-29
- **Rotinas afetadas:** RT-INF-005, RT-INF-006, RT-IAM-008, e toda rotina de retenção

## Contexto

Várias tarefas prometidas pela documentação são, por natureza, **cross-tenant**: purgar
idempotência vencida, expurgar o outbox concluído, aplicar retenção em auditoria e em conversas
de IA. Nenhuma delas pertence a um estabelecimento — todas atravessam todos.

Isso colide de frente com a RLS, que é justamente o que impede atravessar estabelecimentos.

## Opções consideradas

| Opção | Prós | Contras |
|---|---|---|
| Dar poder cross-tenant a `salao_app` | Nenhuma infraestrutura nova | Desfaz o que a RLS existe para garantir. Uma query esquecida volta a poder ler tudo |
| Rodar manutenção como *owner* | Sem role nova | Exigiria policy permissiva para o owner em toda tabela, o que torna o `FORCE ROW LEVEL SECURITY` decorativo e reabre o cenário "app apontado para as credenciais de owner" |
| Flag de sessão (`app.manutencao = 'on'`) na policy | Simples de escrever | **Qualquer conexão pode definir um GUC**, inclusive a da aplicação. A "permissão" seria só uma convenção |
| Função `SECURITY DEFINER` | Escopo estreito | Com `FORCE RLS`, a função ainda é filtrada, a menos que o dono tenha `BYPASSRLS` — atributo que exige superusuário e reintroduz o problema anterior |
| **Role `salao_manutencao` com policy própria** | Permissão é de quem se conecta, não de quem lembra de setar variável. `salao_app` nunca casa com a policy | Uma role e um `DataSource` a mais |

## Decisão

Três roles, cada uma com um trabalho:

| Role | Faz | RLS |
|---|---|---|
| `salao_owner` | DDL, via Flyway | Irrelevante — DDL não é filtrado por policy |
| `salao_app` | Todo o tráfego da aplicação | Só o próprio tenant, via `tenant_isolado` |
| `salao_manutencao` | Purga e retenção | Policy `manutencao` (`using (true)`) nas tabelas que a declaram |

A elegibilidade é explícita por tabela: `permitir_manutencao('<tabela>')`. Uma tabela sem essa
chamada é invisível para a manutenção — é preciso decidir liberar, não decidir bloquear.

## Consequências

**Positivas.** A permissão passa a ser propriedade de quem se conecta, verificável em
`pg_policies`, e não uma convenção que depende de alguém lembrar. `salao_app` nunca ganha poder
cross-tenant, então o `FORCE ROW LEVEL SECURITY` continua valendo de verdade. E fica um padrão
pronto para toda tarefa de retenção que a documentação já promete.

**Negativas, assumidas.** Mais uma credencial para gerenciar e rotacionar, e mais um `DataSource`
no processo (pool de 2 — manutenção é periódica e serial). Quem escrever uma tarefa de manutenção
precisa lembrar de chamar `permitir_manutencao` na migration; esquecer produz uma purga que
apaga zero linhas em silêncio.

> **Pendência conhecida:** falta um teste que quebre o build quando uma tabela com retenção
> prometida não declarou `permitir_manutencao`. Sem ele, o esquecimento acima é silencioso — o
> mesmo modo de falha que o `SchemaIT` já cobre para `estabelecimento_id` e RLS.

**Revisitar quando** a manutenção precisar de mais do que `select` e `delete`, ou quando surgir
uma tarefa que precise escrever cross-tenant — o que seria sinal de um problema de modelagem, não
de permissão.
