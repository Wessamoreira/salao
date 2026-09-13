---
id: RT-INF-010
titulo: Shell do front
modulo: infra
fase: 0
perfil: completo
status: implementado
depende_de: [RT-IAM-006]
permissoes: []
eventos: []
regras: [RN-INF-011]
atualizado_em: 2026-09-12
---

# RT-INF-010 — Shell do front

## 1. Objetivo

A casca em que toda tela de negócio entra sem mexer em rota, sessão ou permissão.

## 2. RN-INF-011 — renovação de sessão é sempre uma só

**A decisão mais importante desta rotina, e ela só existe por causa de como o backend ficou.**

O refresh é rotativo com detecção de reuso (RN-IAM-007): apresentar um token já trocado revoga a
**família inteira**. Se três requisições receberem 401 ao mesmo tempo e cada uma disparar seu
próprio refresh, as três apresentam o mesmo cookie — a primeira rotaciona, e as outras duas são
lidas como reuso. **O backend derruba a sessão, corretamente, por um acidente de concorrência do
front.**

A janela de tolerância de 10 s no servidor perdoaria esse caso hoje. Depender dela seria construir
sobre uma folga que existe para rede instável, não para descuido nosso.

Por isso `renovacaoEmCurso` é uma promessa única compartilhada: a primeira chamada renova, as
demais esperam o mesmo resultado. `http.test.ts` dispara três requisições paralelas e exige
exatamente **uma** renovação.

## 3. O token vive em memória

`localStorage` é proibido (inviolável nº 6) — é XSS servido de bandeja. Em memória, um XSS usa a
sessão enquanto a aba está aberta mas não a leva embora.

O custo é que recarregar perde o token, e é exatamente para isso que existe o cookie `HttpOnly`:
no boot, `restaurarSessao()` troca o cookie por um token novo. O cookie o JavaScript não lê, então
nem um XSS o alcança.

## 4. A casca desenha o que o servidor mandou

**Não existe lista de menus neste código.** Se o backend parar de devolver o financeiro, ele some
sem ninguém tocar no front. É o que torna verdadeira a promessa do inviolável nº 6.

`Casca.test.tsx` monta com capacidades de recepção e verifica que Financeiro e Configurações
**não** aparecem — sem nenhum `if (perfil === ...)` no meio.

**A guarda de rota é conveniência, não segurança.** Ela evita piscar tela vazia para quem não pode
vê-la; quem protege é o backend, que recusa mesmo quem navegue direto pela URL.

O acabamento de 11/09 transformou essa lista numa navegação de produto completa: topo com a seção
atual e a data operacional, lateral com os ícones nomeados pelo servidor, identidade do salão e
usuário, link de salto para teclado e gaveta móvel com fundo de bloqueio. A largura de 820 px é a
mudança de comportamento: acima dela o menu participa do layout; abaixo, sai do fluxo e não rouba
espaço da agenda.

O rodapé da navegação oferece saída explícita. Ao concluir `POST /auth/logout`, o front limpa o
access token e todo o cache em memória antes de voltar ao login, impedindo que dados da conta
anterior permaneçam disponíveis na próxima sessão. Sem conexão a ação fica indisponível: esconder
a falha e deixar o cookie de refresh ativo daria uma falsa sensação de logout.

O backend repetia o nome do salão no lugar do nome do usuário. `Capacidades.nome` agora vem da
conta autenticada, lida sob RLS; `estabelecimento.nome` continua sendo a marca. Sem essa correção,
a lateral mostraria a mesma informação duas vezes e esconderia quem está operando.

### PWA e queda de conexão

O build agora gera o service worker a partir da relação de arquivos com hash que o próprio Vite
acabou de produzir. Manifesto, ícone, tema e modo `standalone` tornam o sistema instalável; o app
shell abre mesmo sem rede depois do primeiro acesso em produção.

A API autenticada não entra no `Cache Storage`. Nomes de clientes num cache compartilhado
sobreviveriam ao logout e poderiam aparecer para a próxima pessoa que usasse o computador. Durante
uma queda, o React Query conserva em memória as consultas que já estavam abertas e cada tela mostra
que o retrato é antigo. Uma escrita offline falha imediatamente com mensagem explícita: ela não é
enfileirada para reaparecer quando a agenda talvez já tenha mudado (R-UX-14).

Abrir a agenda do zero, depois de fechar totalmente o aplicativo, ainda exige internet para
restaurar a sessão. Persistência offline de dado autenticado depende do modo quiosque com credencial
do dispositivo; guardar token ou agenda nominal no armazenamento local não é um atalho aceitável.

## 5. Tokens por referência, nunca copiados

`design/tokens.css` é a fonte única, compartilhada com o canvas de design. O front a alcança por
**symlink** em `src/estilo/tokens.css`, e o Tailwind a lê com `@theme inline` — que referencia as
variáveis em vez de copiar valores.

Duas fontes de verdade para a mesma cor é como a divergência começa, e ela é silenciosa: as telas
ficam diferentes e ninguém sabe qual está certa.

## 6. Telas que ainda não existem dizem isso

`EmBreve` nomeia a rotina que a entrega, em vez de fingir conteúdo. Uma tela com dados falsos
parece pronta e engana quem revisa — inclusive o dono do salão, numa demonstração.

## 7. Testes

