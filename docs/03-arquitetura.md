# Arquitetura

## Estilo

Monólito modular com Spring Modulith, fronteiras verificadas por teste. Não é preguiça: para
um desenvolvedor e volume de salão, deploy separado só adiciona modos de falha. O que torna a
extração futura barata é a **fronteira**, não o deploy.

## Módulos

| Módulo | Sigla | Responsabilidade | Agregados | Publica |
|---|---|---|---|---|
| `shared` | — | `Money`, `TenantContext`, IDs, erros, paginação, relógio | — | — |
| `iam` | IAM | Estabelecimento, usuário, perfil, permissão, sessão, auditoria | Estabelecimento, Usuario, Perfil | `UsuarioCriado`, `PerfilAlterado` |
| `equipe` | EQP | Profissional PJ, vínculo, jornada, exceção, habilidades | Profissional, Jornada | `JornadaAlterada`, `ProfissionalDesativado` |
| `cliente` | CLI | Cliente, contatos, preferências, ficha técnica (dado sensível) | Cliente | `ClienteCriado`, `ClienteAnonimizado` |
| `catalogo` | CAT | Serviço, blocos, preço, recurso | Servico, Recurso | `PrecoAlterado`, `ServicoDesativado` |
| `agenda` | AGD | Disponibilidade, agendamento, bloqueio, recorrência, fila de espera | Agendamento, Bloqueio | `AgendamentoCriado/Confirmado/Reagendado/Cancelado`, `NoShowRegistrado` |
| `atendimento` | ATD | Comanda: abertura, itens, executantes, desconto, fechamento | Comanda | `ComandaAberta`, `ComandaFechada`, `ComandaEstornada` |
| `financeiro` | FIN | Pagamento, livro-razão, comissão, vale, fechamento, conciliação | Pagamento, Lancamento, Fechamento, Conciliacao | `PagamentoRegistrado`, `PagamentoConciliado`, `ComissaoApurada`, `DivergenciaDetectada` |
| `estoque` | EST | Produto, lote, movimento, saldo, custo médio, alertas | Produto, Lote | `EstoqueBaixo`, `LoteProximoVencimento`, `EstoqueMovimentado` |
| `conversacional` | CNV | Webhook, conversa, orquestração do agente, tools, intenção pendente | Conversa, IntencaoPendente | `AcaoExecutadaPorIA` |
| `notificacao` | NOT | Envio (WhatsApp/Telegram/e-mail), template, retry, opt-out | Mensagem | `MensagemEntregue`, `MensagemFalhou` |
| `arquivos` | ARQ | Upload por presigned URL, validação, thumbnail | Arquivo | — |

### Por que `cliente` e `equipe` existem (correção do desenho inicial)

`Cliente` não tinha módulo dono no rascunho e é referenciado por agenda, atendimento,
financeiro e conversacional — sem dono, o cadastro acaba duplicado em dois lugares. E
`Profissional` não é item de catálogo: tem CNPJ, contrato, comissão, jornada e ciclo de
vida próprio, com cadência de mudança completamente diferente de `Servico`. Ver ADR-0005.

## Regra de ouro e sua única exceção

Módulo só conversa com outro pela **API pública** (`<modulo>/api`) ou por **evento**.
**Zero join entre tabelas de módulos diferentes.**

Exceção única, nomeada e testável: o schema `relatorio`. Relatórios analíticos (repasse por PJ,
faturamento por serviço, curva ABC de produto) leem views que cruzam tabelas de vários módulos.
Condições:

1. Vivem no schema `relatorio`, nunca no schema de um módulo.
2. São **somente leitura**. Nenhuma escrita passa por ali.
3. Toda view filtra por `estabelecimento_id` e respeita RLS.
4. A exceção é codificada explicitamente no teste de ArchUnit — se alguém cruzar módulos fora
   dela, o build quebra.

## Camadas dentro do módulo

```
web/          controller HTTP, DTO de request e response, mapeamento de erro
application/  caso de uso — orquestra, transaciona, autoriza
domain/       agregado, entidade, value object, política, evento, port
infra/        adapter dos ports: JPA, S3, HTTP externo, publisher
api/          contrato público consumido por outros módulos (interface + DTO)
```

Dependências permitidas: `web → application → domain`, `infra → domain`, `application → api de
outro módulo`. Proibido: `domain → spring`, `web → infra`, `web → repository`,
`application → web`.

### Um caso de uso por classe

```java
// certo
public class CriarAgendamentoUseCase {
    @Transactional
    @PreAuthorize("hasAuthority('agenda:write:all') or hasAuthority('agenda:write:own')")
    public AgendamentoCriado executar(CriarAgendamentoCommand cmd) { ... }
}

// errado — vira lixeira de 40 métodos em seis meses
public class AgendaService { ... }
```

Nome do arquivo é o nome do caso de uso na doc: `RT-AGD-002 Criar agendamento` →
`CriarAgendamentoUseCase`. Se não der para nomear com verbo + substantivo, o caso de uso está
fazendo mais de uma coisa.

## Eventos

Dois tipos, com tratamento diferente:

| Tipo | Exemplo | Mecanismo | Garantia |
|---|---|---|---|
| **Interno** (mesmo processo, mesma transação) | Comanda fechada → apurar comissão | `@DomainEvents` / `ApplicationEventPublisher` síncrono | Atômico com a escrita |
| **Externo** (sai do processo) | Agendamento criado → WhatsApp de confirmação | **Outbox transacional** (Spring Modulith `@ApplicationModuleListener`) | Ao menos uma vez |

