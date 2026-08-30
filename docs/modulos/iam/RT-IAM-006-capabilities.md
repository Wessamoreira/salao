---
id: RT-IAM-006
titulo: /me/capabilities e autorização por permissão
modulo: iam
fase: 0
perfil: completo
status: implementado
depende_de: [RT-IAM-002, RT-IAM-005]
permissoes: []
eventos: []
regras: [RN-IAM-013, RN-IAM-014]
atualizado_em: 2026-08-29
---

# RT-IAM-006 — `/me/capabilities` e autorização por permissão

## 1. Objetivo

Dar ao front tudo o que ele precisa para desenhar a tela — e ao backend as autoridades que fazem
`@PreAuthorize` funcionar.

## 2. É o contrato que sustenta a regra de ouro do projeto

`14-frontend` diz: *"o front não decide nada"*. Este endpoint é o que torna isso possível. Menu,
botão, limite e obrigatoriedade de MFA chegam prontos; não existe `if (perfil === 'ADMIN')` em
lugar nenhum.

**Menus e limites são derivados no servidor.** Mandar só as permissões e deixar o front escolher
os menus recriaria no JavaScript a regra que este endpoint existe para eliminar — e as duas cópias
divergiriam na primeira permissão nova.

**As flags descrevem o efeito, não o perfil:** `podeVerValorDeOutros`, não `ehGerente`. O front
pergunta o que quer saber, e a resposta continua correta quando o mapa de permissões mudar.

## 3. O token carrega o perfil; as permissões são derivadas

`ConversorDePermissoes` expande o perfil do JWT nas autoridades que o `@PreAuthorize` confere.

**Por que não colocar a lista no token:** ela congelaria a decisão por até quinze minutos e
cresceria o token à toa. Com o perfil, corrigir uma permissão errada passa a valer na requisição
seguinte.

> Esta classe é o que faz `hasAuthority('agenda:write:all')` funcionar. Sem ela, **todo
> `@PreAuthorize` do projeto negaria acesso a todo mundo** — e silenciosamente, porque negar é o
> comportamento correto quando não há autoridade nenhuma.

## 4. O mapa de permissões, e as promessas que ele guarda

Cada linha de `MapaDePermissoes` é uma promessa feita ao dono do salão, e por isso cada uma tem
teste:

| Perfil | Decisão |
|---|---|
| `PROFISSIONAL` | Só a própria agenda e o próprio extrato. Vê cliente, não vê a ficha (dado de saúde) |
| `RECEPCAO` | Agenda para todos, abre e fecha comanda — **não vê comissão nem custo de produto** |
| `PAINEL` | Conta de dispositivo em espaço público: leitura da agenda, zero financeiro |
| `BOT` | **Vazio.** Age em nome de um usuário e herda as permissões dele |
| `GERENTE` | Opera tudo menos a estrutura: quem cria usuário e muda configuração é o dono |

O `BOT` vazio é o que impede o *confused deputy*: mandar mensagem ao bot e conseguir o que o
próprio login não permite.

**Em código, e não em banco, por enquanto.** Enquanto todos os salões usam o mesmo mapa, uma
tabela seria configuração que ninguém configura — e precisaria de tela, migração e teste. O
gatilho para mover está escrito: o primeiro salão que pedir um perfil diferente. O que já está
pronto é o que importa — nada no sistema pergunta pelo perfil, tudo pergunta por permissão.

## 5. RN-IAM-014 — a imposição do MFA acontece no backend

Esta é a parte que faltava em RT-IAM-005, e ela é o ponto da rotina.

Se "MFA obrigatório para ADMIN" fosse apenas uma flag em `/me/capabilities`, seria uma instrução
para o front respeitar — e quem chamasse a API diretamente entraria sem segundo fator nenhum. O
próprio projeto diz que esconder botão é UX, não segurança.

Um `AuthorizationManager` recusa qualquer requisição de perfil que exija MFA sem MFA ativo. Três
exceções, cada uma com motivo:

| Aberto mesmo com MFA pendente | Por quê |
|---|---|
| `/api/v1/auth/mfa/**` | É o caminho para sair do bloqueio |
| `/api/v1/me/**` | Sem ele, a tela não teria informação para explicar o bloqueio |
| `POST /api/v1/auth/logout-all` | É ação de **segurança**: bloquear reduziria a segurança em nome de uma regra de segurança |

A terceira só apareceu porque um teste existente quebrou quando a imposição entrou. Quem suspeita
que perdeu o dispositivo precisa poder encerrar as sessões, MFA pendente ou não.

**Confirmar o MFA devolve tokens novos.** O token em uso ainda diz `mfa=false`; sem isso o usuário
ficaria bloqueado logo depois de fazer exatamente o que se pediu dele.

## 6. Testes

- [x] `MapaDePermissoesTest` — 7: uma promessa por teste, incluindo "recepção não vê financeiro" e
      "bot não tem permissão própria"
- [x] `CapacidadesIT` — 4: conteúdo por perfil, menus derivados, limites, flags por efeito
- [x] `AutorizacaoWebIT` — 5: **admin sem MFA barrado pelo backend** · caminho de saída aberto ·
      confirmar libera na hora · quem não precisa passa direto · sem token nada passa

`AutorizacaoWebIT` usa `/api/v1/agendamentos`, que ainda não existe, como sonda: **403 é barrado
pela autorização; 404 é passou por ela**. É o discriminador exato de que se precisava.

## 7. O bug que o teste encontrou

**Toda URL digitada errada retornava 500.**

O `@ExceptionHandler(Exception.class)` do `ManipuladorGlobalDeErros` engolia as próprias exceções
do Spring MVC — `NoResourceFoundException`, método não suportado, corpo ilegível — e as convertia
em erro interno.

Em produção isso encheria o log de 500 por causa de clientes que erraram o caminho, **escondendo
os 500 de verdade no meio do ruído**. E o alerta de taxa de erro 5xx dispararia por engano.

Só apareceu porque este teste precisava distinguir 403 de 404. Corrigido com tratadores
específicos — o mais específico ganha, e é por isso que eles precisam existir explicitamente.

## 8. Pendências

- [ ] Permissões por estabelecimento, quando o primeiro salão pedir um perfil diferente
- [ ] `podeVerFichaDoCliente` só existe como flag: a permissão ainda não protege nada, porque o
      módulo `cliente` não existe (Fase 1)
- [ ] Cachear `/me/capabilities` — está na lista do que se cacheia em `03-arquitetura` e ainda não
      é cacheado; precisa de invalidação ao trocar perfil
- [ ] Revogação imediata do access token: **o gatilho registrado em RT-IAM-004 disparou** — a
      imposição de MFA existe agora. Trocar o perfil de alguém ainda leva até 15 min para valer
