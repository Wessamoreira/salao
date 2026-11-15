# Auditoria das telas, da navegação e da segurança do front — 15/11/2026

**Como foi feita.** As 14 prévias (`frontend/preview/*.html`) e a prévia do sistema, fotografadas em
Chrome a 1440×900 e a 390×844 com área segura emulada (47/34px), e o código lido onde a foto
levantava suspeita. Cada achado tem onde está e como se vê; nenhum é adjetivo.

**O que já está certo e não muda:** token em memória e refresh em cookie `HttpOnly`; nenhum
`innerHTML`, `dangerouslySetInnerHTML`, `eval` ou `target="_blank"` no código; `localStorage` só para
preferência de tela (lateral recolhida, densidade da agenda); código da tela pré-carregado no hover;
consultas guardadas 30 min; transição de vista entre seções; o sistema de design inteiro.

---

## 1. Produção e segurança — o mais grave primeiro

| # | Achado | Onde | Consequência |
|---|---|---|---|
| **S1** | **O front não é servido em produção.** O Caddy manda tudo para `app:8080`; o jar não leva o `dist`; o CI gera o pacote e só o guarda como artefato por 7 dias | `ops/producao/Caddyfile`, `Dockerfile`, `.github/workflows/ci.yml` | Nenhuma tela — nem o PWA, nem a porta do sistema — chega a quem abre o domínio. Não estava registrado em pendência nenhuma |
| **S2** | **Não existe CSP para o front.** A da API é `default-src 'none'` e está certa; a do front "pertence ao servidor de estáticos" (RT-INF-011), que não existe | RT-INF-011, pendência aberta | Um XSS teria rede e script livres |
| S3 | Sem `Permissions-Policy`, `Cross-Origin-Opener-Policy` e `X-Frame-Options`/`frame-ancestors` para as páginas | idem | Câmera, microfone e localização disponíveis para qualquer script; a janela pode ser referenciada por quem a abriu |
| S4 | Sem regra de cache por tipo de arquivo | idem | `sw.js` e `index.html` precisam de `no-cache`; `/assets/*` (com hash) pode ser imutável por um ano |

**A tecnologia que cabe aqui, e que este código já permite de graça:** como não há nenhum
`innerHTML` no front, dá para exigir **Trusted Types** (`require-trusted-types-for 'script'`) —
o navegador passa a recusar qualquer escrita de HTML por string, inclusive de uma dependência
futura. É raro um projeto poder ligar isto sem refatorar nada; este pode.

## 2. Organização das telas — desktop

| # | Achado | Telas |
|---|---|---|
| O1 | **Largura sem regra.** Cinco telas esticam até a borda — em 1440px o nome fica a 1.400px do botão "Abrir"; duas param em 1080px; duas em 520–560px. *(Corrigido em 15/11/2026: a primeira versão desta linha dizia "coluna de 700px", lida de uma folha de contato reduzida à metade. Medido no CSS, é o contrário.)* | acessos, catálogo, equipe, estoque, financeiro · agente, conciliação · config, clientes |
| O2 | **Cada linha de lista é um cartão separado**, com borda e vão entre eles. Numa lista de 30 itens são 30 caixas — ruído que a escala de espaçamento existe para evitar | acessos, catálogo, equipe, estoque, financeiro |
| O3 | **Ação de tela em três lugares diferentes:** no topo da casca (portal), no título da seção, e solta no canto ("Salvar alterações") | config, catálogo, equipe, estoque |
| O4 | **A comanda, a tela mais usada no balcão, é a menos acabada:** `select` nativo, formulário em duas colunas apertadas, "REMOVER" em caixa alta sublinhado | comandas |
| O5 | **Cor de status em texto** — o saldo devedor é `color: var(--status-falta)`. O sistema de design mediu: nenhum status passa AA como texto sobre papel | `financeiro.css:159` |

## 3. Organização das telas — celular

| # | Achado | Telas |
|---|---|---|
| M1 | **Selo cortado pela barra de status:** "GRAV\|OU" — o `::before` absoluto passa por cima do selo quando a linha empilha | `agente.css:120` |
| M2 | Linha de lista empilha cada dado numa linha própria, com vãos grandes: o perfil da pessoa sozinho no meio do cartão | acessos, equipe |
| M3 | **Ação principal no topo**, longe do polegar e abaixo da dobra depois de rolar ("Salvar alterações", "Novo acesso", "Fechar o período") | config, acessos, catálogo, equipe, estoque, financeiro |
| M4 | O período quebra em duas linhas dentro do próprio controle | financeiro |
| M5 | Comandas abertas numa faixa horizontal cortada sem indicar que rola | comandas |
| M6 | Selo de alerta ("acabou", "4,5 L") desenhado como caixa com barra e fundo, que quebra quando o texto é longo | estoque |

## 4. Navegação e fluidez — o que a tecnologia de hoje acrescenta

O código da tela já chega antes do clique. **O dado não.** Ao abrir uma tela, ela ainda espera a
consulta — é a espera que sobra.

| # | O quê | Como |
|---|---|---|
| F1 | **Pré-carregar o dado, não só o código**, no mesmo hover/`pointerdown` que já existe | `queryClient.prefetchQuery` com a mesma chave da tela; o cache de 30s já evita buscar duas vezes |
| F2 | **Ação otimista com desfazer visível** nas escritas de um clique (confirmar agendamento, aceitar da fila) | `useOptimistic` do React 19 — a regra R-UX-18 já exige o rollback visível |
| F3 | **Listas longas sem custo de desenho** fora da tela | `content-visibility: auto` com `contain-intrinsic-size` (fila, extrato, revisão do agente) |
| F4 | Atalhos de tela no teclado (`N` novo, `/` buscar na tela) | R-UX-02 já pede teclado primeiro; hoje só existe o `⌘K` global |

Fora de propósito, de propósito: *Speculation Rules* (é para navegação entre documentos; isto é
SPA) e qualquer cache de dado da API no service worker (agenda tem nome de cliente e o computador
pode ser compartilhado — decisão de RT-INF-010 que continua certa).

---

## As rotinas, em ordem

Uma rotina por vez, como o projeto exige. A ordem é por consequência, não por gosto:

| Ordem | Rotina | Fecha | Por que nesta posição |
|---|---|---|---|
| ✓ | **RT-INF-014** — o símbolo e a porta do sistema | pedido do dono | Feita |
| 1 | **RT-INF-015** — o front em produção, com CSP estrita e Trusted Types | S1–S4 | Sem ela, nada do que está nesta lista chega a ninguém |
| 2 | **RT-INF-016** — moldes de página: três larguras (lista, formulário, grade), lista como uma superfície com divisórias, ação de tela num lugar só e no polegar no celular | O1–O3, O5, M1–M4, M6 | Uma peça corrige onze achados em treze telas; tela a tela seriam treze decisões |
| 3 | **RT-ATD-0xx** — a comanda do balcão, redesenhada | O4, M5 | Depende dos moldes; é a tela que mais merece o tempo |
| 4 | **RT-INF-017** — fluidez: dado pré-carregado, otimista com desfazer, listas leves, atalhos | F1–F4 | Melhor depois do molde, para medir uma vez |
