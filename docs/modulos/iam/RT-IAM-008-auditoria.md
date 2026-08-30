---
id: RT-IAM-008
titulo: Auditoria append-only
modulo: iam
fase: 0
perfil: completo
status: implementado
depende_de: [RT-IAM-006, RT-IAM-007]
permissoes: []
eventos: []
regras: [RN-IAM-017, RN-IAM-018]
atualizado_em: 2026-08-29
---

# RT-IAM-008 — Auditoria append-only

## 1. Objetivo

Responder *"quem alterou isto, quando e de onde?"* meses depois — numa discussão com cliente sobre
um horário, ou com profissional sobre um repasse.

## 2. Auditoria não é log

| | Log | Auditoria |
|---|---|---|
| Serve para | Investigar comportamento do sistema | Responder a uma pergunta de negócio |
| Pode ser | Amostrado, filtrado, apagado | Imutável, retido por prazo definido |
| Consultado por | `traceId`, texto | Chave de negócio (entidade + id) |
| Contém PII? | **Nunca** | Sim, e por isso tem retenção |

Confundir os dois leva ao pior dos mundos: log com dado pessoal e trilha que alguém apagou para
liberar disco.

## 3. RN-IAM-017 — imutável por permissão, não por convenção

A role da aplicação simplesmente **não tem** `UPDATE` nem `DELETE` na tabela (revogados em V3).
Convenção depende de ninguém escrever o comando; permissão revogada faz o comando não funcionar.

`AuditoriaIT.trilha_e_imutavel` executa `UPDATE` e `DELETE` pela conexão de `salao_app` e verifica
que os dois falham com `permission denied` — **assertando a razão, e não só que houve exceção**.
Falhar por outro motivo qualquer também passaria num teste genérico, e não provaria nada.

## 4. RN-IAM-018 — grava na mesma transação do fato

`@Transactional(propagation = MANDATORY)`. Se abrisse transação própria, a trilha registraria
alterações que o rollback desfez — e **trilha que mente é pior que trilha ausente**, porque alguém
vai acreditar nela.

`MANDATORY` em vez de `REQUIRED` é deliberado: quem esquecer de auditar de dentro de uma transação
descobre na hora, em vez de gravar um registro solto que ninguém questionaria.

Tentativa **recusada** é outra coisa: é sinal de segurança e vive no log, não aqui.

## 5. O chamador descreve o quê; o resto se resolve sozinho

Quem chama informa ação, entidade, `antes` e `depois`. Usuário, ator, IP, user agent e `traceId`
vêm do contexto da requisição.

Pedir isso a cada chamador significaria que, na décima chamada, alguém passaria o usuário errado
ou deixaria o IP em branco — e a trilha só vale se for uniforme.

**Rede de segurança contra PII:** chaves como `senha_hash`, `segredo` e `token` são substituídas
por `[omitido]` antes de gravar. A trilha é retida por anos e lida por gente; um hash de senha ali
viraria dado sensível de longa duração que ninguém revisaria depois. Substituir em vez de recusar
o registro é proposital — perder a trilha seria pior que guardá-la sem o campo.

## 6. Retenção com dois prazos

| Entidades | Prazo | Por quê |
|---|---|---|
| `agendamento`, `comanda`, `pagamento`, `lancamento`, `fechamento`, `comissao` | **5 anos** | Respondem a disputa com cliente ou profissional |
| As demais | **1 ano** | Passado isso não respondem mais nada, e guardar dado pessoal sem finalidade contraria a minimização da LGPD |

Apagar por prazo **é** parte da política, não descuido: trilha que cresce para sempre vira, ela
própria, um repositório de dado pessoal que ninguém revisa.

V10 acrescenta índice por `ocorrido_em` — o existente começa por `estabelecimento_id`, e o expurgo
varre por data atravessando estabelecimentos.

## 7. Fecha a pendência de RT-IAM-007

Criar usuário, alterar perfil, desativar e resetar MFA agora deixam rastro com `antes` e `depois`.
Antes disso havia só log de aplicação, que não serve como trilha — é amostrável, filtrável e
apagável.

## 8. Nota sobre empilhar advices

`@PreAuthorize` e `@Transactional` convivem nos casos de uso, e **aqui é seguro**: a advice de
method security tem ordem definida (200) e a de transação usa `LOWEST_PRECEDENCE`, então a
autorização roda por fora e nega antes de abrir transação.

É o oposto do par `@Async`/`@Transactional` (RT-INF-006), em que ambas compartilham a mesma ordem
e o resultado não é confiável. O padrão do projeto — *não empilhe advices cuja ordem relativa você
não controla* — continua valendo; a diferença é que aqui a ordem **é** controlada, e está escrita
no código para quem vier depois não precisar redescobrir.

## 9. Testes

- [x] `AuditoriaIT` — 6: **imutável por permissão** · rollback não deixa rastro · exige transação ·
      campos sensíveis omitidos · gestão deixa rastro com antes/depois · retenção por criticidade

## 10. Pendências

- [ ] Auditar agenda e financeiro: as entidades críticas já estão na regra de retenção, mas os
      módulos não existem. Entram junto com `RT-AGD-002` e `RT-ATD-006`
- [ ] Ator `BOT`: hoje só `USUARIO` e `SISTEMA`. Entra na Fase 4, quando o bot passar a agir em
      nome de alguém — ali o usuário efetivo estará no contexto e o ator precisará dizer que a
      ação veio da IA
- [ ] Consulta da trilha por tela: hoje só existe por SQL. Precisa de decisão de UI e de permissão
      própria — nem todo perfil deve ver quem alterou o quê
- [ ] Auditar login e logout. Hoje ficam em log; como são eventos de segurança e não alterações
      de dado, a decisão de onde eles moram ainda não foi tomada