- [x] `http.test.ts` — 11: renova UMA vez quando várias chamadas recebem 401 juntas · repete a
      chamada com o token novo depois de renovar · não tenta renovar em loop quando a renovação
      falha · não tenta renovar a partir das próprias rotas de autenticação · preserva o código
      estável do erro, que é o que a tela mapeia · queda de rede chega como ErroDaApi em português,
      e não como o erro cru do navegador · prazo de 12s estourado vira ER-INF-TEMPO_ESGOTADO ·
      cancelar o SSE não acende "Sem conexão" · 502 do proxy conta como servidor fora do ar, mesmo
      com o fetch tendo "funcionado" · recusa uma escrita offline na hora, sem enfileirar nem chamar
      a rede · nunca escreve o token em localStorage
- [x] `Casca.test.tsx` — 9: desenha só os menus que o servidor mandou · mostra o nome do
      estabelecimento vindo das capacidades · abre e fecha a navegação móvel sem perder os menus do
      servidor · desenha os grupos que o servidor mandou, e nenhum grupo vazio · a busca ⌘K navega e
      só oferece o que o servidor mandou · lembra a lateral recolhida entre sessões · desenha a
      lateral mesmo com um servidor que não conhece grupos · um menu de grupo desconhecido aparece,
      em vez de sumir em silêncio · encerra a sessão pelo backend antes de voltar ao login

`npm test` e `npm run build` verdes; a tela de login foi aberta no navegador e conferida.

Verificado em 12/09, depois do acabamento de 11/09 e do PWA: `npm run lint`, `npm run typecheck`,
`npm test` (101) e `npm run build` verdes — o build com `tsc -b`, que é o que pega o que o
`--noEmit` deixa passar —, `sw.js` gerado com o app shell, e a prévia `sistema` inspecionada em
desktop e em 375 px, sem rolagem lateral.

## 8. O que a verificação encontrou

**Eu violei o próprio sistema de design, e só vi na tela.** Os rótulos de campo usavam
`--tinta-tenue`, que `16-design-system` declara insuficiente para AA e restrita a texto não
essencial. Rótulo de campo é essencial. Corrigido para `--tinta-fraca` em todos os pontos.

Vale como lição: a regra estava escrita, revisada e commitada — e ainda assim foi desrespeitada na
primeira tela. Documentar não substitui olhar o resultado.

**`tsc --noEmit` passou onde `tsc -b` falhou.** O build do projeto usa `erasableSyntaxOnly`, que
proíbe propriedades declaradas no construtor. Rodar só `--noEmit` dá falsa confiança.

**Acabamento com `frontend-design`.** A primeira direção repetia o agrupamento mais comum de telas
geradas: fundo creme, serifa de alto contraste, dourado e interface inteira sem uma hierarquia de
produto. A revisão adotou o vocabulário de um back-office de recepção: neutros frios, lateral de
operação compacta, uma única cor de marca, cantos menores e régua de horários como assinatura. O
gesto editorial ficou restrito ao monograma. As fontes são locais para a marca não desaparecer
quando a internet cai.

**A revisão do PWA achou dois defeitos no cliente HTTP, e nenhum teste os pegava.**

O `catch` de `enviar` marcava a API como fora do ar em *qualquer* falha do `fetch` — inclusive
quando quem chamou cancelou pelo próprio `signal`. O SSE cancela ao desmontar, e o `StrictMode`
desmonta todo efeito uma vez em dev: a faixa "Sem conexão" acendia com a rede funcionando.
Cancelamento pedido pelo chamador agora sobe intacto e não mexe no estado.

E o prazo novo de 12s abortava o `fetch` sem traduzir o erro. A tela recebia
`signal is aborted without reason`, em inglês, e quem mostra `erro.message` mostraria isso para a
recepção. Queda de rede e prazo estourado viram `ErroDaApi` com `ER-INF-SEM_CONEXAO` e
`ER-INF-TEMPO_ESGOTADO`, em português, e o login mapeia os dois.

De quebra, 502, 503 e 504 passaram a contar como servidor fora do ar. Com o Caddy (ou o Vite) na
frente, backend caído não faz o `fetch` falhar — devolve 502 —, e era justamente esse o caso "Wi-Fi
conectado, servidor fora" que `registrarEstadoDaApi` promete perceber.

## 9. Pendências

- [ ] Auditoria com a skill `design:accessibility-review` — a correção de contraste foi pontual e
      por inspeção; falta a passagem completa (alvo de toque, ordem de tabulação, leitor de tela)
- [x] PWA instalável, app shell offline, aviso de conexão e leitura das consultas já abertas
- [ ] Reabertura a frio da agenda offline — depende do modo quiosque e de cache isolado por
      dispositivo; dado nominal e token não entram em armazenamento compartilhado
- [ ] **`sw.js` precisa de `Cache-Control: no-cache` no servidor.** Não há Caddyfile no repositório.
      Sem o cabeçalho, o navegador pode servir o service worker velho do cache HTTP por até 24h, e
      um deploy novo demora um dia para chegar a quem não fecha a aba
- [ ] **Queda de rede durante a renovação do token faz logout.** `renovarSessao` devolve `null`
      tanto para refresh recusado quanto para falha de rede, e `api()` trata os dois como sessão
      expirada. O cookie continua válido, mas a pessoa cai no login. Separar os dois casos mexe na
      rotação com detecção de reuso (RN-IAM-007), e por isso não foi feito de passagem
- [x] `Cmd+K` e a paleta de comandos (R-UX-02) — `Paleta.tsx`, coberta por
      `Casca.test.tsx` ("a busca ⌘K navega e só oferece o que o servidor mandou")
- [ ] Sentry no front, com o mesmo `release` da imagem — herdado da pendência de `RT-INF-008`
- [ ] E2E com Playwright: os 5 fluxos críticos só existem a partir da Fase 1
