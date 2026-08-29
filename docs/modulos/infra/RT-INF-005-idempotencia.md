---
id: RT-INF-005
titulo: Idempotência de escrita
modulo: infra
fase: 0
perfil: completo
status: implementado
depende_de: [RT-INF-002, RT-INF-003, RT-INF-004]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-08-29
---

# RT-INF-005 — Idempotência de escrita

## 1. Objetivo

Garantir que uma escrita repetida — pelo mesmo cliente, com a mesma chave — produza o efeito de
negócio **no máximo uma vez**.

## 2. Contexto de negócio

Três origens repetem requisição por desenho, não por defeito:

- A **Meta reenvia webhooks** do WhatsApp quando não recebe confirmação a tempo. Sem idempotência,
  um "marca quarta 10h" vira dois agendamentos.
- A **recepção clica duas vezes** no botão quando a resposta demora. É o comportamento normal de
  quem está com o telefone na orelha.
- **Retry de rede** em conexão instável — a do salão é.

O efeito de um duplicado aqui não é cosmético: é um horário a mais bloqueado na agenda, e mais
adiante seria uma segunda cobrança.

## 3. Atores

Infraestrutura, consumida pela camada de aplicação. Sem ator humano direto.

## 4. Decisão central: uma transação só

O desenho usual é em três fases — reservar a chave numa transação, executar o negócio em outra,
gravar a resposta numa terceira. Ele oferece um estado "em andamento" observável, e por isso é
tentador. **Foi descartado.**

Ele abre uma janela real: se o processo morre entre o commit do negócio e a gravação da resposta,
a chave volta a parecer disponível e a repetição **executa o efeito de novo**. Em criação de
agendamento isso é uma segunda reserva; no `financeiro` seria uma segunda cobrança. A janela é
pequena e é exatamente a que a idempotência foi contratada para eliminar.

Aqui o registro de idempotência e o efeito de negócio commitam **na mesma transação**. Ou os dois
acontecem, ou nenhum. Não existe estado intermediário para observar — e é por isso que também não
existe a janela.

Consequência de projeto: `Idempotencia` é usada **no caso de uso**, nunca num filtro ou
interceptor HTTP. Um interceptor não tem como participar da transação do caso de uso, e por isso
não teria como oferecer esta garantia.

## 5. Quem arbitra a concorrência é o banco

Duas requisições simultâneas com a mesma chave: a segunda **bloqueia no índice único** até a
primeira commitar, e então lê a resposta já gravada.

```sql
insert into idempotencia (...) values (...)
on conflict on constraint idempotencia_unica do nothing
returning id
```

Zero linhas devolvidas significa "outra transação é dona da chave" — e nesse ponto ela já
commitou, porque o `INSERT` esperou por ela. Mesma filosofia da exclusion constraint da agenda:
a aplicação valida por UX, o banco garante. Lock de aplicação não sobreviveria a duas instâncias.

**Custo assumido:** a segunda requisição espera o tempo da primeira. Para o volume de um salão é
irrelevante, e `lock_timeout` limita o pior caso. A alternativa — responder 409 "em andamento" de
imediato — exigiria a reserva em transação separada, com a janela da seção 4.

**Dependência de isolamento, e ela é real:** funciona em `READ COMMITTED` (o padrão) porque cada
comando toma um snapshot novo, e por isso o `SELECT` seguinte enxerga a linha que o `INSERT`
acabou de perder. Em `REPEATABLE READ` o select **não** a veria. Se algum caso de uso precisar de
isolamento mais forte, esta classe precisa ser revista junto — está anotado no javadoc, onde quem
mudar o isolamento vai olhar.

## 6. Contrato

```http
POST /api/v1/agendamentos
Idempotency-Key: 8f14e45f-ea0a-4b8f-9d1e-1a2b3c4d5e6f
```

| Situação | Resposta |
|---|---|
| Primeira chamada | 2xx normal |
| Mesma chave, mesmo payload | 2xx com o corpo original + `Idempotent-Replay: true` |
| Mesma chave, payload diferente | 422 `ER-INF-IDEMPOTENCIA_CONFLITO` |
| Chave em outro escopo | Operação independente |
| Chave em outro estabelecimento | Operação independente |

