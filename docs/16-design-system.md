# Sistema de design

**Padrão do projeto.** Toda tela segue isto. Uma tela que precise fugir do sistema é uma decisão a
justificar por escrito aqui, não uma exceção silenciosa no CSS.

> **Os valores não moram neste arquivo.** Eles moram em [`design/tokens.css`](../design/tokens.css),
> que o front importa. Uma tabela de cores num `.md` diverge do código na terceira semana, e
> diverge em silêncio — mesma razão pela qual regra de negócio mora em `regras.md` e não dentro de
> cada rotina. Aqui está o **porquê**; lá estão os números.

## Como este sistema foi produzido

| Etapa | Ferramenta | O que produziu |
|---|---|---|
| Desenho das três telas centrais | skill `design` | Canvas com agenda, comanda, painel e vocabulário |
| Crítica estruturada | skill `design:design-critique` | 7 achados aplicados — ver "O que a crítica corrigiu" |
| Formalização em sistema | skill `design:design-system` | Este documento e `design/tokens.css` |
| Acabamento na implementação | skill `frontend-design` | Casca responsiva e identidade material em `RT-INF-010` |
| Auditoria de acessibilidade | skill `design:accessibility-review` | Pendente do `RT-INF-010` |

Os arquivos-fonte do canvas vivem em `design/*.dc.html` e são re-semeados a cada alteração — o
canvas publicado nunca é editado direto.

## A direção, em uma frase

**Back-office de recepção com régua de horários.** Branco e cinza frio organizam o trabalho, a
lateral escura ancora a navegação e uma única cor de marca identifica o salão. A personalidade vem
do ritmo da agenda e dos detalhes próprios da operação — não de uma estética genérica de luxo.

## As quatro decisões que sustentam tudo

### 1. Cor operacional só onde carrega informação

Status de agendamento, alerta de validade, divergência financeira. **A área de trabalho é neutra.**
A cor de marca e o aço neutro aparecem na identidade e na orientação da casca, em áreas pequenas
e previsíveis; eles não codificam estado nem competem com o conteúdo operacional.

E cor **nunca em texto**: a medição de contraste mostrou que nenhum dos status passa em AA sobre
papel. Eles são borda, barra e fundo claro — o que já era a regra, agora com o motivo medido.

Os quatro status compartilham chroma (0.115) e luminosidade (0.62) em oklch, variando só a matiz —
é isso que faz os quatro pesarem igual na tela, em vez de um gritar mais alto por acidente de
saturação. Uma **cor de status** nunca aparece em bloco cheio: entra como borda, barra lateral de
3px, ou fundo de luminosidade alta na mesma matiz. O vinho sólido é reservado a pequenos elementos
de identidade, como o avatar, sempre com contraste medido.

### 2. Forma, além de cor

Daltonismo é comum e o contraste AA é obrigatório. **Nenhum status é distinguível só pela cor:**

| Status | Cor | Forma |
|---|---|---|
| Confirmado | verde | borda sólida |
| Solicitado | âmbar | borda tracejada |
| Em atendimento | azul | barra de progresso na base |
| Química agindo | neutro | hachura diagonal |
| Não compareceu | vermelho | hachura + texto riscado |

Em preto e branco, a tela continua legível. É o teste.

### 3. Tipografia com um único gesto

**Bodoni Moda** aparece somente na identidade do estabelecimento e nos totais. O negócio é
beleza — um gesto editorial se justifica. Em texto operacional, nunca. Na casca ela aparece **uma
vez**: na placa do salão.

**Archivo** em todo o resto, com `font-feature-settings: "tnum"` ligado. Numeral tabular não é
preciosismo: numa grade de horários, número que não alinha em coluna força o olho a reprocessar
cada linha.

Nem Inter nem Roboto. Não por esnobismo — são as fontes-padrão de toda tela gerada sem escolha, e
o produto perde identidade por omissão.