```
@Transactional: salva agendamento + INSERT em event_publication   ← mesmo commit
Publisher assíncrono: lê pendentes → publica → marca completo
```

Consequências que precisam de código explícito:

- **Consumidor é idempotente**, sempre. Ao menos uma vez significa "vai chegar repetido".
- A tabela `event_publication` **cresce sem limite** se ninguém limpar. Configure a política de
  completion e um job de expurgo; monitore o tamanho da fila (ver `12-observabilidade`).
- No restart, eventos incompletos são republicados. Isso é o desejado, e é o motivo do item 1.

## Cache

Caffeine local por instância, invalidado por `LISTEN/NOTIFY` do Postgres. Custo de infra: zero.

```
Instância A: altera preço  →  NOTIFY cache_invalidacao (DENTRO da transação)
Postgres entrega só no commit, descarta no rollback
Instâncias A, B, C: LISTEN → invalidam a chave local
```

Detalhes que decidem se isso funciona ou serve dado velho:

- O `NOTIFY` vai **dentro da transação**. O Postgres já garante a semântica de commit. Emitir
  depois do commit, pela aplicação, abre a janela "commit passou, processo morreu, ninguém
  invalidou".
- O listener usa **conexão dedicada, fora do pool do Hikari**. Uma conexão presa em `LISTEN`
  dentro do pool é uma conexão a menos para sempre.
- `LISTEN/NOTIFY` **não é durável**. Se a conexão do listener cair, invalidações são perdidas em
  silêncio. Obrigatório: reconexão automática, **flush total do cache ao reconectar**, e métrica
  `cache.listener.up` com alerta.
- `expireAfterWrite` de 30 min é a rede de segurança que limita o estrago para 30 min de preço
  velho no pior caso. Não aumente esse valor achando que o `NOTIFY` cobre.

| Cacheia | Não cacheia |
|---|---|
| Catálogo de serviços e preços | Agenda (escrita constante, correção crítica) |
| Jornada e configuração do estabelecimento | Saldo financeiro |
| `capabilities` do usuário | Saldo de estoque |
| Relatório agregado de período já fechado | Comanda aberta |

Para a agenda, o ganho vem de índice certo, projeção e keyset — não de cache.

```java
Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(30))
    .refreshAfterWrite(Duration.ofMinutes(5))   // serve o velho enquanto recarrega
    .recordStats();                             // hit rate exportado no Micrometer
```

Warm-up no `ApplicationReadyEvent`: catálogo + agenda do dia. Startup ~1s mais lento, primeira
requisição do dia não paga o custo.

Camada extra de graça: `ETag`/`If-None-Match` nos GETs do painel do balcão — o navegador nem
baixa o corpo quando nada mudou.

## Idempotência

Todo endpoint de escrita exposto a webhook, bot ou retry aceita `Idempotency-Key`.

```
chave + hash do payload + resposta original + expira_em   →  tabela idempotencia
mesma chave + mesmo payload  → devolve a resposta original, não reexecuta
mesma chave + payload diferente → 422 ER-INF-IDEMPOTENCIA_CONFLITO
```

## Performance — regras que valem desde o dia 1

- **Paginação por keyset**, nunca `OFFSET`. `OFFSET 50000` varre 50 mil linhas.
- **Leitura não carrega entidade.** Listagem devolve projeção/DTO direto. Entidade é para escrita.
- **N+1:** `@EntityGraph` ou fetch join; ligue
  `hibernate.query.fail_on_pagination_over_collection_fetch`. Em teste, `generate_statistics`
  ligado e **falhe o teste** se o caso de uso passar do orçamento de queries declarado na doc.
- CQRS leve: escrita pelo agregado, leitura pesada por SQL nativo. Sem event sourcing.
- `statement_timeout` configurado, `pg_stat_statements` ligado, `EXPLAIN (ANALYZE, BUFFERS)` em
  toda query de listagem antes de subir.

## Virtual threads

`spring.threads.virtual.enabled=true`. Dois avisos:

1. Virtual thread resolve espera de I/O, não CPU. A carga aqui é I/O de banco, então ajuda — mas
   o gargalo **passa a ser o pool do Hikari**. Com 1000 requisições virtuais e pool de 10, você
   tem 990 esperando conexão. Dimensione o pool e use `connection-timeout` curto para falhar
   rápido em vez de empilhar.
2. O pinning de `synchronized` foi resolvido no JDK 24 (JEP 491), então em Java 25 não existe.
   Cuidado ainda com `ThreadLocal` pesado e libs que criam pool próprio. `TenantContext` usa
   `ScopedValue` onde der.

## Fronteiras verificadas

```java
@Test void modulos_respeitam_fronteiras() {
    ApplicationModules.of(Application.class).verify();
}

@Test void dominio_nao_depende_de_spring() {
    noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework..")
        .check(classes);
}

@Test void controller_nao_chama_repository() { ... }
@Test void nenhuma_entidade_jpa_aparece_em_assinatura_de_controller() { ... }
@Test void toda_tabela_de_negocio_tem_estabelecimento_id() { ... }   // lê o schema do Testcontainer
@Test void toda_tabela_de_negocio_tem_rls_habilitada() { ... }
```

Os dois últimos são os que salvam o multi-tenant. Rodam contra o banco real do Testcontainers e
falham quando alguém cria migration esquecendo o tenant. Se a fronteira não tem teste, ela é só
uma intenção.
