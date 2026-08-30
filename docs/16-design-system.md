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

## Contraste — uma ressalva honesta

| Par | Uso | AA |
|---|---|---|
| `--tinta` sobre `--papel` | Texto principal | ✅ folgado |
| `--tinta-fraca` sobre `--papel` | Texto secundário | ✅ |
| `--tinta-tenue` sobre `--papel` | Rótulo, metadado | ⚠️ **não passa em corpo de texto** |

`--tinta-tenue` só pode ser usada em texto **não essencial**: rótulo em caixa alta, metadado,
placeholder, legenda. Nunca em informação que o usuário precise ler para decidir algo.

Isto será verificado de verdade no `RT-INF-010` com a skill `design:accessibility-review` — a
tabela acima é estimativa a partir da luminosidade, não medição.

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