> **Correção de 11/09/2026 — o produto não tinha fonte.** Este documento dizia
> *"Bodoni 72/Didot"* e *"Avenir Next/Segoe UI"*, e justificava a escolha com *"são pilhas locais
> de sistema: não há download de fonte bloqueando a primeira tela"*. As duas primeiras de cada
> pilha **só existem no macOS**. Fora dele, o texto caía em Segoe UI ou Roboto e a identidade caía
> em Georgia — em silêncio. A economia era real e o custo era o produto inteiro: três aparências,
> nenhuma escolhida, e o único gesto editorial do sistema invisível para a maioria das telas.
>
> Pior, e menos óbvio: `font-weight: 600` e `650` sobre pilha estática fazem o navegador
> **sintetizar** o negrito, esticando o desenho em vez de trocá-lo. É o traço sujo que se sente
> sem conseguir nomear — e foi exatamente a reclamação que abriu esta revisão.
>
> Agora as duas famílias são **auto-hospedadas e variáveis** (`frontend/src/estilo/fontes.css`),
> servidas da mesma origem, sem CDN e sem nada a vazar para terceiros. **81 KB pelas duas
> famílias inteiras**, só o subset latino — que cobre o português completo, porque `ã`, `õ` e `ç`
> são Latin-1. A promessa original continua valendo: `font-display: swap` não bloqueia a primeira
> tela, e o service worker guarda os arquivos na primeira visita.
>
> E os pesos passam a ser tokens (`--p-normal` a `--p-pesado`), com uma escala de entreletra por
> tamanho (`--tr-micro` a `--tr-display`) que antes não existia — o mesmo tracking ia do rótulo de
> 10px ao total de 30px.

### 3b. Três convenções de texto, e cada coisa usa a sua

Descoberta ao revisar o conteúdo das telas: **rótulo de campo, título de seção, etiqueta de
status e cabeçalho de tabela estavam todos com o mesmo desenho** — versalete de 10px, peso 600,
`--tinta-fraca`. Quatro coisas de hierarquias diferentes renderizadas iguais. O agrupamento do
formulário não se lia, e a etiqueta gritava mais que o dado que ela nomeia.

| Papel | Desenho | Onde |
|---|---|---|
| **Rótulo** — nomeia um controle | caixa normal, 13px, `--p-forte`, `--tinta` | `.campo > span`, `.cmd-rotulo`, campos da entrada |
| **Seção** — divide um formulário | versalete 10px, `--p-pesado`, `--tinta-fraca`, **traço acima** | `.secao-titulo` |
| **Etiqueta** — classifica um registro | versalete 10px, `--tinta-fraca` | selo de status, tipo do item, cabeçalho de tabela |

O rótulo é o mais forte dos três, e está certo: ele é conteúdo. Seção é divisória, e divisória se
resolve com **traço e espaço** — não competindo em peso.

### 3c. Foco é tinta, nunca cor de status

O anel global era `--status-atendendo` — o mesmo azul de "em atendimento". Na grade da agenda, um
elemento focado passava a ter a cor de um atendimento em curso, violando a regra nº 1 deste
sistema. Agora há `--foco` (tinta) e `--foco-claro` (para o menu escuro): **o anel é o oposto do
fundo em que está**.

### 4. Escala de espaçamento fixa

`4 · 8 · 12 · 16 · 20 · 24 · 32 · 40`. Valor fora dela é **bug de rigor**, não licença criativa.

Foi exatamente o que fez a primeira versão do canvas ler como amadora: 18 aqui, 22 ali, 9 no
rodapé. Ninguém consegue nomear o problema, mas todo mundo sente.

## Contraste — medido, não estimado

Recalculado em 11/09/2026 sobre os valores hexadecimais da direção “back-office de recepção”, aplicando a
fórmula de luminância relativa da WCAG. A tabela anterior considerava apenas `--papel` como fundo.

