---
id: RT-INF-012
titulo: Endurecimento — timeouts, actuator e segredo fora do SQL
modulo: infra
fase: 0
perfil: completo
status: implementado
depende_de: [RT-INF-005, RT-INF-008]
permissoes: []
eventos: []
regras: [RN-INF-013]
atualizado_em: 2026-08-30
---

# RT-INF-012 — Endurecimento

Fecha os itens 9, 10 e a segunda parte do 6 da auditoria de segurança
(`docs/15-checklist-de-seguranca.md`).

## 1. Timeouts vivem na role, não no cliente

**Fecha uma promessa que eu tinha escrito e não cumprido.** `RT-INF-005` diz, sobre a segunda
requisição idempotente concorrente: *"a segunda requisição espera o tempo da primeira, e
`lock_timeout` limita o pior caso"*. **Esse `lock_timeout` não existia.** O custo declarado
aceitável estava sem teto — e "espera" sem limite não é um custo, é uma falha esperando acontecer.

| Role | `statement_timeout` | `lock_timeout` | `idle_in_transaction` |
|---|---|---|---|
| `salao_app` | 30s | **5s** | 60s |
| `salao_manutencao` | 10min | 30s | — |

**Por que na role e não no cliente:** no cliente, o valor depende de alguém lembrar de configurá-lo
em cada ambiente. Na role, ele acompanha a credencial — inclusive um `psql` aberto às pressas
durante um incidente, que é exatamente quando alguém roda um `SELECT` sem `WHERE` numa tabela
grande.

Manutenção tem limite próprio e mais largo: purga de auditoria com anos de registro leva mais que
30s legitimamente. Mais largo, mas ainda finito.

**O `lock_timeout` produz um erro novo**, e ele não podia virar 500. `CannotAcquireLockException`
vira 409 `ER-INF-OPERACAO_EM_ANDAMENTO` com orientação de repetir: é estado transitório, e 500
diria "algo quebrou" quando nada quebrou.

## 2. RN-INF-013 — nenhuma senha dentro de SQL versionado

`alter role salao_app password 'x'` grava o segredo **dentro do próprio comando**. Com
`log_statement = 'ddl'` ou `'all'`, ele vai para o log do servidor em texto claro — e log de banco
costuma ser copiado, arquivado e lido por mais gente do que quem tem acesso ao segredo.

As migrations passaram a criar as roles **sem senha**. A senha é definida uma vez por ambiente,
fora da migration, por quem provisiona — [`runbook/provisionar-banco.md`](../../runbook/provisionar-banco.md).

O teste `SchemaIT.migrations_nao_carregam_senha` varre os arquivos e reprova o build.

> **Editei migrations já escritas**, contrariando a regra do projeto. É exceção justificada:
> nenhum ambiente persistente existe ainda, e a regra existe para proteger bancos implantados. Se
> houvesse um, o caminho seria uma migration nova rotacionando a senha — e o log antigo já teria
> vazado.

## 3. Actuator fora do alcance da rede

`management.server.address` passa a `127.0.0.1` por padrão: o actuator não é alcançável de fora da
máquina.

Health e métrica precisam ser lidos por sonda de contêiner e por scraper, e **nenhum dos dois
carrega token** — então a proteção não pode ser autenticação. Passa a ser **alcance**.

Expor numa interface privada é decisão de quem opera, feita por variável, com o firewall que a
acompanha. Nunca na pública.

## 4. Testes

- [x] `SchemaIT.roles_tem_timeouts` — os limites estão nas roles, com valores distintos por papel
- [x] `SchemaIT.migrations_nao_carregam_senha` — varre os `.sql` e reprova senha em comando

## 5. O que a implementação revelou

**Meu primeiro teste de senha reprovou o próprio comentário que explica a regra.** Ele procurava
`password '` no arquivo inteiro, e o `V2` explica em prosa por que aquilo não se faz. Um teste que
reprova a explicação da regra é um teste ingênuo — passou a ignorar linhas de comentário.

É pequeno, mas ilustra algo que vale generalizar: **verificação textual sobre código-fonte precisa
saber o que é código e o que é prosa**, senão vira ruído que alguém desliga.

## 6. Pendências

- [ ] Rotação das credenciais: o runbook cria, não troca. Um procedimento de rotação (sem
      janela de indisponibilidade) ainda não existe
- [ ] `pg_stat_statements` ligado, prometido em `08-dados` e ainda ausente — sem ele não há como
      saber qual query está perto do `statement_timeout` antes de estourar
- [ ] Verificar em ambiente real que `MANAGEMENT_ADDRESS=127.0.0.1` não quebra o scraping do
      Prometheus; se quebrar, a saída é túnel ou interface privada com firewall, nunca a pública
