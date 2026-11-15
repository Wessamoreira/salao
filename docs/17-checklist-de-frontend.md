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
- [x] **B2 · Não há busca.** No GitLab a busca é o controle mais usado do produto inteiro.
      *(23/09/2026: o `⌘K` passou a achar **cliente**, e não só seção — pelo nome, pelo apelido
      ou pelos últimos dígitos, com a mesma consulta da tela de clientes. Comanda continua fora,
      porque o servidor não tem busca de comanda: caixa que promete e não acha é pior do que
      caixa que diz o que faz.)*
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
- [x] **C4 · A data ocupa o lugar mais nobre e não é clicável.**
      *(23/09/2026: o relógio virou a porta da agenda de hoje — é o que se faz depois de
      olhar a hora num balcão. Sem `?dia=`, porque hoje é o padrão da tela e um dia
      calculado no clique apontaria para ontem numa tela deixada aberta a noite inteira.
      Quem não tem agenda no menu continua vendo um relógio, e não um atalho.)*
- [x] **C5 · O aviso de conexão vive fora da casca** e aparece como faixa solta.
      *(23/09/2026: virou uma pastilha na barra de topo, ao lado do relógio — estado do
      sistema pertence à moldura do sistema. Na porta do sistema, que não tem barra, ele
      continua flutuando. E passou a distinguir internet caída de servidor mudo, a avisar
      quando a conexão volta, e a oferecer "tentar de novo" no único caso em que
      ninguém avisa sozinho.)*

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
- [x] **D7 · O erro é um bloco acima do formulário**, não um estado do campo que errou.
      *(22/09/2026: o aviso continua num lugar só — repetir a mesma recusa em cada campo a faria
      ser lida três vezes —, mas agora os campos recusados são marcados (`aria-invalid`) e apontam
      para ele (`aria-describedby`), e o foco volta para a **senha**, com o conteúdo selecionado.
      Só a recusa de credencial marca campo: "tentativas demais" não é culpa do que foi digitado.)*

## E — Fluidez

- [x] **E1 · Troca de rota é corte seco.** Sem View Transitions, a navegação pisca.
- [x] **E2 · O esqueleto não tem relação com o conteúdo** que chega depois — ele só pulsa.
- [x] **E3 · A rolagem não é preservada** ao voltar de uma ficha para a lista.
- [x] **E4 · Hover e active usam a mesma duração**, então nada tem peso diferente de nada.

## F — Rigor

- [x] **F1 · Densidade única.** Uma agenda de doze profissionais e uma ficha de cliente pedem
      réguas diferentes.
      *(23/09/2026: a grade da agenda ganhou régua de 15, 30 ou 60 minutos por linha, guardada
      por máquina (R-UX-11) — e a linha continua com 42px em todas, então o que muda é quanto
      tempo cabe na tela, não o tamanho da letra. Fica aberto o outro lado do item: densidade é
      da GRADE, e listas e fichas seguem numa régua só. Isso é decisão do sistema de design, não
      da agenda.)*
- [ ] **F2 · Sem modo escuro.** O painel de parede (`/painel`) fica aceso a noite inteira.
- [x] **F3 · Auditoria de acessibilidade** — feita em 22/09/2026, e **contínua**: o axe-core roda
      dentro da suíte, em nove telas, e falha nomeando a regra, o elemento e o link. Contraste fica
      de fora ali (o jsdom não calcula cor computada, e uma regra que passa sempre é pior que
      nenhuma) — ele continua medido à mão na tabela de `16-design-system.md`.

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

## Terceira passada — 22/09/2026: fluidez, e o que estava quebrado sem ninguém ver

O pedido do dono: *"deixa mais profissional, fluido, sem travar, melhor usabilidade, sem parecer
que uma IA fez"*. A auditoria achou primeiro uma coisa que não é de acabamento nenhum.

### O item zero — feito antes do plano