`Idempotent-Replay` importa: sem ele o cliente não distingue "criei agora" de "já existia", e é
essa distinção que o impede de contar duas vezes.

**Reuso de chave com payload diferente é erro do cliente, não repetição.** Reexecutar seria criar
um segundo agendamento sob a chave do primeiro — o oposto da garantia.

## 7. Dados

`V4__idempotencia.sql`. Colunas relevantes:

| Coluna | Por quê |
|---|---|
| `escopo` | A operação. Sem ele, a mesma chave em endpoints diferentes colidiria e o cliente receberia a resposta de outra operação |
| `hash_payload` | SHA-256 do JSON canônico. Não é segurança — é que colisão aqui significa devolver a resposta de uma operação para outra, então `hashCode()` não serve |
| `corpo_resposta` | `jsonb`. Desserializado **com o tipo declarado pelo chamador**, nunca pelo nome de classe guardado no banco — isso seria um vetor de gadget de desserialização |
| `expira_em` | Retenção de 7 dias, configurável em `app.idempotencia.retencao` |

`unique (estabelecimento_id, escopo, chave)` é o árbitro da concorrência, não um detalhe de
integridade.

## 8. Purga

`PurgadorDeIdempotencia`, diário às 3h. Sem ele a tabela cresce para sempre — mesmo modo de falha
do `event_publication` do Modulith (risco R-12): nada quebra, o disco só enche, e enche primeiro
em produção.

Roda pela **conexão de manutenção** ([ADR-0010](../../adr/0010-role-de-manutencao.md)), nunca pela
da aplicação: alcançar mais de um estabelecimento é justamente o poder que `salao_app` não pode
ter.

## 9. Testes

- [x] `segunda_chamada_repete_sem_reexecutar` — a ação roda uma vez só
- [x] `payload_diferente_e_conflito` — 422, não reexecução
- [x] `escopos_diferentes_nao_colidem`
- [x] `chave_e_por_tenant`
- [x] `falha_no_negocio_libera_a_chave` — rollback leva o registro junto; a chave não fica queimada
- [x] `concorrencia_e_arbitrada_pelo_banco` — duas threads, a segunda espera e repete, efeito uma vez
- [x] `purga_remove_apenas_vencidos` — e prova que a role de manutenção alcança os dois tenants

O de concorrência assere antes o tamanho do pool: com pool de 1 ele travaria em vez de falhar, e
teste que trava é pior que teste que falha.

## 10. O que a implementação revelou

**Um segundo bean `DataSource` desliga o `DataSource` da aplicação.** O
`DataSourceAutoConfiguration` do Boot é `@ConditionalOnMissingBean(DataSource.class)`: publicar o
`DataSource` de manutenção como bean fez o da aplicação sumir. O sintoma aparece longe da causa —
Hibernate falhando com *"Unable to determine Dialect without JDBC metadata"*, sem nenhuma menção
ao bean extra. Resolvido encapsulando em `ConexaoDeManutencao`, que não é do tipo `DataSource` e
ainda fecha o pool no shutdown.

**Pool de teste passou a ser declarado por classe.** `TenantIsolamentoIT` precisa de pool 1 para
provar o `SET LOCAL`; `IdempotenciaIT` precisa de mais de um para ter concorrência real. Estava na
classe base e virou conflito. Cada classe declara o que precisa — o custo é um contexto Spring a
mais, e a leitura fica mais clara.

## 11. Pendências

- [ ] `lock_timeout` explícito na conexão da aplicação, para limitar a espera da segunda
      requisição. Hoje depende do padrão do Postgres, que é "sem limite"
- [ ] Métrica `idempotencia.repeticao` por escopo — repetição subindo é sinal de cliente com
      problema de rede ou de retry mal configurado
- [ ] Cabeçalho `Idempotent-Replay` no response: entra com o primeiro controller
- [ ] Teste que quebre o build quando uma tabela com retenção prometida esquecer
      `permitir_manutencao` (ver ADR-0010)