| Par | Razão | AA (corpo) | Uso permitido |
|---|---|---:|---|
| `--tinta` sobre `--papel` | **16.38** | ✅ | qualquer |
| `--tinta` sobre `--papel-fundo` | **15.11** | ✅ | qualquer |
| `--tinta-fraca` sobre `--papel` | **6.41** | ✅ | texto secundário |
| `--tinta-fraca` sobre `--papel-fundo` | **5.91** | ✅ | texto secundário |
| `--tinta-tenue` sobre `--papel` | **3.03** | ❌ | traço e metadado não essencial |
| `--espelho-texto` sobre `--espelho` | **14.69** | ✅ | navegação principal |
| `--espelho-texto-fraco` sobre `--espelho` | **9.19** | ✅ | navegação secundária |
| `--papel` sobre `--vinho` | **9.04** | ✅ | avatar e ação marcada |
| `--tinta` sobre `--vinho-fundo` | **14.70** | ✅ | aviso e seleção clara |
| `--espelho` sobre `--metal` | **6.16** | ✅ | monograma |

### O que a casca de 11/09/2026 acrescentou

| Par | Razão | AA (corpo) | Uso |
|---|---:|:---:|---|
| `--tinta` sobre `--papel-afundado` | **13.90** | ✅ | linha marcada da busca |
| `--tinta-fraca` sobre `--papel-afundado` | **5.44** | ✅ | rota na linha marcada |
| `--espelho` sobre `--metal-claro` | **9.78** | ✅ | placa, extremo claro do aço |
| `--papel` sobre `--tinta-fraca` | **6.41** | ✅ | placa do login, extremo claro |
| `--espelho-texto-fraco` sobre `--espelho-ativo` | **6.46** | ✅ | ícone do item ativo |
| `--metal` sobre `--espelho-ativo` | **4.33** | ⚠️ | marcador do item ativo — **não é texto**, é traço de 2px, e o item já se distingue pelo fundo e pela cor do rótulo |

**E uma violação encontrada e corrigida na medição.** A rota exibida na busca (`/financeiro`)
nasceu em `--tinta-tenue`, que mede 3.03 sobre papel — exatamente o que a seção acima reserva a
"traço e separador". Rota é texto: alguém lê para saber para onde vai. Passou para
`--tinta-fraca`.

É a terceira vez que este token escapa para uso de texto. Ele continua no sistema porque traço
precisa dele — mas a regra não se sustenta sozinha, e vale medir toda tela nova.

### O que a medição mudou

**`--tinta-fraca` sobrevive aos dois fundos de trabalho.** Sobre `--papel` mede 6.41 e
sobre `--papel-fundo`, 5.91. A lista de resultados continua numa superfície própria por hierarquia,
mas contraste não depende mais disso.

Em `RT-CFG-001`, o formulário ainda usa superfície própria para separar grupos — agora por
hierarquia e não como remendo necessário para o contraste.

**Os status não servem para texto.** Nenhum passa em corpo de texto sobre papel, e é consequência
direta de eles compartilharem luminosidade 0.62 para pesarem igual. Continuam certos como borda,
barra e fundo claro; texto colorido em cima de papel, não. A ação de apagar dados perdeu o
vermelho por isso — e por já contradizer o próprio argumento de ser discreta.

**`--tinta-tenue` não sobrevive a nenhum uso de texto**, nem como placeholder: 3.03 é ilegível de
fato, não no papel. Ele fica reservado a traço e separador.

### O que RT-AGD-011 acrescentou

Duas armadilhas que a medição por si só **aprova**:

**`opacity` no container quebra o contraste em silêncio.** A verificação lê a cor declarada, não a
composta: um bloco com `opacity: 0.65` mede como se fosse opaco e reprova de fato. Opacidade não é
forma nem cor — é atalho que custa legibilidade. O que distingue um item apagado é borda, traço e
riscado.