**`npm run typecheck` não verificava nada.** Era `tsc --noEmit` sobre o `tsconfig` de solução, que
tem `files: []` e só referências: nenhum arquivo era lido. Um erro de tipo plantado em `src`
passava — e passou, no teste que fiz para provar. Quem lê os arquivos é `tsc -b`, e ele acusava
quatro erros em três telas: o dublê de `ErroDaApi` nos testes sem a assinatura do real. Ou seja,
**`npm run build` estava quebrado**, e nenhuma verificação dizia isso.

Durou porque **a CI não toca no front**: não há `npm` em lugar nenhum do `ci.yml`. A suíte Java
verde carregava a impressão de que estava tudo verificado.

### As seis faixas, na ordem

| # | Defeito | Evidência medida |
|---|---|---|
| 1 | ~~A CI ignora o front~~ **feito** | job `front`: `npm ci`, lint, tipos, testes e **build**, com o pacote publicado como artefato. O `imagem` passou a depender dele |
| 2 | ~~Toda ação espera a ida e a volta~~ **feito nas duas telas quentes** | comanda e preços usavam a resposta só para invalidar tudo: dois a três pedidos por clique |
| 3 | ~~Um pacote só, sem divisão por rota~~ **feito** | era 411 KB (121 KB comprimido) num arquivo; ficou 295 KB (93 KB) de entrada, com cada tela num pedaço de 1 a 33 KB |
| 4 | ~~Cada tela inventou o próprio cartão~~ **feito** | três definições idênticas viraram uma primitiva; seção deixou de ser caixa |
| 5 | ~~O erro não mora no campo que errou (D7)~~ **feito na entrada** | e o login não tinha teste nenhum — a porta do sistema |
| 6 | ~~Acessibilidade nunca foi medida (F3)~~ **feito** | axe-core em 9 telas, dentro da suíte que a CI roda |

### Faixa 2, e o que ela achou

**A resposta da ação era jogada fora.** Todo endpoint da comanda devolve *a comanda* ou *a conta*
inteira — e o front descartava isso para invalidar as três consultas da tela. Cada clique custava a
ida da ação e a volta da releitura, com a cliente na frente. Agora a metade que chegou é gravada
direto e só a outra é pedida; enquanto ela não chega, fica **esmaecida e sem clique**, o mesmo
idioma que a agenda já usava para o dia anterior. Número velho calado é que não pode (RN-ATD-008).

Em preços, aprovar e recusar devolvem o painel já sem o cartão decidido: ele some no clique.

**E um defeito de dinheiro apareceu no caminho.** No formulário de pagamento, o campo de valor se
limpa no envio, mas o envio cai no `restante` quando o campo está vazio — e o restante só é relido
depois. **Dois cliques em "Registrar" cobravam a mesma quantia duas vezes.** Agora todo controle que
repetiria a ação fica desarmado enquanto ela está no ar. Nos lançamentos o formulário já se limpava
sozinho (a trava lá é cinto e suspensório); no pagamento, não havia nada.

Os dois têm teste, e os dois testes foram conferidos por mutação — inclusive um que passava por
acaso, casando com o "R$ 0,00" do desconto em vez do "falta receber".

**Falta nesta faixa:** fila de espera, conciliação e clientes continuam invalidando e relendo. Na
fila, o ganho real exige o servidor devolver a vaga recalculada no registro da chamada — ele já a
calcula para decidir se a espera cabe.

### Faixa 3 — e a armadilha dela

Abrir a agenda no celular da recepção baixava conciliação, preços, o agente e a revisão de IA
junto: 121 KB comprimidos para usar uma tela. Agora são 93 KB de entrada, e cada tela é um pedaço
próprio — a conciliação sozinha valia 9 KB que todo mundo carregava.

**Dividir sem precarregar troca um problema por outro:** a primeira tela fica leve e cada troca de
menu ganha uma espera. Por isso o mesmo `import()` serve às duas coisas, e a casca começa a baixar
o pedaço **quando o ponteiro encosta** no item do menu — ou quando o foco chega nele, pelo teclado.
O intervalo entre decidir e clicar existe de graça; é ele que paga o download.

