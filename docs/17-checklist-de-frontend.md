# Checklist de acabamento do front

**Por que este documento existe.** O sistema de design (`16-design-system.md`) está certo no
*porquê* e o front não entrega o *como*. Esta é a lista do que separa a implementação atual de um
produto que ninguém adivinha ter sido gerado — item a item, com o defeito nomeado, não com
adjetivo.

Ordem: tipografia primeiro, porque é o que se vê antes de qualquer outra coisa e o que estraga
todo o resto quando está errado.

---

## A — Tipografia

> O sintoma que o dono nomeou: *"essas letras não são legais"*. Ele está certo, e a causa é
> estrutural: **o produto hoje não tem fonte.** Tem a fonte que cada sistema operacional resolveu
> emprestar.

- [x] **A1 · A fonte de interface não existe fora do macOS.** `--fonte: "Avenir Next", Avenir,
      "Segoe UI", system-ui`. No Windows cai em Segoe UI; no Android, em Roboto. São três produtos
      com três aparências, e nenhuma delas foi escolhida. A economia que isso comprou — "não há
      download de fonte bloqueando a primeira tela" — some com auto-hospedagem e `font-display`.
- [x] **A2 · A identidade só aparece para quem usa Mac.** `Bodoni 72`/`Didot` são macOS. No Windows
      o "único gesto editorial" do sistema vira **Georgia** em silêncio. O gesto que justifica o
      negócio ser beleza não chega à maioria das telas.
- [x] **A3 · Os pesos são falsos.** `font-weight: 650` e `600` sobre pilhas estáticas fazem o
      navegador **sintetizar** o negrito: o desenho é esticado, não desenhado. É literalmente a
      "letra que não é legal" — borda suja, contraste errado.
- [x] **A4 · Não há escala óptica.** 11px em caixa alta e 30px de total usam o mesmo desenho e o
      mesmo tracking. Tipografia profissional aperta o grande e abre o pequeno.
- [x] **A5 · `-webkit-font-smoothing: antialiased`** afina o texto no macOS e não faz nada no
      resto — é mais uma fonte de divergência entre máquinas, não um acabamento.

## B — Menu lateral (referência: GitLab)

- [x] **B1 · Um único nível de hierarquia.** "Operação" + lista plana. A referência tem três:
      contexto (onde estou), seções (o que existe), fixados (o que eu uso).
- [ ] **B2 · Não há busca.** No GitLab a busca é o controle mais usado do produto inteiro.
- [ ] **B3 · Não há contadores.** Comandas abertas, fila de espera e alerta de estoque são números
      que o sistema **já tem** e não mostra onde a decisão acontece.
- [x] **B4 · Não recolhe.** 232px fixos são 18% de uma tela de 13" gastos permanentemente com seis
      palavras.
- [x] **B5 · O rodapé de usuário é um bloco, não um menu.** Conta, segundo fator e sair precisam de
      um popover; hoje "sair" é um ícone solto ao lado do nome.
- [ ] **B6 · Não há seletor de estabelecimento.** O sistema é multi-tenant desde o dia 0 e a casca
      não sabe disso.

## C — Barra do topo

- [x] **C1 · Sem busca global (⌘K).** É o que faz um portal parecer portal.
- [x] **C2 · "Salão / Agenda" não é trilha, é rótulo.** Numa ficha de cliente ou numa comanda não
      existe caminho de volta.
- [x] **C3 · Sem área de ações da página.** Cada tela inventa o próprio cabeçalho, e por isso
      nenhuma parece do mesmo produto.
- [ ] **C4 · A data ocupa o lugar mais nobre e não é clicável.**
- [ ] **C5 · O aviso de conexão vive fora da casca** e aparece como faixa solta.

## D — Login

- [x] **D1 · Split-screen com painel escuro e slogan** é o layout de login mais replicado que
      existe. É o item que mais grita "gerado".
