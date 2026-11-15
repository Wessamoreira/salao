---
id: RT-INF-014
titulo: A marca Nexo e a porta do sistema
modulo: infra
fase: 7
perfil: leve
status: implementado
depende_de: [RT-INF-013]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-11-15
---

# RT-INF-014 — A marca Nexo e a porta do sistema

> **Como esta rotina terminou.** Três símbolos desenhados aqui foram recusados — o espelho com a
> linha do agora (lia como porta), a tesoura-relógio e o N-ligação. O dono trouxe a arte pronta
> (gerada no ChatGPT): o N em fita, de violeta a ciano, sobre noite. **Ela é a marca.** O
> trabalho desta rotina passou a ser levá-la para o sistema sem perder nada — §3b. As seções
> 3 e 6 registram a primeira tentativa, para não repeti-la.

## 3b. A marca que ficou

**Nexo** (nome de trabalho — ver abaixo). Arte do dono, guardada intacta em
`design/marca/original/`: `nexo-icone.png` (1254², a placa com o N) e `nexo-assinatura.png`
(2172×724, o N e o nome).

**O que foi feito com ela** — `design/marca/preparar-marca.py`, rodável de novo se a arte mudar:

| Peça | Problema na arte crua | Tratamento |
|---|---|---|
| Ícone `any` 192/512, favicon 48 | Preto sólido em volta da placa; e o azul-noite com brilho de borda, vetado pelo dono em `RT-INF-016` | O N sobre a placa de grafite, cantos transparentes de 96/432 |
| Ícone `maskable` 512 | O N ocupa a placa toda; o círculo do Android cortaria as pontas | N isolado do fundo e recomposto no grafite da moldura, dentro da zona segura |
| `apple-touch-icon` 180 | Idem, mas o iOS recorta em canto arredondado, não em círculo | N maior que no maskable |
| Assinatura | Retângulo preto e arcos de luz junto | N + nome isolados; cor desmultiplicada do fundo para a borda não levar halo |

**As cores**, medidas na arte e não aproximadas, estão em `design/tokens.css` (seção da marca):
noite `#07052c`, violeta `#8324fb`, anil `#1b3ffb`, ciano `#00f8fd`.