E a espera, quando acontece, é **dentro da casca**: menu, trilha e relógio continuam na tela, com
o esqueleto no lugar do conteúdo. Por fora, trocar de tela apagaria a casca por um instante — o
corte seco que a transição de vista (E1) veio tirar.

### Faixa 4 — seção não é caixa

Conciliação, preços e fila tinham a **mesma caixa branca copiada três vezes**: `.cnc-bloco`,
`.prc-bloco`, `.fil-bloco`, idênticas até no `padding`. E as três eram caixa de canto arredondado
com 24px de padding — exatamente o que a lista final de `16-design-system.md` proíbe
("espaçamento uniforme de 24px · canto muito arredondado"). Não era falta de direção: era a
direção não sendo seguida.

Agora existe `layout/Secao.tsx`, uma só, e ela é **estrutura**: título, régua fina e ar. O papel
branco ficou reservado ao que é **objeto** — o cartão da sugestão, a vaga com quem ligar, o número
do mês na conciliação. A tela passou a ter dois níveis de leitura em vez de um, e some a pilha de
caixas iguais que é o retrato do gerado.

O ganho foi além do visual: no celular, sem o padding da caixa, os três botões de cada candidata
cabem numa linha só.

### Faixa 5 — o erro, e um buraco maior que ele

O aviso de login continua num lugar só, de propósito: repetir a mesma recusa em cada campo a faria
ser anunciada três vezes por um leitor de tela. O que mudou é a **ligação** — os campos recusados
são marcados e apontam para o aviso, e o foco volta para a **senha**, com o conteúdo selecionado,
porque é quase sempre ela que está errada: o e-mail a recepção digita certo, ou o gerenciador
preenche. E só a recusa de credencial marca campo: "tentativas demais" não é culpa do que foi
digitado, e apontar um campo ali mandaria corrigir o que está certo.

**O buraco maior:** a tela de entrada não tinha teste nenhum — a porta do sistema, o único lugar
por onde todo mundo passa todo dia. Agora tem cinco, e os três que importam foram conferidos por
mutação.

### Faixa 6 — medida, não opinada

`axe-core` (o mesmo motor da extensão da Deque) roda dentro da suíte, em nove telas, e falha
listando regra, elemento e link. Não é auditoria de uma vez: é verificação a cada commit, porque a
CI roda a suíte.

Ele pega o estrutural — rótulo desassociado, botão sem nome, `aria` inválido, cabeçalho fora de
ordem. **Não** pega julgamento: se o rótulo faz sentido, se a ordem de tabulação é a da tarefa, se
a mensagem diz o que fazer. Isso continua sendo revisão humana, e o auxiliar diz isso no próprio
javadoc para ninguém confundir cobertura com qualidade.

**Nenhuma tela tinha violação estrutural** — o que é resultado do cuidado das rotinas anteriores,
não sorte. Para provar que a verificação funciona, desassociei o rótulo do campo de serviço na
comanda: reprovou com "select-name (critical)".

**E apareceu uma instabilidade real:** o axe é pesado, e dois testes sem relação com ele passaram a
estourar o prazo de 1s do `findBy` em execuções alternadas. O prazo subiu para 3s no preparo da
suíte — teste instável custa mais caro que teste lento, porque ensina a ignorar vermelho.

**Por que o build entra no CI e não só os testes:** os testes montam componentes, não o pacote —
um import quebrado passa por lint, tipos e testes e só aparece no `vite build`. Foi exatamente o
caso do item zero.

**1 a 3 respondem a "fluido, sem travar". 4 responde a "sem parecer que foi IA"** — pilha de caixas
brancas iguais é o retrato do gerado, e a régua deste produto pede papel, densidade e traço, não
caixa dentro de caixa.

### O que fica para depois, por decisão do dono (22/09/2026)

`/estoque` e `/financeiro` estão no menu e mostram "Em breve", com o backend pronto há semanas. É a
maior falha de acabamento do produto hoje, e são duas telas grandes: entram **depois** destas
faixas, para nascerem sobre o sistema visual novo em vez de serem reescritas.

## Quarta passada — 23/09/2026: o que sobrou do checklist