- [x] **D2 · O "ritmo" `09:00 — 13:30 — 18:00` é ornamento puro.** Não codifica nada, não muda
      nunca, não informa ninguém. Sai.
- [x] **D3 · Rótulo em caixa alta 11px sobre campo de 16px** é formulário de 2019.
- [x] **D4 · O foco do campo só troca `border-color`** e compete com o outline global.
- [x] **D5 · Gerenciador de senha não preenche.** Os campos não têm `name`, `id` nem
      `autocomplete`. Não é estética: é a recepção digitando a senha inteira toda manhã.
- [x] **D6 · Sem "mostrar senha"** — e o teclado do celular erra maiúscula.
- [ ] **D7 · O erro é um bloco acima do formulário**, não um estado do campo que errou.

## E — Fluidez

- [x] **E1 · Troca de rota é corte seco.** Sem View Transitions, a navegação pisca.
- [x] **E2 · O esqueleto não tem relação com o conteúdo** que chega depois — ele só pulsa.
- [x] **E3 · A rolagem não é preservada** ao voltar de uma ficha para a lista.
- [x] **E4 · Hover e active usam a mesma duração**, então nada tem peso diferente de nada.

## F — Rigor

- [ ] **F1 · Densidade única.** Uma agenda de doze profissionais e uma ficha de cliente pedem
      réguas diferentes.
- [ ] **F2 · Sem modo escuro.** O painel de parede (`/painel`) fica aceso a noite inteira.
- [ ] **F3 · Auditoria de acessibilidade** continua pendente desde `RT-INF-010`.

---

## Estado — 11/09/2026

**Feitos:** toda a tipografia (A1–A5), a estrutura da lateral menos contadores e seletor de
estabelecimento (B1, B4, B5), busca global e trilha no topo (C1, C2), e o login inteiro (D1–D6).
Mais a transição de vista entre rotas (E1) e a hierarquia de tempo (E4).

**B1 exigiu mudar o backend**, e é a parte que mais importa registrar: agrupar os menus no front
recriaria no JavaScript a regra que `/me/capabilities` existe para eliminar (inviolável nº 6). O
contrato ganhou `grupos` e `Menu.grupo`, com teste garantindo que todo menu aponta para um grupo
que existe — um menu órfão desapareceria da lateral sem erro nenhum.

**Não feitos, e por quê:**

- **B2 (busca na lateral)** — dispensado: a busca vive no topo, num lugar só. Duas caixas de busca
  na mesma tela é o defeito que ela veio consertar.
- **B3 (contadores)** — o servidor ainda não manda os números. Inventá-los no front seria a mesma
  violação que B1 acabou de corrigir.
- **B6 (seletor de estabelecimento)** — não existe endpoint de troca de salão. Um seletor com nada
  atrás é pior que nenhum.
- **C3 (área de ações da página)** — o lugar existe no desenho, mas nenhuma tela tem ação para pôr
  lá ainda. Entra junto com a primeira que tiver.
- **C4, C5** — a data virou o relógio do salão, no fuso do estabelecimento (ADR-0009): é dado real
  e é o mais consultado num balcão. O aviso de conexão continua fora da casca.
- **D7, E2, E3, F1–F3** — pendentes.

## Segunda passada — 12/09/2026: o conteúdo das telas

A casca ficou pronta e apareceu o defeito seguinte: **cinco telas, cinco cabeçalhos, três jeitos
de dizer "Carregando…"** — todos em texto puro. Cada tela parecia razoável sozinha; o conjunto
parecia cinco produtos.

### As primitivas

`layout/Pagina.tsx` — `Pagina`, `Vazio`, `Esqueleto`, `Falha` e `Acao`. Toda tela passa por ela.

**O título da página não se repete mais.** A trilha da barra de topo é o `h1`; cada tela tinha um
próprio dizendo a mesma palavra logo abaixo, comendo oitenta pixels de altura sem informar nada. E
as ações principais sobem para o topo, num lugar só — aprendido uma vez, certo em todas as telas
(fecha **C3**).

