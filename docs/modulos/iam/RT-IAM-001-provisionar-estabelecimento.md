---
id: RT-IAM-001
titulo: Provisionar estabelecimento
modulo: iam
fase: 0
perfil: completo
status: implementado
depende_de: [RT-INF-002, RT-INF-005, RT-INF-007]
permissoes: []
eventos: []
regras: [RN-IAM-001, RN-IAM-002, RN-IAM-003]
atualizado_em: 2026-08-29
---

# RT-IAM-001 — Provisionar estabelecimento

## 1. Objetivo

Criar o tenant: a raiz de todo isolamento e a fonte da configuração que os demais módulos
consultam.

## 2. Contexto

Nada funciona sem um estabelecimento — a RLS o compara em toda tabela, e o fuso dele decide o que
significa "hoje". Mas até esta rotina, **não existia caminho para criá-lo**: a migration V3 deixou
a pendência anotada e nenhuma role tinha a permissão necessária.

## 3. A decisão central: provisionamento é operação de plataforma

`salao_app` não conseguiria fazer isso nem com `insert` concedido. A policy `tenant_isolado` tem
`with check (id = current_setting('app.tenant_id'))`, então a aplicação só insere uma linha cujo
`id` seja o do tenant que ela já é. Para criar um tenant que ainda não existe, isso é inútil.

Três saídas possíveis, e duas são ruins:

| Saída | Por quê não |
|---|---|
| Afrouxar a policy de `estabelecimento` | Desfaz o isolamento inteiro para resolver um caso que acontece uma vez por cliente |
| Gerar o UUID na aplicação e abrir o escopo nele antes de inserir | Funciona por acidente do `with check`, e passa a mensagem de que qualquer código pode se declarar dono de um tenant novo |
| **Role de plataforma** | Provisionamento é a mesma categoria de purga e retenção: cross-tenant por natureza (ADR-0010) |

**Endurecimento junto:** V6 revoga o `insert` que `salao_app` tinha desde V3. Ele era inofensivo
só por acidente da policy — permissão que não é exercida deve ser removida, não deixada dependendo
de uma segunda camada para não causar dano.

## 4. Sem transação, e sem evento — os dois de propósito

**Sem `@Transactional`:** a conexão de plataforma não passa pelo gerenciador que exige tenant, e
ele exigiria um tenant que por definição ainda não existe. Um `INSERT` único é atômico de qualquer
forma.

**Sem evento de domínio:** publicá-lo exigiria escrever no outbox pela conexão da aplicação, em
outra transação — sem atomicidade com o insert, que é exatamente a garantia pela qual o outbox
existe. Quando algo precisar reagir a provisionamento, resolve-se gravando a publicação pela mesma
conexão, e não fingindo que duas escritas são uma só.

## 5. Leitura: decorador de cache, não anotações empilhadas

`EstabelecimentoCacheado` decora `EstabelecimentoJdbc` em vez de `@Cacheable` e `@Transactional`
conviverem no mesmo método.

**Motivo:** as duas advices usam `LOWEST_PRECEDENCE`, e a ordem entre elas não é confiável. Se a
transação abrir primeiro, até um acerto de cache paga uma transação e um `set_config` — exatamente
o custo que o cache existe para evitar. Dois beans tornam a ordem um fato do código.

É a mesma lição do `PropagadorDeContexto` (RT-INF-006), onde a ordem entre `@Async` e
`@Transactional` também não era confiável. **Duas vezes o mesmo problema em fases diferentes: vale
como padrão do projeto — não empilhe advices cuja ordem relativa você não controla.**

`@Transactional` na leitura é obrigatório mesmo sendo somente leitura: é a transação que dispara o
`set_config('app.tenant_id')`. Fora dela, a RLS não encontra o tenant e a consulta devolve zero
linhas — falha fechada, mas confusa para quem investiga.

## 6. Contrato

Sem endpoint HTTP nesta rotina, **deliberadamente**: não há Spring Security ainda (RT-IAM-002), e
expor criação de tenant sem autenticação seria abrir o sistema. O caso de uso está pronto para
receber um controller assim que houver autorização.

## 7. Dados

`V6__provisionamento.sql`: policy `manutencao` ganha `with check (true)`, `salao_manutencao` ganha
`insert, update`, e `salao_app` perde `insert`.

## 8. Testes

- [x] `provisiona_e_o_tenant_fica_utilizavel` — provisiona e lê pela API já no escopo do tenant
- [x] `aplicacao_nao_cria_estabelecimento` — prova o revoke de V6
- [x] `configuracao_e_isolada_por_tenant` — a RLS filtra mesmo com o id conhecido
- [x] `configuracao_e_cacheada` — altera a linha por fora e confirma que a leitura ainda vem do cache
- [x] `fuso_invalido_e_erro_de_dominio` — código do catálogo, não exceção crua
- [x] `NovoEstabelecimentoTest` — 5 testes de domínio puro, sem Spring nem banco

## 9. O que a implementação revelou

**Os dois guardrails de arquitetura funcionam — e eu os disparei sem querer.**

Escrevi o enum de erros do módulo dentro de `..domain..`, e ele implementa `CodigoDeErro`, que
expõe `HttpStatus`. O `ArquiteturaTest.dominio_nao_depende_de_spring` reprovou o build apontando as
quatro formas do vazamento (campo, construtor, retorno e inicializador estático). O enum foi para
`iam/api`, que é o lugar certo: código de erro é contrato público, é o front que o consome.

Ao mesmo tempo, o `ApplicationModules.verify()` reprovou `iam` por depender de tipos não expostos
de `shared`. **O Modulith só considera exposto o pacote-raiz de um módulo**, e `shared` é
organizado por assunto em subpacotes. Declarar `@NamedInterface` em cada um seria cerimônia sem
ganho — `shared` passou a ser `type = OPEN`, com a justificativa escrita no `package-info`: o que
sustenta essa fronteira não é a anotação, é `shared` não depender de nenhum módulo e não conter
regra de negócio.

## 10. Pendências

- [ ] Endpoint HTTP protegido, com `config:manage` (depende de RT-IAM-002)
- [ ] Alterar configuração **e invalidar o cache** — a chave já usa `@chaveDeCache.de(...)`, então
      é endereçável pelo `InvalidadorDeCache`; falta a rotina de alteração
- [ ] Primeiro usuário `ADMIN` junto do provisionamento: hoje nasce um estabelecimento sem ninguém
      que possa entrar nele (RT-IAM-002/007)
- [ ] Registrar o provisionamento em `auditoria` (RT-IAM-008)