### Faixa 1 — o dia é do salão, e está no endereço

A agenda calculava "hoje" com `Intl.DateTimeFormat('en-CA')` **sem fuso**: o dia da máquina.
Num salão em Manaus, entre 23h e meia-noite de lá, a recepção abria a agenda de amanhã — e a
tela não dava nenhuma pista do engano. É o inviolável nº 3 e a ADR-0009 valendo em todo lugar
menos onde se olha o dia inteiro.

No mesmo lugar, o dia morava num `useState`: recarregar voltava para hoje, o endereço não podia
ser mandado para ninguém, e o "voltar" do navegador não desfazia a seta.

O que entrou (R-UX-21): o dia vem do fuso do estabelecimento; o dia está na URL (`?dia=`, com
`replace` para a seta não entupir o histórico); hoje é o endereço sem parâmetro; e um dia
impossível colado ali (`2026-02-31`) cai em hoje em vez de derrubar a tela — `Intl` lança para
data inválida, e era uma tela branca a um link torto de distância.

E o relógio do topo deixou de ser enfeite: ele leva à agenda de hoje (C4).

Sete mutações, sete mortas — incluindo a que devolve o dia da máquina e a que deixa o dia
inválido passar.

### Faixa 2 — o estado da conexão é do sistema, não da tela

O aviso de conexão era um retângulo fixo no canto inferior direito, por fora da casca, cobrindo
o conteúdo: a forma exata do aviso de cookie. Foi para a barra de topo, ao lado do relógio.

Olhando para ele de perto, apareceram três defeitos que não eram de lugar:

1. **Uma frase para duas quedas.** "Sem conexão" dito para quem está com o WhatsApp aberto
   ensina a recepção a não acreditar no aviso. Agora são dois diagnósticos — e, com os dois
   sinais acesos ao mesmo tempo (que é o caso comum: a rede cai e a requisição em voo falha),
   manda o que está perto da pessoa e ela pode resolver.
2. **A volta não era avisada.** Quem viu o aviso não sabia quando podia salvar de novo; o jeito
   de descobrir era tentar e ver se dava errado. E a rede voltando deixava gravado "servidor
   fora do ar" — o eco da queda anterior — até alguém pedir algo ao servidor.
3. **Servidor mudo não tinha saída.** Internet voltando, o navegador avisa sozinho; servidor
   voltando, ninguém avisa. Só ali existe "tentar de novo", e ele refaz as consultas da tela.

Dez testes novos, sete mutações, sete mortas.

### Faixa 3 — a régua da grade

A agenda tinha uma densidade só: 30 minutos por linha, 84px por hora. Funciona para o salão de
três profissionais que serviu de canvas — e é a régua errada para os outros dois casos. Com doze
colunas, ver a tarde exige rolar o dia inteiro; com três, sobra tela.

Agora são três réguas — 15, 30 e 60 minutos por linha —, guardadas por máquina. O que muda é o
**tempo** de uma linha, não a altura dela: 42px em todas, que é onde a hora e o nome cabem sem
apertar. Mudar a altura seria letra menor disfarçada de zoom.

Apertar a régua tinha um preço escondido que só apareceu na tela: na grade compacta a tarja de
meia hora mede 21px, cabe uma linha de texto, e a segunda — o **nome da cliente** — sumia por
baixo da borda. Grade em que não se lê quem é não serve para nada. A tarja baixa passou a pôr o
nome ao lado da hora, e a medida é a altura dela própria: um encaixe de 15 minutos é baixo em
qualquer régua.

Oito mutações, oito mortas.

### Faixa 4 — a busca acha gente

O `⌘K` ia só a seções do sistema. O comentário no topo do arquivo explicava por quê — *"procurar
cliente exige um endpoint de busca que não existe"* — e terminava com uma promessa: *"quando a
busca de dado existir, ela entra AQUI, como uma segunda seção"*.

O endpoint existia desde a RT-CLI-006. É o mesmo que entende "os quatro últimos dígitos"
(RN-CLI-009), e é o caso mais comum do balcão: o telefone toca. O caminho era ir ao menu, abrir
clientes, digitar. Agora são duas teclas e o nome.

