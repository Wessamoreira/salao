---
id: RT-IAM-007
titulo: CRUD de usuário e atribuição de perfil
modulo: iam
fase: 0
perfil: completo
status: implementado
depende_de: [RT-IAM-005, RT-IAM-006]
permissoes: [usuario:manage]
eventos: []
regras: [RN-IAM-015, RN-IAM-016]
atualizado_em: 2026-08-29
---

# RT-IAM-007 — CRUD de usuário e atribuição de perfil

## 1. Objetivo

Criar, listar, promover, desativar e destravar as pessoas que usam o sistema — sem que o salão
consiga se trancar para fora.

## 2. Primeiro uso real de `@PreAuthorize`

A autorização vive **no caso de uso**, nunca no controller. Não é preferência estética: o bot da
Fase 4 chama o caso de uso diretamente, sem passar por controller nenhum. Autorização no controller
deixaria o bot de fora — e é justamente o bot que precisa herdar as permissões do usuário efetivo.

`GestaoDeUsuariosIT.sem_permissao_nao_cria` prova, chamando o caso de uso direto com uma autoridade
insuficiente.

## 3. RN-IAM-015 — as duas travas contra o auto-bloqueio

**Não se opera sobre si mesmo.** Rebaixar ou desativar a própria conta é quase sempre engano, e o
estrago é imediato: quem perceberia o erro é exatamente quem acabou de perder o acesso.

**O salão nunca fica sem administrador.** Sem essa trava, um salão com dois admins em que um
rebaixa o outro e depois é desativado fica sem ninguém que possa administrá-lo — e a única saída
seria alterar o banco à mão. É barato de evitar e caro de consertar.

As duas verificações vivem em `GestaoDeUsuarios`, compartilhadas: verificação duplicada é
verificação que alguém vai esquecer de atualizar.

## 4. RN-IAM-016 — mudança de acesso encerra as sessões

Rebaixar, desativar, trocar senha e resetar MFA revogam todas as sessões do usuário afetado.

O motivo é a janela do access token. Ele vale 15 minutos e não é revogável; sem encerrar as
sessões, o token com o **perfil antigo** continuaria funcionando. Numa promoção isso seria
irrelevante; num rebaixamento é exatamente a janela que não se quer deixar aberta.

Na troca de senha, todas caem **inclusive a de quem trocou**. Trocar senha é o que se faz ao
suspeitar que alguém tem acesso — manter as sessões abertas manteria justamente o acesso que se
está tentando cortar. O preço é entrar de novo, e é baixo.

## 5. Decisões menores que importam

**Desativar, não apagar.** O histórico aponta para quem executou cada serviço e cada comanda.
Apagar quebraria o rastro que sustenta o extrato de comissão do profissional.

**`BOT` não é atribuível a pessoas.** Um usuário com esse perfil criaria o *confused deputy* que o
projeto evita — o bot age em nome de alguém e herda as permissões dessa pessoa.

**E-mail repetido não diz onde.** O índice é global (V7), então a colisão pode ser com outro
estabelecimento. Dizer isso contaria a um administrador algo sobre um tenant que não é o dele. A
mensagem é a mesma nos dois casos, e há teste para isso.

**Exigir a senha atual na troca** protege contra quem senta no computador do salão com a sessão
aberta — o cenário mais provável num balcão compartilhado.

**Sem paginação na listagem**, contrariando a regra geral do projeto: um salão tem de cinco a vinte
usuários. A regra existe para listagem que cresce sem limite; esta não cresce.

## 6. Resetar o segundo fator fecha uma pendência

Quem perdia o celular **e** os códigos de recuperação ficava sem acesso, sem caminho que não fosse
mexer no banco (pendência de RT-IAM-005).

É uma operação perigosa, e o desenho reconhece: exige `usuario:manage`, encerra as sessões do alvo
e registra em log com nível de aviso. **Confirmar a identidade de quem pediu é responsabilidade de
quem administra** — nenhum sistema resolve isso sozinho, e fingir que resolve seria pior.

## 7. Testes

- [x] `GestaoDeUsuariosIT` — 10: cria e o usuário entra · **sem permissão é recusado no caso de
      uso** · e-mail repetido sem revelar onde · BOT não atribuível · não opera sobre si mesmo ·
      último administrador protegido · desativar encerra sessões · rebaixar encerra sessões ·
      trocar senha exige a atual e encerra tudo · listagem isolada por tenant

## 8. Pendências

- [ ] Convite por e-mail em vez de o administrador definir a senha e comunicá-la. Exige o módulo
      `notificacao` funcionando (Fase 1) — hoje a senha inicial passa por WhatsApp ou papel, o que
      é uma fraqueza real e conhecida
- [ ] Forçar troca no primeiro acesso: só faz sentido junto do convite acima
- [ ] Registrar estas operações em `auditoria` (RT-IAM-008) — hoje só há log de aplicação, que não
      serve como trilha
- [ ] Vincular usuário a profissional (Fase 1, `RT-EQP-001`): hoje `PROFISSIONAL` é um perfil sem
      ligação com o cadastro de quem executa serviço