**Dica de campo não é estado vazio.** A primeira versão trocou a dica da busca de clientes pelo
`Vazio` centralizado, e ficou pior: `Vazio` responde "não há nada aqui" e mora no meio da área de
trabalho; a dica responde "digite aqui" e mora debaixo do campo, alinhada com ele. Consistência é
usar a mesma peça para a mesma coisa — não a mesma peça para coisas diferentes.

### A travada, nomeada

`useAgendaDoDia` não guardava o dia anterior. **Cada clique na flecha derrubava a grade inteira** —
doze colunas, a régua de horas, todos os blocos — e a trocava por um parágrafo, para remontar tudo
meio segundo depois. Não é a demora que se sente como travada: é o desmonte.

Agora o dia que sai fica na tela, esmaecido e sem clique, até o novo chegar. Esmaecido de propósito:
mostrar dado de ontem sem avisar é pior que a tela branca.

Junto: esqueleto com a **forma** do conteúdo em vez da palavra "Carregando" (**E2**), e rolagem
guardada por rota — o container que rola não é a janela, então trocar de tela caía no meio dela
(**E3**).

### Uma tela quebrada não leva o menu

Há **duas** barreiras agora: a de fora (em `App`) e a de dentro (em `Casca`, ao redor do `Outlet`,
com `key={pathname}`). Com uma só, uma tela quebrada apagava a lateral junto e a pessoa perdia até
o caminho para sair dali.

### E dois defeitos de sistema que só apareceram olhando o conteúdo

- **O anel de foco era cor de status** — ver `16-design-system.md`, seção 3c.
- **Rótulo, seção e etiqueta tinham o mesmo desenho** — ver seção 3b.

### O que a prévia do sistema mudou

`preview/sistema.tsx` monta a **casca com as telas reais dentro**, e o `fetch` falso responde com
meio segundo de atraso de propósito: prévia que responde no mesmo quadro nunca mostra o estado de
espera, e o estado de espera é metade do que faz um sistema parecer fluido. As prévias anteriores
julgavam uma tela por vez — e é assim que cinco telas acabam com cinco cabeçalhos.

## A lição que custou uma tela branca

Ao introduzir `grupos` no contrato, a casca passou a fazer `capacidades.grupos.map(...)`. O
backend em execução era de **antes** do campo existir: `undefined.map` estourou, o React
desmontou a árvore inteira e sobrou `<body>` vazio — agenda, comandas e financeiro derrubados por
causa de um menu.

Três correções, e cada uma vale para além deste caso:

1. **A casca tolera contrato de outra versão.** Sem `grupos`, a lista sai plana. Menu apontando
   para grupo inexistente cai num balde no fim, em vez de sumir — seção inteira desaparecendo sem
   erro nenhum é pior do que seção fora de ordem. Front e back saem de versão o tempo todo:
   durante um deploy, com uma aba velha aberta, com o service worker servindo o bundle de ontem.
2. **Existe uma barreira de erro** (`layout/Barreira.tsx`) por fora do roteador. Nenhuma falha de
   render volta a virar tela branca: aparece o que houve, o que fazer, e a mensagem técnica para
   copiar. Para quem está no balcão, tela branca é indistinguível de internet caída.
3. **`Intl.DateTimeFormat` lança com fuso inválido** — não devolve nada, derruba o render. O
   relógio do salão agora cai no fuso do navegador em vez de levar a aplicação junto. O fuso vem
   do banco como texto livre; basta um cadastro com grafia errada.

Regra que fica: **um campo que o front só usa para ORDENAR não pode derrubar o que ele ordena.**

## O que isto NÃO propõe mudar

A direção de `16-design-system.md` está certa e continua valendo: back-office neutro, cor só onde
carrega informação, forma além de cor, escala de espaçamento fixa, vinho como única cor de marca.
Nada aqui é troca de direção — é a execução que faltava.