Três decisões dentro disso:

1. **Uma lista para o teclado, duas na tela.** A seta atravessa as seções e entra nos clientes
   sem tropeço porque o índice percorre uma lista só — um índice por grupo seria reinventar o
   que todo mundo já tem no dedo.
2. **A ficha virou endereço** (`/clientes?cliente=…`), porque senão não havia como abri-la de
   fora: a tela já está montada quando a paleta manda, e estado inicial corre uma vez só. De
   quebra, o voltar do navegador passou a fechar a ficha em vez de largar a busca.
3. **Quem não tem a tela não procura.** Devolver fichas para quem não pode abri-las seria a
   mesma porta com outro nome (inviolável nº 6).

Sete mutações, sete mortas — e duas delas mostraram testes fracos meus antes de morrerem: um que
passava só porque a consulta ainda não tinha tido tempo de sair, e um grupo vazio que virava
título sozinho e ninguém olhava.

### Faixa 5 — a tela de clientes, e uma barra que quebrava no tablet

A tela de clientes usava um quarto da tela. Num monitor de 1440px, um campo de 520px encolhido
no canto superior esquerdo, duas linhas de texto embaixo dele, e **setecentos pixels de nada**
onde a ficha ainda não estava. A coluna da ficha era reservada desde o primeiro instante — antes
de existir ficha.

O conserto não foi encher o vazio: foi **compor**. A tela tem duas formas, e troca de uma para a
outra num clique:

- **procurando** — só existe a busca, então ela é a página: uma coluna de 640px centrada, com o
  campo, a leitura do termo e os resultados na mesma largura. A margem virou composição em vez
  de sobra;
- **lendo** — abrir alguém muda o assunto: a ficha ocupa o espaço e a busca encolhe para a
  coluna da esquerda, continuando à mão, porque outra cliente pode ligar no meio.

Três acabamentos junto:

1. **A lupa dentro do campo**, como na busca da casca: diz "digite para procurar" sem gastar uma
   palavra, e o foco passou a ficar na moldura inteira em vez de num anel por dentro dela.
2. **Os três jeitos de procurar viraram forma, não frase.** `Marilda` nome · `Ju` apelido ·
   `4321` fim do telefone. Os quatro dígitos são o atalho que resolve na primeira tentativa
   (R-UX-03) e o que ninguém descobre sozinho — e a frase que os descrevia foi encurtada, porque
   exemplo e explicação não podem fazer o mesmo trabalho.
3. **A ficha ganhou saída.** Ela virou endereço na faixa anterior, e sair dela era só clicar em
   outra pessoa ou apertar Esc no campo. Agora há um × no canto, além dos dois.

E um defeito real, achado ao conferir a faixa em 700px: abaixo de 820px aparece o botão de abrir
o menu, que é um **quarto** item na barra de topo — e a barra tinha três colunas declaradas. O
relógio caía numa segunda linha do grid, com a régua dele pendurada no meio do nada e a barra com
o dobro da altura. No tablet do balcão, que é exatamente onde essa faixa existe para funcionar.

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

## Faixa 6 — a tela que faltava para o sistema sair de uma pessoa só

`/acessos` (RT-IAM-009, 25/09). Três coisas que valem para qualquer tela de administração:

1. **Perfil se escolhe lendo o que ele faz.** "GERENTE" não diz se a pessoa passa a ver o
   dinheiro do salão, e é essa a pergunta de quem escolhe. As frases vêm do servidor, com um
   teste provando que nenhuma permissão ficou sem frase — um dicionário no front envelheceria
   calado.
2. **Cada ato tem o seu botão.** Mudar perfil e definir senha derrubam a sessão da pessoa;
   corrigir o nome, não. Escondê-los dentro de um "salvar" faria um botão manso produzir uma
   consequência que ninguém pediu — e a pessoa do outro lado descobriria sendo desconectada no
   meio de um atendimento.