**A porta do sistema — direção D (editorial).** O dono pediu cor mais contida e uma porta
diferente; quatro direções foram desenhadas numa prancheta (noite, claro, painel da marca,
editorial — https://claude.ai/artifact/BgoXMgv7Chukaq5Th9viyF) e ele escolheu a D:

- a noite da marca, a assinatura pequena no canto, uma **saudação** grande ("Bom dia." /
  "Boa tarde." / "Boa noite.", pela hora do aparelho — `saudacao()`, com teste);
- campos que são só uma linha; o foco engrossa e acende a linha inteira;
- o N da arte enorme, vazando pela borda direita (WebP de 28 KB, não o PNG de 324 KB);
- **cor mínima: a única cor da tela é o N.** Texto, campos e botão são branco e os cinzas
  `--noite-*` (contraste medido: 17,9 · 12,0 · 7,9:1). O anil saiu do botão, que é branco;
- no toque, o N sobe para o canto, o texto fica abaixo dele com folga (nunca sobre a fita) e o
  botão ocupa a largura toda, no alcance do polegar.

**Onde a marca aparece — e onde não:** splash (`background_color` noite), carregamento (ícone
sobre a noite, barra branca), porta do sistema (direção D). **Dentro do sistema ela não aparece**: lá a cor é de quem carrega informação (status), e
degradê e brilho competiriam com ela. A placa com as iniciais do salão, na lateral, continua: é a
identidade do estabelecimento, não do produto.

**Nome de trabalho, não comercial.** "Nexo" colide com a Nexo CS Informática (software de gestão,
desde 1996) e os domínios óbvios têm dono; livres em 15/11/2026: `nexosalao.com.br`,
`nexobalcao.com.br`. Antes de vender o sistema: busca formal no INPI.

## 1. Objetivo

Dar ao produto um símbolo próprio — no ícone instalado, no carregamento e na porta do sistema —
e fazer da tela de entrar o primeiro lugar onde ele aparece inteiro.

## 2. Contexto

Até `RT-INF-013` o ícone era um "S" em Bodoni sobre a placa de aço, a mesma placa que mostra as
iniciais do salão na lateral. Duas coisas diferentes com o mesmo desenho: a placa é do **salão**
(muda por estabelecimento); o ícone é do **produto** (é o mesmo para todos). O dono pediu um
símbolo, e uma porta do sistema que não fosse mais um cartão branco centrado.

Não há ferramenta de geração de imagem nesta base, e não deveria haver para isto: ícone de PWA é
vetor — nítido de 16 a 512px, editável, versionado como texto.

## 3. A decisão

**Um espelho de bancada em arco, cortado pela linha do agora.** O raciocínio, a construção e as
variantes estão em `16-design-system.md`, seção "O símbolo". Três variações foram desenhadas e
comparadas de 200 a 16px; a escolhida é a única em que a linha do agora sobrevive em 16px:

| Variação | Por que não |
|---|---|
| Arco em contorno, linha cheia | O contorno some abaixo de 32px — vira um retângulo |
| Arco cheio, linha só por dentro | Sem a parte de fora, o ponto deixa de ser o marcador da régua |
| **Arco cheio, linha vazada que sai à esquerda com o ponto** | Escolhida |

**A porta do sistema é o símbolo em tamanho real e ao vivo.** Cartão em arco (o espelho), fundo com
o livro de horários, e a linha do agora atravessando o espelho na divisa entre a marca e o
formulário. O livro corre por baixo da linha até o minuto real; a hora encosta na borda do arco,
como o ponto do símbolo encosta no espelho.

## 4. Atores e permissões

Nenhuma. A porta é pública por definição.

## 5. Fluxo principal

1. O app instalado abre com o símbolo no splash (ícone + `background_color`).
2. O carregamento inicial mostra o símbolo sobre a moldura enquanto o cookie restaura a sessão.
3. Sem sessão, a porta: o espelho, a marca, e a linha do agora na hora certa.

## 6. Fluxos alternativos

**A1 — Fonte ainda carregando:** a altura do cartão muda quando a Bodoni chega; a divisa é medida
de novo em `document.fonts.ready`, e a linha não fica desalinhada.

**A2 — Erro de senha, texto do segundo fator:** mudam a altura do cartão; um `ResizeObserver` mede
de novo.

**A3 — Celular, conteúdo maior que a tela:** a linha e o livro são absolutos na tela, e não fixos
na janela — rolam junto com o espelho.

## 11. Casos de borda

| Situação | Comportamento |
|---|---|
| Fuso do aparelho diferente do salão | A porta mostra a hora do aparelho: antes de entrar não se sabe qual é o salão. Dentro da casca, o relógio é o do salão (ADR-0009) |
| Virada do minuto | Um `setTimeout` alinhado à virada, depois um intervalo de 60s — o mesmo padrão do relógio da casca |
| `prefers-reduced-motion` | Nada anima; a linha só muda de lugar uma vez por minuto |

## 14. UX e front

- `layout/Simbolo.tsx` — o símbolo, com `variante` `aco` (sobre a moldura) e `tinta` (sobre papel).
- `telas/Autenticacao.tsx` — o espelho, a medição da divisa e a hora.
- `public/pwa-icon.svg`, `favicon.svg`, `pwa-icon-192/512.png`, `pwa-maskable-512.png`,
  `apple-touch-icon.png` — o mesmo desenho, mesmas coordenadas do componente.
- Prévia: `npm run previa:login`.

## 15. Testes obrigatórios

- [x] Login, segundo fator e "minha senha" continuam passando (a porta é a mesma para os três)
- [x] Suíte inteira, typecheck, lint e build
- [x] Conferido em Chrome: desktop 1440×900 e celular 390×844 com área segura

## Pendências

- [ ] Nome comercial definitivo e busca no INPI antes de vender o sistema.
