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
| Acabamento na implementação | skill `frontend-design` | Pendente do `RT-INF-010` |
| Auditoria de acessibilidade | skill `design:accessibility-review` | Pendente do `RT-INF-010` |

Os arquivos-fonte do canvas vivem em `design/*.dc.html` e são re-semeados a cada alteração — o
canvas publicado nunca é editado direto.

## A direção, em uma frase

**Papel e tinta, densidade alta, referência de calendário nativo.** Não é minimalismo por moda: a
recepção usa isto com o telefone no ombro e a cliente em pé na frente, e cada pixel que decora é um
pixel que não informa.

## As quatro decisões que sustentam tudo

### 1. Cor só onde carrega informação

Status de agendamento, alerta de validade, divergência financeira. **O resto do produto é neutro.**

E cor **nunca em texto**: a medição de contraste mostrou que nenhum dos status passa em AA sobre
papel. Eles são borda, barra e fundo claro — o que já era a regra, agora com o motivo medido.

Os quatro status compartilham chroma (0.115) e luminosidade (0.62) em oklch, variando só a matiz —
é isso que faz os quatro pesarem igual na tela, em vez de um gritar mais alto por acidente de
saturação. Um accent nunca aparece em bloco cheio: entra como borda, barra lateral de 3px, ou fundo
de luminosidade alta na mesma matiz.

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

**Instrument Serif** aparece em exatamente dois lugares: identidade do estabelecimento e totais. O
negócio é beleza — um gesto editorial se justifica. Em texto operacional, nunca.

**Instrument Sans** em todo o resto, com `font-feature-settings: "tnum"` ligado. Numeral tabular
não é preciosismo: numa grade de horários, número que não alinha em coluna força o olho a
reprocessar cada linha.

Nem Inter nem Roboto. Não por esnobismo — são as fontes-padrão de toda tela gerada sem escolha, e
o produto perde identidade por omissão.

### 4. Escala de espaçamento fixa

`4 · 8 · 12 · 16 · 20 · 24 · 32 · 40`. Valor fora dela é **bug de rigor**, não licença criativa.

Foi exatamente o que fez a primeira versão do canvas ler como amadora: 18 aqui, 22 ali, 9 no
rodapé. Ninguém consegue nomear o problema, mas todo mundo sente.

## Contraste — medido, não estimado

Medido no navegador em 30/08/2026 (`RT-CLI-006`), convertendo os oklch para sRGB e aplicando a
fórmula da WCAG. **Substitui a tabela estimada anterior**, que errava por um motivo específico:
só considerava `--papel` como fundo.

| Par | Razão | AA (corpo) | Uso permitido |
|---|---|---:|---|
| `--tinta` sobre `--papel` | **15.77** | ✅ | qualquer |
| `--tinta` sobre `--papel-fundo` | **14.68** | ✅ | qualquer |
| `--papel` sobre `--tinta` | **15.77** | ✅ | botão escolhido |
| `--tinta` sobre `--status-*-fundo` | **15.5** | ✅ | texto dentro de aviso |
| `--tinta-fraca` sobre `--papel` | **4.67** | ✅ | texto secundário |
| `--tinta-fraca` sobre `--papel-fundo` | **4.34** | ❌ | só título grande |
| `--tinta-fraca` sobre `--papel-afundado` | **4.20** | ❌ | só título grande |
| `--status-falta` sobre `--papel` | **3.62** | ❌ | borda e ícone, nunca texto |
| `--status-confirmado` sobre `--papel` | **3.25** | ❌ | borda e ícone, nunca texto |
| `--tinta-tenue` sobre `--papel` | **2.53** | ❌ | nada, na prática |

### O que a medição mudou

**`--tinta-fraca` depende do fundo.** Sobre `--papel` passa; sobre `--papel-fundo` e
`--papel-afundado`, não. Foi por isso que a lista de resultados de `RT-CLI-006` virou uma
superfície com fundo `--papel` em vez de ficar solta sobre o fundo da página — o telefone da
cliente é a informação mais essencial daquela tela e estava no par que reprova.

Aconteceu de novo em `RT-CFG-001`, com o formulário de configuração, e virou **regra do produto**:
*texto secundário mora sobre papel*. Bloco que contenha rótulo, legenda ou explicação recebe
superfície própria; sobre o fundo da página só vai texto em `--tinta`.

**Os status não servem para texto.** Nenhum passa em corpo de texto sobre papel, e é consequência
direta de eles compartilharem luminosidade 0.62 para pesarem igual. Continuam certos como borda,
barra e fundo claro; texto colorido em cima de papel, não. A ação de apagar dados perdeu o
vermelho por isso — e por já contradizer o próprio argumento de ser discreta.

**`--tinta-tenue` não sobrevive a nenhum uso de texto**, nem como placeholder: 2.53 é ilegível de
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

Raios de 2px e 3px, e não existe um terceiro. Canto muito arredondado lê como app de consumo, não
como ferramenta de trabalho.

## Movimento

120ms e 180ms, curva `cubic-bezier(0.2, 0, 0, 1)`. Curto o bastante para não ser notado — a
recepção clica com pressa, e animação é atraso disfarçado de sofisticação.

`prefers-reduced-motion` zera as duas.

**A exceção que importa:** o rollback de uma ação otimista (R-UX-18) **precisa** ser visível. Se o
servidor recusar o agendamento que já apareceu na grade, ele tem que sumir de um jeito que a pessoa
perceba — desfazer silencioso é pior que erro visível.

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
espaçamento uniforme de 24px · Inter e Roboto · cor decorativa · canto muito arredondado ·
animação que se nota.

## Para o `RT-INF-010`

`design/tokens.css` é importado direto; o `tailwind.config` estende o tema **referenciando as
variáveis CSS**, nunca redeclarando os valores. Duas fontes de verdade para a mesma cor é como a
divergência começa.

Componentes que o canvas já define e que o front precisa reproduzir: bloco de agendamento (5
estados), chip de comanda aberta, selo de status, campo de busca com atalho, botão primário e
desabilitado, linha de item de comanda com executantes.