3. **O que não dá para fazer diz por quê, na linha.** A própria conta não se rebaixa nem se
   desativa (RN-IAM-015). Em vez de botão cinza, está escrito o motivo e para onde ir.

E o "copiado" só aparece quando copiou: sem HTTPS a área de transferência falha em silêncio, e
prometer a cópia faria alguém colar nada na mensagem com a senha.

## Faixa 7 — a prévia como instrumento, não como enfeite

`/estoque` e `/estoque/contagem` (RT-EST-013, 26/09). A rodada de mutação matou 15 de 15 e **não
achou nenhum destes quatro** — todos apareceram ao olhar a prévia:

1. **A consulta quebrava no caso normal.** `204` vira `undefined`, e o TanStack Query recusa
   `undefined` como dado: "não há contagem aberta" — o estado de quase todo dia — levava a tela
   inteira ao erro. Teste unitário não pega, porque o dublê devolvia o que o real não devolve.
2. **O campo mais importante da tela estava invisível**: sem borda e sem fundo, porque a regra de
   campo não o alcançava. Ao lado do botão, parecia que só havia botão.
3. **Uma unidade fixa em "un"** faria 500 ml de tinta virarem "500 un".
4. **Produto desativado sumia junto com o botão que o traz de volta.**

E uma lição de processo: escrever um `*.css` que já existia **apagou** o estilo do leitor de
código de barras. Arquivo que já existe se edita, não se escreve por cima — e a prévia foi o que
mostrou, porque o teste de tela não olha CSS.

## Faixa 8 — a última tela, e o que o teste achou antes da tela

`/financeiro` (RT-FIN-013, 26/09). A rodada de mutação matou 17 de 17; os dois defeitos reais
foram achados pelos **testes que eu estava escrevendo**, e não pela rodada:

1. **"Período anterior" de um mês inteiro andava trinta e um dias**, caindo em 29/01–28/02 — que
   não é o mês anterior e não é nada. Um mês inteiro anda um mês; qualquer outro recorte anda o
   próprio tamanho.
2. **A tela disparava três consultas antes de saber a permissão.** Quem tem só
   `financeiro:read:own` via três 403 chegarem antes da explicação de por que a tela não abre.

E um terceiro, achado ao rodar o teste pela primeira vez: `vi.useFakeTimers()` falsifica todos os
relógios e **trava `findBy*` e o `userEvent`** — dezessete testes morreram em timeout dizendo "não
achei o elemento", que é a pista mais enganosa possível. `{ toFake: ['Date'] }` é o que se quer
quando a intenção é só fixar o dia.

Duas decisões desta tela que valem para as próximas:

- **a tela é o período.** Tudo pende de "de/até", com um controle só no alto. Cada seção com o seu
  recorte produziria a conversa mais cara do sistema: duas pessoas olhando números diferentes e
  concluindo que uma está errada;
- **a prévia é a lista e é a confirmação.** A tabela que responde "quem tem a receber" é
  literalmente a que o servidor usa para calcular o repasse, com o mesmo hash. Uma tabela "de
  visualização" ao lado garantiria que um dia as duas divergissem.

E o `plural` que eu tinha escrito no estoque apareceu de novo aqui, dois dias depois. Virou
`layout/plural.ts` — "1 pessoa(s)" é o jeito de não decidir, e quem lê é que paga.

## Faixa 9 — a agenda passou a escrever

`/agenda` (RT-AGD-013, 26/09). Era a tela que a recepção deixa aberta o dia inteiro e **só lia**.

Dois defeitos reais, e nenhum veio da rodada de mutação (13 de 13 mortas):

1. **`pode()` derrubava a tela inteira** quando as capacidades chegavam sem o campo de
   permissões: `capacidades?.permissoes.includes` tem o `?.` no primeiro ponto e não no segundo.
   É exatamente a lição de `grupos` registrada na faixa anterior — *um campo que o front usa para
   decidir o que desenhar não pode derrubar o que ele desenha*. A casca tinha aprendido; `pode`
   não. Quem achou foi o teste que já existia, ao ver a tela sumir.