**Fundo padronado não tem um contraste.** Hachura alterna dois tons, e o texto precisa passar contra
o **pior pixel** — não contra a média nem contra o que a medição encontra subindo a árvore até achar
um fundo sólido. Texto sobre padrão vai em `--tinta` cheia.

### Como verificar de novo

A prévia (`frontend/preview/`) roda a tela real sem backend; o console do navegador mede em
segundos. Estimar a partir da luminosidade foi o que produziu a tabela errada da primeira vez.

## Sombra e canto

**Sombra existe só para o que flutua** — menu suspenso, diálogo. Card não tem sombra. É o traço
mais reconhecível de tela gerada sem critério, e o mais fácil de evitar.

Raios de 4px, 8px e 12px cobrem bloco, controle e painel. O terceiro raio não é decoração: impede
que uma folha móvel tenha o mesmo desenho de um chip, sem transformar tudo em cápsula.

## Movimento

120ms e 180ms, curva `cubic-bezier(0.2, 0, 0, 1)`. Curto o bastante para não ser notado — a
recepção clica com pressa, e animação é atraso disfarçado de sofisticação.

`prefers-reduced-motion` zera as duas.

**A exceção que importa:** o rollback de uma ação otimista (R-UX-18) **precisa** ser visível. Se o
servidor recusar o agendamento que já apareceu na grade, ele tem que sumir de um jeito que a pessoa
perceba — desfazer silencioso é pior que erro visível.

## Fluidez e conexão

Fluidez aqui significa **preservar contexto**, não espalhar transições. Consultas recentes ficam
em memória por 30 minutos e são consideradas atuais por 30 segundos; voltar para uma tela não a
apaga para desenhar um spinner. A restauração de sessão mostra imediatamente a marca e uma única
linha de progresso, em vez de uma página branca.

Requisição operacional comum tem prazo máximo de 12 segundos. O app shell instalado usa cache
primeiro para seus arquivos e, numa navegação sem resposta, troca para a cópia local em 3 segundos.
Movimento continua restrito a 120/180 ms e respeita `prefers-reduced-motion`.

Offline é explícito. O sistema conserva dados já abertos, informa o horário do retrato e recusa
escritas na hora; nunca guarda uma mutação para executá-la depois contra uma agenda diferente.

## O que a crítica corrigiu

Registrado como calibragem: é o tipo de coisa que quem conhece a categoria nota e ninguém consegue
nomear.

| Achado | Por que importava |
|---|---|
| **Faltava a linha do "agora"** na agenda | Sem ela um calendário é uma tabela. Era a falha mais séria |
| Cabeçalho de coluna sem carga do dia | É o número que decide se dá para encaixar mais um |
| Barra de comandas amarela demais | Três comandas abertas não são urgência; só a de 3h20 é |
| Legenda permanente no rodapé | Ferramenta profissional não se explica todo dia para quem já sabe |
| Escala de espaçamento arbitrária | O que fazia ler como amadora, sem nome |
| Comanda sem subtotal por categoria | Serviço e produto têm comissão diferente; o fechamento separa |
| Botão anunciando o total | Faltavam R$ 69,40 a receber. **Não se fecha comanda com dinheiro em aberto** |

## O que este projeto não faz

Gradiente roxo · sombra grande em card · ícone genérico em toda linha · emoji em título ·
espaçamento uniforme de 24px · Inter e Roboto · cor decorativa fora da identidade · canto muito
arredondado · animação que se nota.

## Para o `RT-INF-010`

`design/tokens.css` é importado direto; o `tailwind.config` estende o tema **referenciando as
variáveis CSS**, nunca redeclarando os valores. Duas fontes de verdade para a mesma cor é como a
divergência começa.

Componentes que o canvas já define e que o front precisa reproduzir: bloco de agendamento (5
estados), chip de comanda aberta, selo de status, campo de busca com atalho, botão primário e
desabilitado, linha de item de comanda com executantes.