2. **O painel do gesto ancorava na página, não na grade** (`.agenda-corpo` era `static`): ele
   subia para o canto superior da tela, longe do horário que o gesto acabou de desenhar. Achado
   na prévia.

E um engano de teste que se repetiu duas vezes na mesma hora: **`fireEvent` é síncrono e a
escrita sai num microtask.** A asserção "não escreveu nada" passava mesmo quando escrevia. Onde o
teste afirma uma ausência depois de um `fireEvent`, ele precisa esperar o microtask — senão está
provando o relógio, não o código.

Três decisões que ficam:

- **o gesto define o período; o painel pergunta só o quê.** Seis campos obrigatórios fariam a
  recepção voltar para o papel;
- **arrastar define o início; a duração é do serviço.** Um arrasto torto não pode virar uma
  coloração de vinte minutos — e a tela mostra em que hora termina, para a diferença ser visível;
- **remover bloqueio não pede confirmação; cancelar atendimento pede.** Um tem ninguém do outro
  lado; o outro chega a uma cliente.

## O que isto NÃO propõe mudar

A direção de `16-design-system.md` está certa e continua valendo: back-office neutro, cor só onde
carrega informação, forma além de cor, escala de espaçamento fixa, vinho como única cor de marca.
Nada aqui é troca de direção — é a execução que faltava.

## Quarta passada — 15/11/2026: o app instalado (`RT-INF-013`)

O pedido do dono: *"PWA que se comporte como app, e no desktop algo realmente bonito"*. A
medição achou primeiro o que estava quebrado sem ninguém ver:

- **No iPhone instalado, a barra de topo ficava debaixo do relógio.** O `index.html` pedia status
  bar translúcido e não tinha `viewport-fit=cover`: o texto branco do sistema ficava sobre a barra
  branca, e nenhuma área segura era respeitada.
- **O splash era bege** (`#f3f2ef`), resto de uma paleta abandonada — e a sequência de abertura
  piscava bege → escuro (boot) → claro (login) → escuro (lateral).
- **A versão nova assumia sozinha** (`skipWaiting`) e apagava o cache da anterior com a aba
  aberta usando-o. Ver R-UX-23.
- **A prévia do sistema não abria:** importava `SemTela`, apagado em RT-FIN-013. Agora ela monta a
  agenda real dentro da casca — a moldura só se julga com a tela que mais se olha dentro dela.

O que entrou, com o motivo de cada um em `16-design-system.md` ("A moldura") e na doc da rotina:

- [x] **G1 · A moldura.** Desktop, celular, porta do sistema e splash são o mesmo objeto.
- [x] **G2 · Abas no polegar** abaixo de 820px, com "Mais" abrindo a navegação completa como folha
      inferior (R-UX-22). O hambúrguer saiu.
- [x] **G3 · Janela própria no desktop:** Window Controls Overlay, faixa arrastável com a seção e o
      salão, e título da janela acompanhando a tela.
- [x] **G4 · Versão nova espera o clique** (R-UX-23), e é procurada a cada hora.
- [x] **G5 · "Instalar o app"** no menu da conta, só quando o navegador deixa pedir.
- [x] **G6 · Ícones** separados por finalidade (`any`, `maskable`, `apple-touch-icon`) e capturas
      `wide`/`narrow` para a janela de instalação.
- [x] **G7 · A agenda no celular** sem cartão dentro da folha, e com o cabeçalho que não corta a
      seta de próximo dia.
- [x] **G8 · Dependências** atualizadas dentro das faixas declaradas (React 19.3, Vite 8.3,
      TanStack Query 5.107). TypeScript 7, Vitest 5 e jsdom 31 são troca de versão maior — ficam
      para uma rotina própria, não de carona numa de desenho.

**Não feito, e por quê:** modo escuro da folha (F2) — a moldura já é escura, mas a folha exige
refazer a medição de contraste par a par; contadores nas abas (B3) — o servidor ainda não manda os
números; arrastar a folha inferior para fechar — sem o gesto, uma alça desenhada seria promessa
falsa.

